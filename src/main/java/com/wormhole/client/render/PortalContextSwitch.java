package com.wormhole.client.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.wormhole.Wormhole;
import com.wormhole.mixin.client.CameraInvokerMixin;
import com.wormhole.mixin.client.GameRendererAccessorMixin;
import com.wormhole.mixin.client.LevelRendererAccessorMixin;
import com.wormhole.mixin.client.MinecraftAccessorMixin;
import com.wormhole.mixin.client.MinecraftRenderTargetMixin;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalTransform;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
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
import org.lwjgl.opengl.GL11;

/**
 * Renders the destination view through a portal by re-running the level renderer from a virtual
 * camera into a secondary FBO, then compositing it into the masked region. Same-dimension version:
 * the destination level is the current {@code mc.level}, rendered by a dedicated
 * {@link PortalRenderer}.
 */
public final class PortalContextSwitch {
    /** Guards against recursively rendering portals while a portal view is being rendered. */
    public static volatile boolean isRenderingPortal = false;

    /** Oblique near-plane clip (M3): tilts the virtual camera's near plane onto the destination
     *  portal plane so only geometry BEYOND the destination renders. Without it you see the
     *  destination's near-side surroundings (which look like the area you're standing in / "the
     *  portal itself") instead of what is on the far side. */
    private static final boolean ENABLE_OBLIQUE_CLIP = true;

    /** Lengyel's oblique near-plane degenerates to a sliver as the camera approaches the plane.
     *  Within this perpendicular distance we skip the clip (the opening fills the view in the final
     *  approach anyway, so the minor near-side bleed is not visible). */
    private static final double MIN_CLIP_PERP = 0.5;

    private static TextureTarget secondaryFbo;
    private static GpuBuffer projGpuBuffer;
    private static GpuBuffer fogGpuBuffer;
    private static int errorLogCount = 0;

    private PortalContextSwitch() {
    }

    /**
     * Renders {@code destEnd}'s surroundings as seen through {@code srcEnd}. Returns true if the
     * destination world was drawn+composited; false if the caller should draw a fallback (e.g. the
     * dest chunks are not compiled yet).
     */
    public static boolean renderDestinationWorld(PortalEnd srcEnd, PortalEnd destEnd, Camera mainCamera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        LevelRenderer destRenderer = PortalRenderer.getOrCreate(mc, destEnd);
        if (destRenderer == null) {
            return false;
        }
        try {
            return doFboRender(srcEnd, destEnd, mainCamera, destRenderer, mc.level, mc);
        } catch (Exception e) {
            if (errorLogCount++ < 5) {
                Wormhole.LOGGER.error("[render] portal FBO render failed", e);
            }
            return false;
        }
    }

