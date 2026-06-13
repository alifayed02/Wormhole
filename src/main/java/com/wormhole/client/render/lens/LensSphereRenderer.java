package com.wormhole.client.render.lens;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.wormhole.Wormhole;
import com.wormhole.client.ClientPortalStore;
import com.wormhole.client.ClientPortalTeleport;
import com.wormhole.client.render.capture.CubeCapture;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Draws each wormhole mouth as a sphere whose through-image is a TRUE 1:1 geodesic lensing of the other
 * side: per fragment the real DNeg geodesic ({@link DeflectionLut}, baked from {@code DnegLensModel}) is
 * evaluated from the exact inputs (camera distance + incidence angle) to get the emergent direction, and
 * the partner's FULL live cubemap ({@link CubeCapture}) is sampled in that direction — any direction is
 * valid, so there's no FOV limit or edge clamping. The cubemaps are re-captured every frame (live).
 */
public final class LensSphereRenderer {
    private static final int STACKS = 24;
    private static final int SLICES = 36;
    private static int errCount = 0;

    private static GpuBuffer basisBuffer;
    private static GpuBuffer lensParamsBuffer;

    private LensSphereRenderer() {
    }

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(mc.level.dimension());
        if (pairs.isEmpty()) {
            return;
        }
        RenderPipeline pipeline = LensRenderPipelines.sphere();
        if (pipeline == null) {
            return;
        }
        GpuTextureView lut = DeflectionLut.view();
        if (lut == null) {
            return;
        }
        Set<PortalEnd> active = new HashSet<>();
        for (PortalPair pair : pairs) {
            active.add(pair.getA());
            active.add(pair.getB());
        }
        CubeCapture.retainOnly(active);

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        RenderTarget rt = mc.getMainRenderTarget();
        var dim = mc.level.dimension();
        for (PortalPair pair : pairs) {
            // Draw only the end(s) in the current dimension: a same-dimension pair has both here, a
            // cross-dimensional pair has exactly one (the partner lives in another world).
            if (pair.getA().getDimension().equals(dim)) {
                drawMouth(pipeline, rt, pair, pair.getA(), cam, lut);
            }
            if (pair.getB().getDimension().equals(dim)) {
                drawMouth(pipeline, rt, pair, pair.getB(), cam, lut);
            }
        }
    }

    /** Capture one local mouth's through-view and draw it (unless the camera is inside it). */
    private static void drawMouth(RenderPipeline pipeline, RenderTarget rt, PortalPair pair,
                                  PortalEnd end, Vec3 cam, GpuTextureView lut) {
        // Live, parallax-correct: capture the mouth's through-view (the PARTNER's surroundings) from
        // the CAMERA's image on the partner side — transformTeleportPosition(end, cam) — so the
        // geodesic sample tracks the eye and the crossing is seamless. Keyed by the mouth you look
        // through; each mouth samples its OWN cube, so readiness is per-mouth.
        CubeCapture.capture(end, pair.transformTeleportPosition(end, cam));
        if (!CubeCapture.isReady(end)) {
            return; // chunks still compiling — retry next frame
        }
        // While crossing (suppressed), the camera is inside the destination bubble walking out;
        // drawing that mouth's lens sphere would show it inside-out. Skip just that one.
        boolean suppressed = ClientPortalTeleport.isSuppressed();
        if (!(suppressed && end.containsPoint(cam))) {
            drawSphere(pipeline, rt, end, cam, lut);
        }
    }

    private static void drawSphere(RenderPipeline pipeline, RenderTarget rt, PortalEnd end,
                                   Vec3 cam, GpuTextureView lut) {
        TextureTarget[] faces = CubeCapture.faces(end);
        if (faces == null) {
            return;
        }
        Vec3 c = end.getCenter();
        float r = (float) end.getRadius();
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        try {
            mv.translate((float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z));
            mv.scale(r, r, r);
            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                new Vector3f(), new Matrix4f());
            GpuBufferSlice basis = writeBasis();
            GpuBufferSlice lensParams = writeLensParams(
                (float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z), r);

            MeshData mesh = buildUnitSphere();
            if (mesh == null) {
                return;
            }
            try {
                GpuBuffer vertices = pipeline.getVertexFormat().uploadImmediateVertexBuffer(mesh.vertexBuffer());
                RenderSystem.AutoStorageIndexBuffer autoIndices =
                    RenderSystem.getSequentialBuffer(mesh.drawState().mode());
                GpuBuffer indices = autoIndices.getBuffer(mesh.drawState().indexCount());
                VertexFormat.IndexType indexType = autoIndices.type();

                GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                GpuSampler lutSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                GpuTextureView color = rt.getColorTextureView();
                GpuTextureView depth = rt.useDepth ? rt.getDepthTextureView() : null;
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "wormhole_lens_sphere", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dynamicTransforms);
                    pass.setUniform("CubeBasis", basis);
                    pass.setUniform("LensParams", lensParams);
                    for (int i = 0; i < 6; i++) {
                        pass.bindTexture("Face" + i, faces[i].getColorTextureView(), sampler);
                    }
                    pass.bindTexture("DeflectionLut", lut, lutSampler);
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
            } finally {
                mesh.close();
                if (basisBuffer != null) {
                    basisBuffer.close();
                    basisBuffer = null;
                }
                if (lensParamsBuffer != null) {
                    lensParamsBuffer.close();
                    lensParamsBuffer = null;
                }
            }
        } catch (Exception e) {
            if (errCount++ < 5) {
                Wormhole.LOGGER.error("[lens] sphere draw failed", e);
            }
        } finally {
            mv.popMatrix();
        }
    }

    /** std140 block: Fwd[6], Right[6], Up[6] (each vec3 padded to vec4). */
    private static GpuBufferSlice writeBasis() {
        ByteBuffer buf = ByteBuffer.allocateDirect(288).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 6; i++) {
            putVec(buf, CubeCapture.forward(i));
        }
        for (int i = 0; i < 6; i++) {
            putVec(buf, CubeCapture.right(i));
        }
        for (int i = 0; i < 6; i++) {
            putVec(buf, CubeCapture.up(i));
        }
        buf.flip();
        basisBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_cube_basis", 288, buf);
        return basisBuffer.slice();
    }

    private static void putVec(ByteBuffer buf, Vector3fc v) {
        buf.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()).putFloat(0.0F);
    }

    /** std140 block: vec4 Center (xyz = camera-relative centre, w = radius). */
    private static GpuBufferSlice writeLensParams(float cx, float cy, float cz, float radius) {
        ByteBuffer buf = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        buf.putFloat(cx).putFloat(cy).putFloat(cz).putFloat(radius);
        buf.flip();
        lensParamsBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_lens_params", 16, buf);
        return lensParamsBuffer.slice();
    }

    /** A unit sphere as POSITION quads; vertex positions are unit outward directions. */
    private static MeshData buildUnitSphere() {
        int quads = STACKS * SLICES;
        ByteBufferBuilder byteBuf = new ByteBufferBuilder(quads * 4 * DefaultVertexFormat.POSITION.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int i = 0; i < STACKS; i++) {
            double phi0 = Math.PI * i / STACKS;
            double phi1 = Math.PI * (i + 1) / STACKS;
            for (int j = 0; j < SLICES; j++) {
                double t0 = 2.0 * Math.PI * j / SLICES;
                double t1 = 2.0 * Math.PI * (j + 1) / SLICES;
                vertex(b, phi0, t0);
                vertex(b, phi1, t0);
                vertex(b, phi1, t1);
                vertex(b, phi0, t1);
            }
        }
        return b.build();
    }

    private static void vertex(BufferBuilder b, double phi, double theta) {
        double sinPhi = Math.sin(phi);
        b.addVertex((float) (sinPhi * Math.cos(theta)), (float) Math.cos(phi), (float) (sinPhi * Math.sin(theta)));
    }
}
