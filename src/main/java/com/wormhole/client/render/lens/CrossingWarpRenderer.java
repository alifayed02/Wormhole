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
import com.wormhole.client.render.capture.CameraCube;
import com.wormhole.client.render.capture.CubeCapture;
import com.wormhole.portal.PortalEnd;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * Full-screen physically-accurate crossing warp: a screen-filling billboard (centred on the camera
 * forward) running the DNeg geodesic per pixel. Through-rays sample the crossing mouth's PARTNER cube;
 * the output alpha is the proximity {@link CrossingState#intensity()} so it crossfades over the frame
 * — transparent far away, the full lensed destination at the surface. (The around-branch / source cube
 * is added in a later task.)
 */
public final class CrossingWarpRenderer {
    private static int errCount = 0;
    private static GpuBuffer basisBuffer;
    private static GpuBuffer lensParamsBuffer;
    private static GpuBuffer warpParamsBuffer;

    private CrossingWarpRenderer() {
    }

    public static void render() {
        if (!CrossingState.active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        RenderPipeline pipeline = LensRenderPipelines.crossing();
        GpuTextureView lut = DeflectionLut.view();
        if (pipeline == null || lut == null) {
            return;
        }
        PortalEnd mouth = CrossingState.mouth();
        TextureTarget[] faces = CubeCapture.faces(mouth);
        if (faces == null || !CubeCapture.isReady(mouth)) {
            return; // partner cube not captured yet this session
        }
        GpuTextureView aroundLut = AroundDeflectionLut.view();
        TextureTarget[] srcFaces = CameraCube.faces();
        if (aroundLut == null || srcFaces == null || !CameraCube.isReady()) {
            return; // source cube not captured yet this frame
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        Vector3fc fwd = camera.forwardVector();
        Vector3fc up = camera.upVector();
        Vector3fc left = camera.leftVector();
        float fovDeg = camera.getFov();
        float aspect = (float) mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        float halfV = (float) Math.tan(Math.toRadians(fovDeg) * 0.5);
        float depth = 1.0F;
        float sv = depth * halfV * 1.6F; // 60% margin past the frustum edges
        float sh = sv * aspect;

        Vec3 c = mouth.getCenter();
        float rho = (float) mouth.getRadius();
        RenderTarget rt = mc.getMainRenderTarget();
        try {
            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                new Vector3f(), new Matrix4f());
            GpuBufferSlice basis = writeBasis();
            GpuBufferSlice lensParams = writeLensParams(
                (float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z), rho);
            GpuBufferSlice warpParams = writeWarpParams((float) CrossingState.intensity());

            MeshData mesh = buildScreenQuad(fwd, up, left, depth, sh, sv);
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
                GpuTextureView depthView = rt.useDepth ? rt.getDepthTextureView() : null;
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "wormhole_crossing", color, OptionalInt.empty(), depthView, OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dynamicTransforms);
                    pass.setUniform("CubeBasis", basis);
                    pass.setUniform("LensParams", lensParams);
                    pass.setUniform("CrossingParams", warpParams);
                    for (int i = 0; i < 6; i++) {
                        pass.bindTexture("Face" + i, faces[i].getColorTextureView(), sampler);
                        pass.bindTexture("Src" + i, srcFaces[i].getColorTextureView(), sampler);
                    }
                    pass.bindTexture("DeflectionLut", lut, lutSampler);
                    pass.bindTexture("AroundLut", aroundLut, lutSampler);
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
            } finally {
                mesh.close();
                closeBuffers();
            }
        } catch (Exception e) {
            if (errCount++ < 5) {
                Wormhole.LOGGER.error("[lens] crossing warp draw failed", e);
            }
        }
    }

    /** Screen-filling quad centred on the camera forward at {@code depth}; corners give per-pixel rays. */
    private static MeshData buildScreenQuad(Vector3fc fwd, Vector3fc up, Vector3fc left,
                                            float depth, float sh, float sv) {
        float cx = fwd.x() * depth, cy = fwd.y() * depth, cz = fwd.z() * depth;
        float rx = -left.x(), ry = -left.y(), rz = -left.z();
        float ux = up.x(), uy = up.y(), uz = up.z();
        ByteBufferBuilder byteBuf = new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION);
        corner(b, cx, cy, cz, -sh, -sv, rx, ry, rz, ux, uy, uz);
        corner(b, cx, cy, cz, sh, -sv, rx, ry, rz, ux, uy, uz);
        corner(b, cx, cy, cz, sh, sv, rx, ry, rz, ux, uy, uz);
        corner(b, cx, cy, cz, -sh, sv, rx, ry, rz, ux, uy, uz);
        return b.build();
    }

    private static void corner(BufferBuilder b, float cx, float cy, float cz, float a, float d,
                               float rx, float ry, float rz, float ux, float uy, float uz) {
        b.addVertex(cx + a * rx + d * ux, cy + a * ry + d * uy, cz + a * rz + d * uz);
    }

    /** std140: Fwd[6], Right[6], Up[6] (each vec3 padded to vec4) — same basis as the mouth shader. */
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
        basisBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_crossing_basis", 288, buf);
        return basisBuffer.slice();
    }

    private static void putVec(ByteBuffer buf, Vector3fc v) {
        buf.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()).putFloat(0.0F);
    }

    private static GpuBufferSlice writeLensParams(float cx, float cy, float cz, float radius) {
        ByteBuffer buf = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        buf.putFloat(cx).putFloat(cy).putFloat(cz).putFloat(radius);
        buf.flip();
        lensParamsBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_crossing_lens", 16, buf);
        return lensParamsBuffer.slice();
    }

    private static GpuBufferSlice writeWarpParams(float intensity) {
        ByteBuffer buf = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        buf.putFloat(intensity).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
        buf.flip();
        warpParamsBuffer = RenderSystem.getDevice().createBuffer(() -> "wormhole_crossing_warp", 16, buf);
        return warpParamsBuffer.slice();
    }

    private static void closeBuffers() {
        if (basisBuffer != null) {
            basisBuffer.close();
            basisBuffer = null;
        }
        if (lensParamsBuffer != null) {
            lensParamsBuffer.close();
            lensParamsBuffer = null;
        }
        if (warpParamsBuffer != null) {
            warpParamsBuffer.close();
            warpParamsBuffer = null;
        }
    }
}