    private static boolean doFboRender(PortalEnd srcEnd, PortalEnd destEnd, Camera mainCamera,
                                       LevelRenderer destRenderer, ClientLevel destLevel, Minecraft mc) {
        DeltaTracker deltaTracker = mc.getDeltaTracker();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        // Virtual camera placed at the destination, mirroring the player's offset from the source.
        Vec3 destCamPos = PortalTransform.transformPoint(srcEnd, destEnd, mainCamera.position());
        float yawOffset = PortalTransform.yawDelta(srcEnd, destEnd);
        Camera virtualCamera = new Camera();
        virtualCamera.setLevel(destLevel);
        virtualCamera.setEntity(mc.player);
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setRotation(mainCamera.yRot() + yawOffset, mainCamera.xRot());
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setPosition(destCamPos);
        virtualCamera.tick();

        CameraRenderState mainCameraState =
            mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        Matrix4f viewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(viewMatrix);
        Matrix4f projMatrix = new Matrix4f(mainCameraState.projectionMatrix);
        Frustum destFrustum = new Frustum(viewMatrix, projMatrix);
        destFrustum.prepare(destCamPos.x, destCamPos.y, destCamPos.z);
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setCullFrustum(destFrustum);
        ((CameraInvokerMixin) (Object) virtualCamera).wormhole$setInitialized(true);

        // Re-cull the dedicated renderer's sections around the virtual camera and compile dirty ones.
        LevelRendererAccessorMixin destAccess = (LevelRendererAccessorMixin) destRenderer;
        LevelRenderState destLRS = destAccess.wormhole$getLevelRenderState();
        ViewArea viewArea = destAccess.wormhole$getViewArea();
        ObjectArrayList<RenderSection> visibleSections = destAccess.wormhole$getVisibleSections();
        if (viewArea != null) {
            SectionPos camSec = SectionPos.of(destCamPos);
            viewArea.repositionCamera(camSec);
            SectionRenderDispatcher dispatcher = destRenderer.getSectionRenderDispatcher();
            if (dispatcher != null) {
                dispatcher.setCameraPosition(destCamPos);
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
                if (destLevel.getChunkSource().hasChunk(sx, sz) && destFrustum.isVisible(section.getBoundingBox())) {
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

        destRenderer.extractLevel(deltaTracker, virtualCamera, partialTick);
        ChunkSectionsToRender destChunks = destLRS.chunkSectionsToRender;
        if (destChunks == null || destChunks.maxIndicesRequired() == 0) {
            return false; // not compiled yet -> caller draws the fallback
        }

        prepareSecondaryFbo();
        CameraRenderState destCameraState = destLRS.cameraRenderState;
        virtualCamera.extractRenderState(destCameraState, partialTick);
        destCameraState.projectionMatrix.set(mainCameraState.projectionMatrix);
        // Oblique near-plane clip: tilt the near plane onto the destination portal plane so geometry
        // between the virtual camera and the portal doesn't bleed into the view.
        if (ENABLE_OBLIQUE_CLIP) {
            Vec3 dn = destEnd.getNormal();
            Vec3 dc = destEnd.getCenter();
            double perp = Math.abs(dn.x * (destCamPos.x - dc.x)
                + dn.y * (destCamPos.y - dc.y)
                + dn.z * (destCamPos.z - dc.z));
            if (perp > MIN_CLIP_PERP) {
                applyObliqueNearPlane(destCameraState.projectionMatrix, virtualCamera, destCamPos, dc, dn);
            }
        }
        if (destCameraState.entityRenderState != null) {
            destCameraState.entityRenderState.bob = 0.0F;
            destCameraState.entityRenderState.backwardsInterpolatedWalkDistance = 0.0F;
            destCameraState.entityRenderState.hurtTime = -1.0F;
            destCameraState.entityRenderState.hurtDuration = 1;
            destCameraState.entityRenderState.isDeadOrDying = false;
        }

        FogRenderer fogRenderer = ((GameRendererAccessorMixin) mc.gameRenderer).wormhole$getFogRenderer();
        FogData destFogData = fogRenderer.setupFog(virtualCamera, mc.options.getEffectiveRenderDistance(),
            deltaTracker, 0.0F, destLevel);
        FogData savedFogData = destCameraState.fogData;
        FogType savedFogType = destCameraState.fogType;
        destCameraState.fogData = destFogData;
        destCameraState.fogType = FogType.NONE;
        GpuBufferSlice destFogBuffer = writePortalFogBuffer(destFogData);

        Matrix4f destViewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(destViewMatrix);
        Vec3 savedCamPos = mainCamera.position();
        long savedGameTime = mc.level.getGameTime();
        ChunkSectionsToRender chunksToRender = destChunks;

        isRenderingPortal = true;
        try {
            withSwitchedWorld(destRenderer, secondaryFbo, virtualCamera, () -> {
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                Matrix4fStack mvStack = RenderSystem.getModelViewStack();
                mvStack.pushMatrix();
                mvStack.identity();
                try {
                    RenderSystem.backupProjectionMatrix();
                    RenderSystem.setProjectionMatrix(writeProjectionBuffer(destCameraState.projectionMatrix), ProjectionType.PERSPECTIVE);
                    GameRenderState grs = mc.gameRenderer.getGameRenderState();
                    try {
                        mc.gameRenderer.getGlobalSettingsUniform().update(
                            mc.getMainRenderTarget().width, mc.getMainRenderTarget().height,
                            grs.optionsRenderState.glintStrength, destLevel.getGameTime(), deltaTracker,
                            grs.optionsRenderState.menuBackgroundBlurriness, destCamPos, false);
                        destRenderer.update(virtualCamera);
                        destRenderer.renderLevel(
                            GraphicsResourceAllocator.UNPOOLED, deltaTracker, false, destCameraState,
                            destViewMatrix, destFogBuffer, destFogData.color, true, chunksToRender);
                    } finally {
                        mc.gameRenderer.getGlobalSettingsUniform().update(
                            mc.getMainRenderTarget().width, mc.getMainRenderTarget().height,
                            grs.optionsRenderState.glintStrength, savedGameTime, deltaTracker,
                            grs.optionsRenderState.menuBackgroundBlurriness, savedCamPos, false);
                        RenderSystem.restoreProjectionMatrix();
                    }
                } finally {
                    mvStack.popMatrix();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
                    GL11.glStencilMask(0);
                }
            });
        } finally {
            isRenderingPortal = false;
            destCameraState.fogData = savedFogData;
            destCameraState.fogType = savedFogType;
        }

        compositePortalFbo();
        // Free this frame's transient GPU buffers (otherwise they leak and FPS degrades over time).
        freeTransientBuffers();
        return true;
    }

    private static void withSwitchedWorld(LevelRenderer destRenderer, RenderTarget destMainRT,
                                          Camera destCamera, Runnable renderCallback) {
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
            ((MinecraftRenderTargetMixin) (Object) mc).wormhole$setMainRenderTarget(destMainRT);
            mcAccess.wormhole$setLevelRenderer(destRenderer);
            gameRendererAccess.wormhole$setMainCamera(destCamera);
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
     * Modifies {@code projMatrix} in place so its near plane coincides with the destination portal
     * plane (Lengyel's oblique near-plane clipping). Returns true if applied. View-space math: the
     * clip plane normal is flipped to point away from the camera, transformed into view space, and
     * the projection's third row is rewritten so the standard near-plane w-clip culls everything in
     * front of the portal.
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
        float newM02 = vnx * scale;
        float newM12 = vny * scale;
        float newM22 = vnz * scale + 1.0F;
        float newM32 = vd * scale;
        if (Math.abs(newM32) > 50.0F || Math.abs(newM22) > 50.0F
            || Float.isNaN(newM32) || Float.isInfinite(newM32)) {
            return false;
        }

        projMatrix.m02(newM02);
        projMatrix.m12(newM12);
        projMatrix.m22(newM22);
        projMatrix.m32(newM32);
        return true;
    }

    private static void prepareSecondaryFbo() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (secondaryFbo == null) {
            secondaryFbo = new TextureTarget("wormhole_portal", w, h, true);
        } else if (secondaryFbo.width != w || secondaryFbo.height != h) {
            secondaryFbo.resize(w, h);
        }
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
        projGpuBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_portal_proj", 128, buf);
        return projGpuBuffer.slice();
    }

    private static GpuBufferSlice writePortalFogBuffer(FogData fog) {
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
        fogGpuBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_portal_fog", 128, buf);
        return fogGpuBuffer.slice();
    }

    private static void compositePortalFbo() {
        if (secondaryFbo == null || secondaryFbo.getColorTextureView() == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainRT = mc.getMainRenderTarget();
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilMask(0);
        GL11.glDisable(GL11.GL_BLEND);

        RenderPass pass = RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(() -> "wormhole_portal_composite", mainRT.getColorTextureView(),
                OptionalInt.empty(), mainRT.getDepthTextureView(), OptionalDouble.empty());
        try {
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", secondaryFbo.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        } finally {
            pass.close();
        }

        GL11.glEnable(GL11.GL_BLEND);
    }
}
