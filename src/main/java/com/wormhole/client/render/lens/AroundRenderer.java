package com.wormhole.client.render.lens;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
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
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Around-the-mouth gravitational lensing: a camera-facing billboard over each mouth's sphere of
 * influence that warps the mouth's OWN surroundings ({@link DestinationCapture#own}) by the paper's
 * geodesic deflection ({@link AroundDeflectionLut}). Because the surroundings are rendered from the
 * fixed mouth position, the bend is world-anchored (doesn't swim) and includes the sky. Fragments inside
 * the silhouette (the window, drawn by {@link LensSphereRenderer}) or beyond the influence radius
 * discard, and the alpha fades to zero at the edge — so it dissolves into the scene with no hard cutoff.
 */
public final class AroundRenderer {
    /** Radius of the sphere of influence, in mouth-radius (rho) units. Must match B_MAX in the shader. */
    private static final double INFLUENCE_RHO = 2.6;

    private static int errCount = 0;
    private static GpuBuffer lensParamsBuffer;

    private AroundRenderer() {
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
        RenderPipeline pipeline = LensRenderPipelines.around();
        GpuTextureView lut = AroundDeflectionLut.view();
        GpuTextureView scene = SceneCopy.view();
        if (pipeline == null || lut == null || scene == null) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        Vector3fc up = camera.upVector();
        Vector3fc left = camera.leftVector();
        RenderTarget rt = mc.getMainRenderTarget();
        for (PortalPair pair : pairs) {
            drawAround(pipeline, rt, pair.getA(), cam, up, left, lut, scene);
            drawAround(pipeline, rt, pair.getB(), cam, up, left, lut, scene);
        }
    }

    private static void drawAround(RenderPipeline pipeline, RenderTarget rt, PortalEnd end, Vec3 cam,
                                   Vector3fc up, Vector3fc left, GpuTextureView lut, GpuTextureView scene) {
        Vec3 c = end.getCenter();
        float r = (float) end.getRadius();
        // Size the billboard to the influence CONE (angular), so it always contains the circular effect
        // region — even up close, where a fixed world-size quad would be too small and show its corners.
        double dx = c.x - cam.x;
        double dy = c.y - cam.y;
        double dz = c.z - cam.z;
        double dist = Math.max(Math.sqrt(dx * dx + dy * dy + dz * dz), 1.0e-3);
        double alphaCrit = Math.asin(Math.min(1.0, r / dist));
        double cone = Math.min(INFLUENCE_RHO * alphaCrit, 1.45); // clamp ~83° to avoid tan() blow-up
        float s = (float) (dist * Math.tan(cone) * 1.3);         // +30% margin past the effect circle
        try {
            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                new Vector3f(), new Matrix4f());
            GpuBufferSlice lensParams = writeLensParams(
                (float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z), r);

            MeshData mesh = buildBillboard((float) (c.x - cam.x), (float) (c.y - cam.y),
                (float) (c.z - cam.z), s, up, left);
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
                GpuTextureView color = rt.getColorTextureView();
                GpuTextureView depth = rt.useDepth ? rt.getDepthTextureView() : null;
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "wormhole_lens_around", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dynamicTransforms);
                    pass.setUniform("LensParams", lensParams);
                    pass.bindTexture("SceneColor", scene, sampler);
                    pass.bindTexture("DeflectionLut", lut, sampler);
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
            } finally {
                mesh.close();
                if (lensParamsBuffer != null) {
                    lensParamsBuffer.close();
                    lensParamsBuffer = null;
                }
            }
        } catch (Exception e) {
            if (errCount++ < 5) {
                Wormhole.LOGGER.error("[lens] around draw failed", e);
            }
        }
    }

    private static MeshData buildBillboard(float cx, float cy, float cz, float s,
                                           Vector3fc up, Vector3fc left) {
        float rx = -left.x();
        float ry = -left.y();
        float rz = -left.z();
        float ux = up.x();
        float uy = up.y();
        float uz = up.z();
        ByteBufferBuilder byteBuf = new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION);
        corner(b, cx, cy, cz, -s, -s, rx, ry, rz, ux, uy, uz);
        corner(b, cx, cy, cz, s, -s, rx, ry, rz, ux, uy, uz);
        corner(b, cx, cy, cz, s, s, rx, ry, rz, ux, uy, uz);
        corner(b, cx, cy, cz, -s, s, rx, ry, rz, ux, uy, uz);
        return b.build();
    }

    private static void corner(BufferBuilder b, float cx, float cy, float cz, float a, float d,
                               float rx, float ry, float rz, float ux, float uy, float uz) {
        b.addVertex(cx + a * rx + d * ux, cy + a * ry + d * uy, cz + a * rz + d * uz);
    }

    private static GpuBufferSlice writeLensParams(float cx, float cy, float cz, float radius) {
        ByteBuffer buf = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        buf.putFloat(cx).putFloat(cy).putFloat(cz).putFloat(radius);
        buf.flip();
        lensParamsBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_around_params", 16, buf);
        return lensParamsBuffer.slice();
    }
}
