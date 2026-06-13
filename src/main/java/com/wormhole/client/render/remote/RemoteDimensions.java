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
    /** The dimension the player is currently IN (the real mc.level) — vanilla owns its chunks; we
     *  never feed it as a remote level. */
    private static volatile ResourceKey<Level> liveDim;

    public static void setLiveDimension(ResourceKey<Level> dim) {
        liveDim = dim;
    }

    public static boolean isLive(ResourceKey<Level> dim) {
        return dim != null && dim.equals(liveDim);
    }

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
            // Size the chunk cache to the real render distance (not a small fixed 8) so the mouth view
            // is complete AND, after this level is promoted to live, the server's chunk stream around
            // the player is accepted instead of rejected as "not in view range".
            int viewRadius = Math.max(2, mc.options.getEffectiveRenderDistance());
            ClientLevel level = new ClientLevel(mc.getConnection(), data, dim, dimType,
                viewRadius, viewRadius, renderer, false, 0L, mc.level.getSeaLevel());
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
        if (isLive(dim)) {
            return; // the dimension the player is in is the real level — vanilla owns its chunks
        }
        ClientLevel level = getOrCreate(dim);
        LevelRenderer renderer = RENDERERS.get(dim);
        if (level == null || renderer == null) {
            return;
        }
        // Centre the synthetic chunk cache on the remote mouth, else replaceWithPacketData drops
        // chunks "not in view range" (the cache defaults to 0,0). Mirrors SeamlessPortals.
        net.minecraft.world.phys.Vec3 center = com.wormhole.client.ClientPortalStore.remoteCenterFor(dim);
        if (center != null) {
            level.getChunkSource().updateViewCenter(((int) Math.floor(center.x)) >> 4, ((int) Math.floor(center.z)) >> 4);
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

    /** A level + its dedicated renderer handed out to become the live mc.level / mc.levelRenderer. */
    public record Promotion(ClientLevel level, LevelRenderer renderer) {
    }

    /**
     * Hand out the cached level + renderer for {@code dim} to become live, re-pointing the renderer at
     * the shared {@link LevelRenderState} and wiping mirrored entities (the respawn re-adds the real
     * player). Returns null if {@code dim} isn't cached (caller falls back to vanilla). Ports
     * SeamlessPortals' {@code promoteToMain}.
     */
    public static Promotion promote(ResourceKey<Level> dim, LevelRenderState shared) {
        ClientLevel level = LEVELS.remove(dim);
        LevelRenderer renderer = RENDERERS.remove(dim);
        if (level == null || renderer == null) {
            return null;
        }
        ((LevelRendererAccessorMixin) renderer).wormhole$setLevelRenderState(shared);
        net.minecraft.client.player.LocalPlayer self = Minecraft.getInstance().player;
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (e != self) {
                ids.add(e.getId());
            }
        }
        for (int id : ids) {
            try {
                level.removeEntity(id, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            } catch (Exception ignored) {
                // best effort
            }
        }
        Wormhole.LOGGER.info("[crossdim] promoted remote level {} to live", dim.identifier());
        return new Promotion(level, renderer);
    }

    /**
     * Cache the level + renderer being LEFT (give the renderer a fresh render state) so re-crossing
     * re-promotes it. Kept warm by continued streaming while it's the partner side.
     */
    public static void demote(ResourceKey<Level> dim, ClientLevel level, LevelRenderer renderer) {
        ((LevelRendererAccessorMixin) renderer).wormhole$setLevelRenderState(new LevelRenderState());
        LEVELS.put(dim, level);
        RENDERERS.put(dim, renderer);
        Wormhole.LOGGER.info("[crossdim] demoted (cached) left level {}", dim.identifier());
    }

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
