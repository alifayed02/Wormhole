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
            float aspect = (float) target.width / (float) target.height;
            float far = Math.max(mc.options.getEffectiveRenderDistance(), 2) * 16.0F * 4.0F;
            Matrix4f proj = new Matrix4f().setPerspective(
                (float) Math.toRadians(fovDeg), aspect, 0.05F, far, RenderSystem.getDevice().isZZeroToOne());
            boolean ok = doCapture(mc, renderer, mc.level, camPos, yaw, pitch, proj, null, null, target);
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
     * Renders the world from {@code camPos} looking at {@code (yaw, pitch)} using the caller-supplied
     * {@code projMatrix}, with an optional Lengyel oblique near-clip at the plane through
     * {@code clipCenter} (normal {@code clipNormal}). Used for the seamless portal window (parallax-
     * correct destination view). Returns true if a frame was drawn.
     */
    public static boolean captureWithProjection(Vec3 camPos, float yaw, float pitch, Matrix4f projMatrix,
                                                Vec3 clipCenter, Vec3 clipNormal, TextureTarget target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        if (captureDepth > 0) {
            return false;
        }
        LevelRenderer renderer = getOrCreate(mc);
        if (renderer == null) {
            return false;
        }
        captureDepth++;
        try {
            return doCapture(mc, renderer, mc.level, camPos, yaw, pitch, projMatrix, clipCenter, clipNormal, target);
        } catch (Exception e) {
            if (errorLogCount++ < 5) {
                Wormhole.LOGGER.error("[wh-cap] window capture failed", e);
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
                                     Vec3 camPos, float yaw, float pitch, Matrix4f projMatrix,
                                     Vec3 clipCenter, Vec3 clipNormal, TextureTarget target) {
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

        // Oblique near-clip (optional): tilt the supplied projection's near plane onto the
        // destination mouth so near-side geometry is culled (seamless portal window).
        if (clipCenter != null && clipNormal != null) {
            applyObliqueNearPlane(projMatrix, virtualCamera, camPos, clipCenter, clipNormal);
        }

        Matrix4f viewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(viewMatrix);

        // Physics cross-check: the look vector the camera will actually use (MC convention) must
        // match the intended outward direction logged by the caller. If these disagree, the
        // yaw/pitch derivation is wrong, not the render.
        if (VERBOSE) {
            double yr = Math.toRadians(yaw);
            double pr = Math.toRadians(pitch);
            Wormhole.LOGGER.info("[wh-cap] camera look=({}, {}, {})",
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

    /**
     * Lengyel's oblique near-plane clipping: rewrites {@code projMatrix}'s third row in place so the
     * near plane coincides with the plane through {@code portalCenter} with normal {@code portalNormal}
     * — geometry on the camera's side of that plane is culled. Returns true if applied. Revived from
     * the validated pre-sphere portal renderer (git 44cc2f2).
     */
    private static boolean applyObliqueNearPlane(Matrix4f projMatrix, Camera camera, Vec3 cameraPos,
                                                 Vec3 portalCenter, Vec3 portalNormal) {
        Matrix4f viewRot = new Matrix4f();
        camera.getViewRotationMatrix(viewRot);
        float nx = (float) portalNormal.x;
        float ny = (float) portalNormal.y;
        float nz = (float) portalNormal.z;
        double cameraDot = nx * (cameraPos.x - portalCenter.x)
            + ny * (cameraPos.y - portalCenter.y)
            + nz * (cameraPos.z - portalCenter.z);
        if (cameraDot > 0.0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float vnx = viewRot.m00() * nx + viewRot.m10() * ny + viewRot.m20() * nz;
        float vny = viewRot.m01() * nx + viewRot.m11() * ny + viewRot.m21() * nz;
        float vnz = viewRot.m02() * nx + viewRot.m12() * ny + viewRot.m22() * nz;
        float vd = nx * (float) (cameraPos.x - portalCenter.x)
            + ny * (float) (cameraPos.y - portalCenter.y)
            + nz * (float) (cameraPos.z - portalCenter.z);
        float qx = (Math.signum(vnx) + projMatrix.m20()) / projMatrix.m00();
        float qy = (Math.signum(vny) + projMatrix.m21()) / projMatrix.m11();
        float qz = -1.0F;
        float qw = (1.0F + projMatrix.m22()) / projMatrix.m32();
        float dotCQ = vnx * qx + vny * qy + vnz * qz + vd * qw;
        if (Math.abs(dotCQ) < 1.0E-4F) {
            return false;
        }
        float scale = 2.0F / dotCQ;
        float newM22 = vnz * scale + 1.0F;
        float newM32 = vd * scale;
        if (Math.abs(newM32) > 50.0F || Math.abs(newM22) > 50.0F
            || Float.isNaN(newM32) || Float.isInfinite(newM32)) {
            return false;
        }
        projMatrix.m02(vnx * scale);
        projMatrix.m12(vny * scale);
        projMatrix.m22(newM22);
        projMatrix.m32(newM32);
        return true;
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
