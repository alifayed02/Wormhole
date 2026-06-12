package com.wormhole.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.wormhole.portal.PortalEnd;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Builds and draws the geometry for a single {@link PortalEnd}: a flat rectangle filling the
 * frame's entire interior opening, lying in the end's center plane. The portal pipelines render
 * with culling disabled, so the one quad reads correctly from both sides. All geometry is
 * camera-relative, matching how the level renderer submits geometry.
 */
public final class PortalShapeRenderer {
    /** Stencil-pass color is irrelevant (color writes are masked off); just needs alpha set. */
    private static final int STENCIL_COLOR = 0x01000000;

    private PortalShapeRenderer() {
    }

    /** Draws the exact opening silhouette; used to mark/reset the stencil region (no depth). */
    public static void drawStencilQuad(PortalEnd end, Camera camera) {
        MeshData mesh = buildRect(end, camera, STENCIL_COLOR);
        if (mesh != null) {
            PortalRenderTypes.portalStencilOnly().draw(mesh);
        }
    }

    /** Draws the opening with a depth test, writing stencil where it is visible. */
    public static void drawStencilDepthTested(PortalEnd end, Camera camera) {
        MeshData mesh = buildRect(end, camera, STENCIL_COLOR);
        if (mesh != null) {
            PortalRenderTypes.portalStencilWithDepth().draw(mesh);
        }
    }

    /** Pushes the depth of the masked region so the destination view can fill it. */
    public static void drawDepthClear(PortalEnd end, Camera camera) {
        MeshData mesh = buildRect(end, camera, STENCIL_COLOR);
        if (mesh != null) {
            PortalRenderTypes.portalDepthClear().draw(mesh);
        }
    }

    /** Fallback flat color, shown only while the destination chunks are still compiling. */
    public static void drawColorFill(PortalEnd end, Camera camera, int argb) {
        MeshData mesh = buildRect(end, camera, argb);
        if (mesh != null) {
            PortalRenderTypes.portalNoDepthColor().draw(mesh);
        }
    }

    /**
     * Thickness given to the portal surface when the camera is close, so the surface always
     * encloses the camera's near plane while passing through — the stencil mask can never be
     * partially cut at the threshold (port of Lague's ProtectScreenFromClipping; MC's near plane
     * is 0.05, so 0.25 covers the near-plane corner at any sane FOV).
     */
    private static final double NEAR_THICKNESS = 0.25;
    /** Camera-to-plane distance under which the surface is extruded into a box. */
    private static final double THICKEN_RANGE = 1.0;

    /**
     * The portal surface: one quad spanning the full interior rectangle in the end's center
     * plane — extruded into a box (extending AWAY from the camera's side) when the camera is
     * within {@link #THICKEN_RANGE} of the plane.
     */
    private static MeshData buildRect(PortalEnd end, Camera camera, int color) {
        Vec3 cam = camera.position();
        BlockPos o = end.getOrigin();
        double y0 = o.getY();
        double y1 = o.getY() + end.getHeight();
        double perp;
        Vec3 p00;
        Vec3 p01;
        Vec3 p11;
        Vec3 p10;
        if (end.getAxis() == Direction.Axis.X) {
            double plane = end.getCenter().z;
            double x0 = o.getX();
            double x1 = o.getX() + end.getWidth();
            perp = cam.z - plane;
            p00 = new Vec3(x0, y0, plane);
            p01 = new Vec3(x0, y1, plane);
            p11 = new Vec3(x1, y1, plane);
            p10 = new Vec3(x1, y0, plane);
        } else {
            double plane = end.getCenter().x;
            double z0 = o.getZ();
            double z1 = o.getZ() + end.getWidth();
            perp = cam.x - plane;
            p00 = new Vec3(plane, y0, z0);
            p01 = new Vec3(plane, y1, z0);
            p11 = new Vec3(plane, y1, z1);
            p10 = new Vec3(plane, y0, z1);
        }

        boolean box = Math.abs(perp) <= THICKEN_RANGE;
        int quads = box ? 6 : 1;
        ByteBufferBuilder byteBuf =
            new ByteBufferBuilder(quads * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addQuad(b, p00, p01, p11, p10, cam, color);
        if (box) {
            Vec3 off = end.getNormal().scale(perp >= 0.0 ? -NEAR_THICKNESS : NEAR_THICKNESS);
            Vec3 q00 = p00.add(off);
            Vec3 q01 = p01.add(off);
            Vec3 q11 = p11.add(off);
            Vec3 q10 = p10.add(off);
            addQuad(b, q00, q01, q11, q10, cam, color); // back face
            addQuad(b, p00, p01, q01, q00, cam, color); // sides
            addQuad(b, p01, p11, q11, q01, cam, color);
            addQuad(b, p11, p10, q10, q11, cam, color);
            addQuad(b, p10, p00, q00, q10, cam, color);
        }
        return b.build();
    }

    private static void addQuad(BufferBuilder b, Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3, Vec3 cam, int color) {
        addVertex(b, v0, cam, color);
        addVertex(b, v1, cam, color);
        addVertex(b, v2, cam, color);
        addVertex(b, v3, cam, color);
    }

    private static void addVertex(BufferBuilder b, Vec3 p, Vec3 cam, int color) {
        b.addVertex((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(color);
    }
}
