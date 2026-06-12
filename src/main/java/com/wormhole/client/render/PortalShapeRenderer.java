package com.wormhole.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.wormhole.portal.PortalEnd;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/**
 * Builds and draws the geometry for a single {@link PortalEnd} as a <em>spherical wormhole mouth</em>.
 *
 * <p>The opening is a 3D UV sphere centered on the end, radius {@link PortalEnd#getRadius()}. A
 * sphere's silhouette is a circle from every viewing angle, so the stencil mask reads as a clean
 * round portal; rendering it as a real mesh (rather than a camera-facing disc) gives the mouth volume
 * and correct depth, so terrain in front of it occludes it and the transition stays sane as you walk
 * into it. All geometry is camera-relative, matching how the level renderer submits geometry.
 */
public final class PortalShapeRenderer {
    /** Stencil-pass color is irrelevant (color writes are masked off); just needs alpha set. */
    private static final int STENCIL_COLOR = 0x01000000;

    /** UV-sphere tessellation. Latitude bands x longitude segments; each cell is one quad. */
    private static final int STACKS = 16;
    private static final int SLICES = 32;

    /** Camera-facing ring (border) segment count. */
    private static final int RING_SEGMENTS = 64;

    private PortalShapeRenderer() {
    }

    /** Draws the exact mouth silhouette; used to mark/reset the stencil region (no depth). */
    public static void drawStencilQuad(PortalEnd end, Camera camera) {
        MeshData mesh = buildSphere(end, camera, STENCIL_COLOR, 1.0);
        if (mesh != null) {
            PortalRenderTypes.portalStencilOnly().draw(mesh);
        }
    }

    /** Draws the mouth with a depth test, writing stencil where the sphere is visible. */
    public static void drawStencilDepthTested(PortalEnd end, Camera camera) {
        MeshData mesh = buildSphere(end, camera, STENCIL_COLOR, 1.0);
        if (mesh != null) {
            PortalRenderTypes.portalStencilWithDepth().draw(mesh);
        }
    }

    /** Pushes the depth of the masked region so the destination view can fill it. */
    public static void drawDepthClear(PortalEnd end, Camera camera) {
        MeshData mesh = buildSphere(end, camera, STENCIL_COLOR, 1.0);
        if (mesh != null) {
            PortalRenderTypes.portalDepthClear().draw(mesh);
        }
    }

    /** Fallback flat color, shown only while the destination chunks are still compiling. */
    public static void drawColorFill(PortalEnd end, Camera camera, int argb) {
        MeshData mesh = buildSphere(end, camera, argb, 1.0);
        if (mesh != null) {
            PortalRenderTypes.portalNoDepthColor().draw(mesh);
        }
    }

    /** Draws a glowing, depth-tested ring framing the mouth, always facing the camera. */
    public static void drawBorder(PortalEnd end, Camera camera, int argb) {
        Vec3 center = end.getCenter();
        double r = end.getRadius();
        Vec3 cam = camera.position();

        // Camera-facing basis: the ring lies in the plane perpendicular to the view direction.
        Vec3 n = cam.subtract(center);
        if (n.lengthSqr() < 1.0e-6) {
            return;
        }
        n = n.normalize();
        Vec3 up0 = Math.abs(n.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = up0.cross(n).normalize();
        Vec3 up = n.cross(right).normalize();
        Vec3 push = n.scale(0.02); // nudge toward camera so the ring frames the silhouette edge

        double inner = r * 0.99;
        double outer = r * 1.12;

        ByteBufferBuilder byteBuf =
            new ByteBufferBuilder(RING_SEGMENTS * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int j = 0; j < RING_SEGMENTS; j++) {
            double t0 = 2.0 * Math.PI * j / RING_SEGMENTS;
            double t1 = 2.0 * Math.PI * (j + 1) / RING_SEGMENTS;
            Vec3 in0 = ringPoint(center, right, up, inner, t0).add(push);
            Vec3 out0 = ringPoint(center, right, up, outer, t0).add(push);
            Vec3 out1 = ringPoint(center, right, up, outer, t1).add(push);
            Vec3 in1 = ringPoint(center, right, up, inner, t1).add(push);
            addVertex(b, in0, cam, argb);
            addVertex(b, out0, cam, argb);
            addVertex(b, out1, cam, argb);
            addVertex(b, in1, cam, argb);
        }
        MeshData mesh = b.build();
        if (mesh != null) {
            PortalRenderTypes.portalBorder().draw(mesh);
        }
    }

    private static Vec3 ringPoint(Vec3 center, Vec3 right, Vec3 up, double radius, double theta) {
        return center
            .add(right.scale(Math.cos(theta) * radius))
            .add(up.scale(Math.sin(theta) * radius));
    }

    /** A UV sphere centered on the end, tessellated into a quad grid. */
    private static MeshData buildSphere(PortalEnd end, Camera camera, int color, double radiusScale) {
        Vec3 center = end.getCenter();
        double r = end.getRadius() * radiusScale;
        Vec3 cam = camera.position();

        int quads = STACKS * SLICES;
        ByteBufferBuilder byteBuf =
            new ByteBufferBuilder(quads * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < STACKS; i++) {
            for (int j = 0; j < SLICES; j++) {
                Vec3 p00 = spherePoint(center, r, i, j);
                Vec3 p10 = spherePoint(center, r, i + 1, j);
                Vec3 p11 = spherePoint(center, r, i + 1, j + 1);
                Vec3 p01 = spherePoint(center, r, i, j + 1);
                addVertex(b, p00, cam, color);
                addVertex(b, p10, cam, color);
                addVertex(b, p11, cam, color);
                addVertex(b, p01, cam, color);
            }
        }
        return b.build();
    }

    private static Vec3 spherePoint(Vec3 center, double r, int i, int j) {
        double phi = Math.PI * i / STACKS;          // 0 (top) .. PI (bottom)
        double theta = 2.0 * Math.PI * j / SLICES;  // 0 .. 2PI around
        double sinPhi = Math.sin(phi);
        return new Vec3(
            center.x + r * sinPhi * Math.cos(theta),
            center.y + r * Math.cos(phi),
            center.z + r * sinPhi * Math.sin(theta));
    }

    private static void addVertex(BufferBuilder b, Vec3 p, Vec3 cam, int color) {
        b.addVertex((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(color);
    }
}
