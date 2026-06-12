package com.wormhole.client.render;

import com.wormhole.Wormhole;
import com.wormhole.mixin.client.LevelRendererAccessorMixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Owns a dedicated {@link LevelRenderer} used to render the world from the portal's virtual camera.
 *
 * <p>It is pointed at the CURRENT {@code mc.level} (same-dimension prototype), so it shares the
 * loaded chunk data but keeps its own per-camera render state (viewArea / visibleSections /
 * levelRenderState) isolated from the main renderer. Recreated whenever the level changes.
 */
public final class PortalRenderer {
    private static LevelRenderer renderer;
    private static ClientLevel boundLevel;

    private PortalRenderer() {
    }

    /** Returns the dedicated renderer for the current level, creating it if needed (or null). */
    public static LevelRenderer getOrCreate(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            dispose();
            return null;
        }
        if (renderer != null && boundLevel == level) {
            return renderer;
        }
        dispose();
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
            renderer = r;
            boundLevel = level;
            Wormhole.LOGGER.info("[render] Created dedicated portal renderer for current level");
            return renderer;
        } catch (Exception e) {
            Wormhole.LOGGER.error("[render] Failed to create dedicated portal renderer", e);
            dispose();
            return null;
        }
    }

    /** Debug ({@code [wh-light]}): visible-section count of the dedicated renderer, or -1 if absent. */
    public static int debugVisibleSections() {
        if (renderer == null) {
            return -1;
        }
        try {
            return ((LevelRendererAccessorMixin) renderer).wormhole$getVisibleSections().size();
        } catch (Exception e) {
            return -2;
        }
    }

    public static void dispose() {
        if (renderer != null) {
            try {
                renderer.setLevel(null);
            } catch (Exception ignored) {
                // best effort cleanup
            }
            renderer = null;
        }
        boundLevel = null;
    }
}
