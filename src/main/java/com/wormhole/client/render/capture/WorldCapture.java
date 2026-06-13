package com.wormhole.client.render.capture;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.wormhole.Wormhole;
import com.wormhole.mixin.client.CameraInvokerMixin;
import com.wormhole.mixin.client.GameRendererAccessorMixin;
import com.wormhole.mixin.client.LevelRendererAccessorMixin;
import com.wormhole.mixin.client.LightmapExtractorAccessorMixin;
import com.wormhole.mixin.client.MinecraftAccessorMixin;
import com.wormhole.mixin.client.MinecraftRenderTargetMixin;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Renders the world from an arbitrary virtual camera into an off-screen {@link TextureTarget}.
 * Adapted from the validated portal context-switch (git history {@code PortalContextSwitch}),
 * stripped of the portal transform / oblique near-clip / stencil composite — here we just want a
 * clean perspective shot of the surroundings for the wormhole lensing cubemap capture.
 *
 * <p>Uses one shared dedicated {@link LevelRenderer} pointed at the current {@code mc.level};
 * captures are occasional (on demand), so the section re-cull cost is acceptable.
 */
public final class WorldCapture {
    /** Per-capture INFO logging — off by default since live capture runs every frame. */
    private static final boolean VERBOSE = false;

    private static LevelRenderer captureRenderer;
    private static ClientLevel boundLevel;
    private static GpuBuffer projGpuBuffer;
    private static GpuBuffer fogGpuBuffer;
    private static int errorLogCount = 0;

    /** >0 while a nested capture render is in flight; used to detect/block re-entrancy. */
    private static int captureDepth = 0;

    /** True when a cross-dim capture recomputed the lightmap for a remote dimension this frame, so it
     *  must be restored to the player's dimension before the hand/HUD render. */
    private static volatile boolean lightmapDirty = false;

    /** Basis of the most recent capture's virtual camera (world space), for cubemap face mapping. */
    private static final org.joml.Vector3f lastForward = new org.joml.Vector3f();
    private static final org.joml.Vector3f lastUp = new org.joml.Vector3f();
    private static final org.joml.Vector3f lastRight = new org.joml.Vector3f();

    public static org.joml.Vector3fc lastForward() {
        return lastForward;
    }

    public static org.joml.Vector3fc lastUp() {
        return lastUp;
    }

    public static org.joml.Vector3fc lastRight() {
        return lastRight;
    }

    private WorldCapture() {
    }

    /** True while a capture's nested renderLevel is running (so callers can avoid re-entering). */
    public static boolean isCapturing() {
        return captureDepth > 0;
    }

    /** Drops the dedicated renderer (call on disconnect / level change). */
    public static void dispose() {
        if (captureRenderer != null) {
            try {
                captureRenderer.setLevel(null);
            } catch (Exception ignored) {
                // best effort
            }
            captureRenderer = null;
        }
        boundLevel = null;
    }

