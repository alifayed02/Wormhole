package com.wormhole.client.render.remote;

import com.wormhole.Wormhole;
import com.wormhole.mixin.client.LevelRendererAccessorMixin;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientLevel.ClientLevelData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Holds a synthetic {@link ClientLevel} + dedicated {@link LevelRenderer} per remote dimension, so a
 * cross-dimensional mouth can render the partner dimension that the client doesn't natively load.
 * Mirrors SeamlessPortals' {@code PortalWorldManager} (MC 26.1) and reuses the same dedicated-renderer
 * construction as {@link com.wormhole.client.render.capture.WorldCapture}. The capture renders this
 * level via {@code WorldCapture.capture(level, renderer, …)}.
 *
 * <p>v1 is geometry + light only: chunks are fed (synchronously here for the spike; budget-drained in
 * the streaming phase) and the section meshes compile on the dedicated renderer.
 */
public final class RemoteDimensions {
    private static final Map<ResourceKey<Level>, ClientLevel> LEVELS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, LevelRenderer> RENDERERS = new ConcurrentHashMap<>();

    private RemoteDimensions() {
    }

    public static ClientLevel levelFor(ResourceKey<Level> dim) {
        return LEVELS.get(dim);
    }

    public static LevelRenderer rendererFor(ResourceKey<Level> dim) {
        return RENDERERS.get(dim);
    }

    /** Lazily build (or fetch) the synthetic level + dedicated renderer for {@code dim}. */
    public static ClientLevel getOrCreate(ResourceKey<Level> dim) {
        ClientLevel existing = LEVELS.get(dim);
        if (existing != null) {
            return existing;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            return null;
        }
        try {
            GameRenderState grs = mc.gameRenderer.getGameRenderState();
            RenderBuffers buffers = new RenderBuffers(4);
            SubmitNodeStorage submitNodes = new SubmitNodeStorage();
            FeatureRenderDispatcher featureDispatcher = new FeatureRenderDispatcher(
                submitNodes, mc.getModelManager(), buffers.bufferSource(), mc.getAtlasManager(),
                buffers.outlineBufferSource(), buffers.crumblingBufferSource(), mc.font, grs);
            LevelRenderer renderer = new LevelRenderer(mc, mc.getEntityRenderDispatcher(),
                mc.getBlockEntityRenderDispatcher(), buffers, grs, featureDispatcher);
            Holder<DimensionType> dimType = mc.level.registryAccess()
                .lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(dimTypeKey(dim));
            ClientLevelData data = new ClientLevelData(Difficulty.NORMAL, false, false);
            ClientLevel level = new ClientLevel(mc.getConnection(), data, dim, dimType,
                8, 8, renderer, false, 0L, mc.level.getSeaLevel());
            ((LevelRendererAccessorMixin) renderer).wormhole$setLevelRenderState(new LevelRenderState());
            renderer.setLevel(level);
            renderer.onResourceManagerReload(mc.getResourceManager());
            LEVELS.put(dim, level);
            RENDERERS.put(dim, renderer);
            Wormhole.LOGGER.info("[crossdim] created remote level + renderer for {}", dim.identifier());
            return level;
        } catch (Exception e) {
            Wormhole.LOGGER.error("[crossdim] failed to create remote level for " + dim.identifier(), e);
            return null;
        }
    }

    private static ResourceKey<DimensionType> dimTypeKey(ResourceKey<Level> dim) {
        if (dim.equals(Level.NETHER)) {
            return BuiltinDimensionTypes.NETHER;
        }
        if (dim.equals(Level.END)) {
            return BuiltinDimensionTypes.END;
        }
        return BuiltinDimensionTypes.OVERWORLD;
    }

    /**
     * Feed one chunk's sections + light into the remote level and mark its sections for recompile.
     * Synchronous (used by the spike); the streaming phase budget-drains this same work.
     */
    public static void feedChunk(ResourceKey<Level> dim, int cx, int cz,
                                 LevelChunkSection[] sections, DataLayer[] sky, DataLayer[] block) {
        ClientLevel level = getOrCreate(dim);
        LevelRenderer renderer = RENDERERS.get(dim);
        if (level == null || renderer == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (LevelChunkSection s : sections) {
                s.write(buf);
            }
            level.getChunkSource().replaceWithPacketData(cx, cz, buf, Collections.emptyMap(), tag -> {
            });
        } finally {
            buf.release();
        }
        LevelLightEngine lightEngine = level.getLightEngine();
        int minSection = level.getMinSectionY();
        for (int i = 0; i < sections.length; i++) {
            SectionPos sp = SectionPos.of(cx, minSection + i, cz);
            if (sky != null && i < sky.length && sky[i] != null) {
                lightEngine.queueSectionData(LightLayer.SKY, sp, sky[i]);
            }
            if (block != null && i < block.length && block[i] != null) {
                lightEngine.queueSectionData(LightLayer.BLOCK, sp, block[i]);
            }
            renderer.setSectionDirtyWithNeighbors(cx, minSection + i, cz);
        }
    }

    // ===== SPIKE (temporary; remove after the Phase 1.3 go/no-go gate) =====
    private static final java.util.Set<Long> SPIKE_FED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * SPIKE stand-in for streamed remote data: feed the synthetic {@code dim} level from the CURRENT
     * client level's chunks around {@code center}, once per chunk. Proves the synthetic-level render
     * path before the real server streaming exists. (The geometry will be the local world's, shown
     * through the cross-dim mouth — that's fine for the render-path proof.)
     */
    public static void spikeFeedFromCurrentLevel(ResourceKey<Level> dim, net.minecraft.world.phys.Vec3 center) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int ccx = (int) Math.floor(center.x) >> 4;
        int ccz = (int) Math.floor(center.z) >> 4;
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = ccx + dx;
                int cz = ccz + dz;
                long key = (((long) dim.identifier().hashCode()) << 42) ^ (((long) cx & 0x1FFFFF) << 21) ^ (cz & 0x1FFFFF);
                if (!SPIKE_FED.add(key)) {
                    continue;
                }
                if (!mc.level.getChunkSource().hasChunk(cx, cz)) {
                    SPIKE_FED.remove(key); // retry once the chunk loads
                    continue;
                }
                net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                int minSec = mc.level.getMinSectionY();
                LevelLightEngine le = mc.level.getLightEngine();
                DataLayer[] sky = new DataLayer[sections.length];
                DataLayer[] block = new DataLayer[sections.length];
                for (int i = 0; i < sections.length; i++) {
                    SectionPos sp = SectionPos.of(cx, minSec + i, cz);
                    sky[i] = le.getLayerListener(LightLayer.SKY).getDataLayerData(sp);
                    block[i] = le.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp);
                }
                feedChunk(dim, cx, cz, sections, sky, block);
            }
        }
    }
    // ===== end SPIKE =====

    public static void dispose() {
        for (LevelRenderer r : RENDERERS.values()) {
            try {
                r.setLevel(null);
            } catch (Exception ignored) {
                // best effort
            }
        }
        RENDERERS.clear();
        LEVELS.clear();
    }
}
