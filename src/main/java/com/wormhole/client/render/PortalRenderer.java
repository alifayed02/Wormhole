package com.wormhole.client.render;

import com.wormhole.Wormhole;
import com.wormhole.mixin.client.LevelRendererAccessorMixin;
import com.wormhole.portal.PortalEnd;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Owns one dedicated {@link LevelRenderer} PER PORTAL DESTINATION END, used to render the world
 * from that portal's virtual camera.
 *
 * <p>Per-end (not shared): with a single shared renderer, rendering a pair's two ends each frame
 * repositioned one view area back and forth between two regions, reassigning a band of section
 * slots every flip — perpetual recompiles (Chunk Sections UBO churn) and sections drawn before
 * their rebuild finished or with light baked before it had streamed in (the "black patches
 * through one portal" bug). A per-end renderer keeps its view area pinned at its destination, so
 * sections compile once and stay.
 *
 * <p>Renderers are pointed at the CURRENT {@code mc.level} (same-dimension prototype), sharing
 * loaded chunk data but with isolated per-camera render state. All are dropped on level change.
 * {@link #mirrorSectionDirty} keeps their sections in sync with world updates (vanilla only
 * notifies the main renderer).
 */
public final class PortalRenderer {
    private static final Map<PortalEnd, LevelRenderer> RENDERERS = new LinkedHashMap<>();
    private static ClientLevel boundLevel;

    private PortalRenderer() {
    }

    /** Returns the dedicated renderer for the given destination end, creating it if needed (or null). */
    public static LevelRenderer getOrCreate(Minecraft mc, PortalEnd destEnd) {
        ClientLevel level = mc.level;
        if (level == null) {
            dispose();
            return null;
        }
        if (boundLevel != level) {
            dispose();
            boundLevel = level;
        }
        LevelRenderer existing = RENDERERS.get(destEnd);
        if (existing != null) {
            return existing;
        }
        try {
            GameRenderState gameRenderState = mc.gameRenderer.getGameRenderState();
            RenderBuffers buffers = new RenderBuffers(4);
            SubmitNodeStorage submitNodes = new SubmitNodeStorage();
            FeatureRenderDispatcher featureDispatcher = new FeatureRenderDispatcher(
                submitNodes, mc.getModelManager(), buffers.bufferSource(), mc.getAtlasManager(),
                buffers.outlineBufferSource(), buffers.crumblingBufferSource(), mc.font, gameRenderState);
            LevelRenderer r = new LevelRenderer(mc, mc.getEntityRenderDispatcher(),
                mc.getBlockEntityRenderDispatcher(), buffers, gameRenderState, featureDispatcher);
            ((LevelRendererAccessorMixin) r).wormhole$setLevelRenderState(new LevelRenderState());
            r.setLevel(level);
            r.onResourceManagerReload(mc.getResourceManager());
            RENDERERS.put(destEnd, r);
            Wormhole.LOGGER.info("[render] Created dedicated portal renderer for end at {} ({} total)",
                destEnd.getOrigin().toShortString(), RENDERERS.size());
            return r;
        } catch (Exception e) {
            Wormhole.LOGGER.error("[render] Failed to create dedicated portal renderer", e);
            return null;
        }
    }

    /** Drops renderers whose destination end no longer exists (portal removed/relinked). */
    public static void retainOnly(Set<PortalEnd> activeEnds) {
        Iterator<Map.Entry<PortalEnd, LevelRenderer>> it = RENDERERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PortalEnd, LevelRenderer> entry = it.next();
            if (!activeEnds.contains(entry.getKey())) {
                quietDispose(entry.getValue());
                it.remove();
            }
        }
    }

    /**
     * Mirrors a main-renderer section-dirty mark into every portal view area, so block changes
     * and late light updates recompile portal-view sections too. Called from
     * {@code LevelRendererDirtyMixin}.
     */
    public static void mirrorSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged) {
        for (LevelRenderer r : RENDERERS.values()) {
            ViewArea viewArea = ((LevelRendererAccessorMixin) r).wormhole$getViewArea();
            if (viewArea != null) {
                viewArea.setDirty(sectionX, sectionY, sectionZ, playerChanged);
            }
        }
    }

    /** Debug ({@code [wh-light]}): total visible sections across portal renderers, or -1 if none. */
    public static int debugVisibleSections() {
        if (RENDERERS.isEmpty()) {
            return -1;
        }
        int total = 0;
        for (LevelRenderer r : RENDERERS.values()) {
            try {
                total += ((LevelRendererAccessorMixin) r).wormhole$getVisibleSections().size();
            } catch (Exception e) {
                return -2;
            }
        }
        return total;
    }

    public static void dispose() {
        for (LevelRenderer r : RENDERERS.values()) {
            quietDispose(r);
        }
        RENDERERS.clear();
        boundLevel = null;
    }

    private static void quietDispose(LevelRenderer renderer) {
        try {
            renderer.setLevel(null);
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }
}