    private static LevelRenderer getOrCreate(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            dispose();
            return null;
        }
        if (boundLevel != level) {
            dispose();
            boundLevel = level;
        }
        if (captureRenderer != null) {
            return captureRenderer;
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
            captureRenderer = r;
            Wormhole.LOGGER.info("[capture] Created dedicated capture renderer");
            return r;
        } catch (Exception e) {
            Wormhole.LOGGER.error("[capture] Failed to create capture renderer", e);
            return null;
        }
    }

    /**
     * Renders the world from {@code camPos} looking at {@code (yaw, pitch)} with a {@code fovDeg}
     * square FOV into {@code target}. Returns true if a frame was drawn (false if chunks aren't
     * compiled yet — try again shortly).
     */
    public static boolean capture(Vec3 camPos, float yaw, float pitch, float fovDeg, TextureTarget target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            Wormhole.LOGGER.info("[wh-cap] capture aborted: level/player null");
            return false;
        }
        if (captureDepth > 0) {
            Wormhole.LOGGER.warn("[wh-cap] RE-ENTRANT capture() blocked (depth={})", captureDepth);
            return false;
        }
        LevelRenderer renderer = getOrCreate(mc);
        if (renderer == null) {
            Wormhole.LOGGER.info("[wh-cap] capture aborted: no dedicated renderer");
            return false;
        }
        if (VERBOSE) {
            Wormhole.LOGGER.info("[wh-cap] capture ENTER camPos=({}, {}, {}) yaw={} pitch={} fov={} target={}x{}",
                fmt(camPos.x), fmt(camPos.y), fmt(camPos.z), yaw, pitch, fovDeg, target.width, target.height);
        }
        captureDepth++;
        try {
            boolean ok = doCapture(mc, renderer, mc.level, camPos, yaw, pitch, fovDeg, target, false);
            if (VERBOSE) {
                Wormhole.LOGGER.info("[wh-cap] capture EXIT ok={}", ok);
            }
            return ok;
        } catch (Exception e) {
            if (errorLogCount++ < 5) {
                Wormhole.LOGGER.error("[wh-cap] world capture failed", e);
            }
            return false;
        } finally {
            captureDepth--;
        }
    }

    /**
     * Like {@link #capture}, but renders a caller-supplied {@link ClientLevel} via a caller-supplied
     * dedicated {@link LevelRenderer} (e.g. a remote dimension from {@code RemoteDimensions}) instead
     * of {@code mc.level} + the shared capture renderer. Used for cross-dimensional mouth views.
     */
    public static boolean captureLevel(ClientLevel level, LevelRenderer renderer, Vec3 camPos,
                                       float yaw, float pitch, float fovDeg, TextureTarget target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || level == null || renderer == null) {
            return false;
        }
        if (captureDepth > 0) {
            return false;
        }
        captureDepth++;
        try {
            return doCapture(mc, renderer, level, camPos, yaw, pitch, fovDeg, target, true);
        } catch (Exception e) {
            if (errorLogCount++ < 5) {
                Wormhole.LOGGER.error("[wh-cap] remote-level capture failed", e);
            }
            return false;
        } finally {
            captureDepth--;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static boolean doCapture(Minecraft mc, LevelRenderer renderer, ClientLevel level,
                                     Vec3 camPos, float yaw, float pitch, float fovDeg, TextureTarget target,
                                     boolean refreshLightmap) {
        DeltaTracker deltaTracker = mc.getDeltaTracker();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        Camera virtualCamera = new Camera();
        virtualCamera.setLevel(level);
        virtualCamera.setEntity(mc.player);
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setRotation(yaw, pitch);
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setPosition(camPos);
        virtualCamera.tick();

        // Stash the camera basis so the cubemap shader can map directions to this face without
        // guessing conventions (right = -left).
        org.joml.Vector3fc f = virtualCamera.forwardVector();
        org.joml.Vector3fc u = virtualCamera.upVector();
        org.joml.Vector3fc l = virtualCamera.leftVector();
        lastForward.set(f.x(), f.y(), f.z());
        lastUp.set(u.x(), u.y(), u.z());
        lastRight.set(-l.x(), -l.y(), -l.z());

        // Square perspective projection (aspect from the target; cube faces are 1:1).
        float aspect = (float) target.width / (float) target.height;
        float far = Math.max(mc.options.getEffectiveRenderDistance(), 2) * 16.0F * 4.0F;
        Matrix4f projMatrix = new Matrix4f().setPerspective(
            (float) Math.toRadians(fovDeg), aspect, 0.05F, far, RenderSystem.getDevice().isZZeroToOne());

        Matrix4f viewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(viewMatrix);

        // Physics cross-check: the look vector the camera will actually use (MC convention) must
        // match the intended outward direction logged by the caller. If these disagree, the
        // yaw/pitch derivation is wrong, not the render.
        if (VERBOSE) {
            double yr = Math.toRadians(yaw);
            double pr = Math.toRadians(pitch);
            Wormhole.LOGGER.info("[wh-cap] projection fov={} aspect={} near=0.05 far={} | camera look=({}, {}, {})",
                fovDeg, fmt(aspect), fmt(far),
                fmt(-Math.sin(yr) * Math.cos(pr)), fmt(-Math.sin(pr)), fmt(Math.cos(yr) * Math.cos(pr)));
        }
        Frustum frustum = new Frustum(viewMatrix, projMatrix);
        frustum.prepare(camPos.x, camPos.y, camPos.z);
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setCullFrustum(frustum);
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setInitialized(true);

        // Re-cull the dedicated renderer's sections around the virtual camera; compile nearby dirty ones.
        LevelRendererAccessorMixin access = (LevelRendererAccessorMixin) renderer;
        LevelRenderState lrs = access.wormhole$getLevelRenderState();
        ViewArea viewArea = access.wormhole$getViewArea();
        ObjectArrayList<RenderSection> visibleSections = access.wormhole$getVisibleSections();
        if (viewArea != null) {
            SectionPos camSec = SectionPos.of(camPos);
            viewArea.repositionCamera(camSec);
            SectionRenderDispatcher dispatcher = renderer.getSectionRenderDispatcher();
            if (dispatcher != null) {
                dispatcher.setCameraPosition(camPos);
            }
            RenderRegionCache cache = new RenderRegionCache();
            visibleSections.clear();
            int camSecX = camSec.x();
            int camSecZ = camSec.z();
            int scheduled = 0;
            for (RenderSection section : viewArea.sections) {
                if (section == null) {
                    continue;
                }
                long node = section.getSectionNode();
                int sx = SectionPos.x(node);
                int sz = SectionPos.z(node);
                if (level.getChunkSource().hasChunk(sx, sz) && frustum.isVisible(section.getBoundingBox())) {
                    if (section.isDirty()) {
                        int dx = sx - camSecX;
                        int dz = sz - camSecZ;
                        if (dx * dx + dz * dz <= 64 && scheduled < 128) {
                            section.rebuildSectionAsync(cache);
                            section.setNotDirty();
                            scheduled++;
                        }
                    }
                    visibleSections.add(section);
                }
            }
        }

        renderer.extractLevel(deltaTracker, virtualCamera, partialTick);
        ChunkSectionsToRender chunks = lrs.chunkSectionsToRender;
        if (VERBOSE) {
            Wormhole.LOGGER.info("[wh-cap] visibleSections={} chunks.maxIndices={}",
                visibleSections.size(), chunks == null ? -1 : chunks.maxIndicesRequired());
        }
        if (chunks == null || chunks.maxIndicesRequired() == 0) {
            return false; // not compiled yet
        }

        CameraRenderState camState = lrs.cameraRenderState;
        virtualCamera.extractRenderState(camState, partialTick);
        camState.projectionMatrix.set(projMatrix);
        if (camState.entityRenderState != null) {
            camState.entityRenderState.bob = 0.0F;
            camState.entityRenderState.backwardsInterpolatedWalkDistance = 0.0F;
            camState.entityRenderState.hurtTime = -1.0F;
            camState.entityRenderState.hurtDuration = 1;
            camState.entityRenderState.isDeadOrDying = false;
        }

        FogRenderer fogRenderer = ((GameRendererAccessorMixin) mc.gameRenderer).wormhole$getFogRenderer();
        FogData fogData = fogRenderer.setupFog(virtualCamera, mc.options.getEffectiveRenderDistance(),
            deltaTracker, 0.0F, level);
        FogData savedFogData = camState.fogData;
        FogType savedFogType = camState.fogType;
        camState.fogData = fogData;
        camState.fogType = FogType.NONE;
        GpuBufferSlice fogBuffer = writeFogBuffer(fogData);

        Vec3 savedCamPos = mc.gameRenderer.getMainCamera().position();
        long savedGameTime = level.getGameTime();
        ChunkSectionsToRender chunksToRender = chunks;

        try {
            withSwitchedWorld(renderer, target, virtualCamera, () -> {
                Matrix4fStack mvStack = RenderSystem.getModelViewStack();
                mvStack.pushMatrix();
                mvStack.identity();
                try {
                    RenderSystem.backupProjectionMatrix();
                    RenderSystem.setProjectionMatrix(writeProjectionBuffer(projMatrix), ProjectionType.PERSPECTIVE);
                    GameRenderState grs = mc.gameRenderer.getGameRenderState();
                    try {
                        mc.gameRenderer.getGlobalSettingsUniform().update(
                            target.width, target.height,
                            grs.optionsRenderState.glintStrength, level.getGameTime(), deltaTracker,
                            grs.optionsRenderState.menuBackgroundBlurriness, camPos, false);
                        // Recompute the lightmap for THIS capture's environment: the virtual camera is
                        // the active main camera here, and its attribute probe was ticked in the remote
                        // level, so re-extracting yields the remote dimension's sky/ambient light curve
                        // (else e.g. the overworld through a nether mouth is lit dark by the nether's).
                        if (refreshLightmap) {
                            refreshLightmap(mc, grs, partialTick);
                        }
                        renderer.update(virtualCamera);
                        renderer.renderLevel(
                            GraphicsResourceAllocator.UNPOOLED, deltaTracker, false, camState,
                            viewMatrix, fogBuffer, fogData.color, true, chunksToRender);
                    } finally {
                        mc.gameRenderer.getGlobalSettingsUniform().update(
                            target.width, target.height,
                            grs.optionsRenderState.glintStrength, savedGameTime, deltaTracker,
                            grs.optionsRenderState.menuBackgroundBlurriness, savedCamPos, false);
                        RenderSystem.restoreProjectionMatrix();
                    }
                } finally {
                    mvStack.popMatrix();
                }
            });
        } finally {
            camState.fogData = savedFogData;
            camState.fogType = savedFogType;
            freeTransientBuffers();
        }
        return true;
    }

    private static void withSwitchedWorld(LevelRenderer renderer, RenderTarget target,
                                          Camera camera, Runnable renderCallback) {
        Minecraft mc = Minecraft.getInstance();
        GameRendererAccessorMixin gameRendererAccess = (GameRendererAccessorMixin) mc.gameRenderer;
        MinecraftAccessorMixin mcAccess = (MinecraftAccessorMixin) (Object) mc;

        RenderTarget savedMainRT = mc.getMainRenderTarget();
        LevelRenderer savedRenderer = mc.levelRenderer;
        Camera savedMainCamera = gameRendererAccess.wormhole$getMainCamera();
        HitResult savedHitResult = mc.hitResult;
        LocalPlayer player = mc.player;
        boolean savedNoPhysics = player != null && player.noPhysics;
        try {
            ((MinecraftRenderTargetMixin) (Object) mc).wormhole$setMainRenderTarget(target);
            mcAccess.wormhole$setLevelRenderer(renderer);
            gameRendererAccess.wormhole$setMainCamera(camera);
            mc.hitResult = null;
            if (player != null) {
                player.noPhysics = true;
            }
            renderCallback.run();
        } finally {
            if (player != null) {
                player.noPhysics = savedNoPhysics;
            }
            mc.hitResult = savedHitResult;
            gameRendererAccess.wormhole$setMainCamera(savedMainCamera);
            mcAccess.wormhole$setLevelRenderer(savedRenderer);
            ((MinecraftRenderTargetMixin) (Object) mc).wormhole$setMainRenderTarget(savedMainRT);
        }
    }

    /** Force the lightmap to recompute for the currently-active (virtual) camera + render it. */
    private static void refreshLightmap(Minecraft mc, GameRenderState grs, float partialTick) {
        GameRendererAccessorMixin acc = (GameRendererAccessorMixin) mc.gameRenderer;
        ((LightmapExtractorAccessorMixin) (Object) acc.wormhole$getLightmapExtractor()).wormhole$setNeedsUpdate(true);
        acc.wormhole$getLightmapExtractor().extract(grs.lightmapRenderState, partialTick);
        acc.wormhole$getLightmap().render(grs.lightmapRenderState);
        lightmapDirty = true;
    }

    /**
     * After cross-dim captures left the lightmap on a remote dimension, recompute it for the player's
     * real camera so the hand/HUD aren't lit by the wrong dimension. No-op if no cross-dim capture
     * happened this frame. Call once per frame after the mouth captures.
     */
    public static void restoreLightmap() {
        if (!lightmapDirty) {
            return;
        }
        lightmapDirty = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return;
        }
        GameRenderState grs = mc.gameRenderer.getGameRenderState();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        GameRendererAccessorMixin acc = (GameRendererAccessorMixin) mc.gameRenderer;
        ((LightmapExtractorAccessorMixin) (Object) acc.wormhole$getLightmapExtractor()).wormhole$setNeedsUpdate(true);
        acc.wormhole$getLightmapExtractor().extract(grs.lightmapRenderState, pt);
        acc.wormhole$getLightmap().render(grs.lightmapRenderState);
    }

    private static void freeTransientBuffers() {
        if (projGpuBuffer != null) {
            projGpuBuffer.close();
            projGpuBuffer = null;
        }
        if (fogGpuBuffer != null) {
            fogGpuBuffer.close();
            fogGpuBuffer = null;
        }
    }

    private static GpuBufferSlice writeProjectionBuffer(Matrix4f matrix) {
        ByteBuffer buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        matrix.get(buf);
        buf.position(64);
        buf.flip();
        projGpuBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_capture_proj", 128, buf);
        return projGpuBuffer.slice();
    }

    private static GpuBufferSlice writeFogBuffer(FogData fog) {
        ByteBuffer buf = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        Std140Builder.intoBuffer(buf)
            .putVec4(fog.color)
            .putFloat(fog.environmentalStart)
            .putFloat(fog.environmentalEnd)
            .putFloat(fog.renderDistanceStart)
            .putFloat(fog.renderDistanceEnd)
            .putFloat(fog.skyEnd)
            .putFloat(fog.cloudEnd);
        buf.position(48);
        buf.flip();
        fogGpuBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_capture_fog", 128, buf);
        return fogGpuBuffer.slice();
    }
}
