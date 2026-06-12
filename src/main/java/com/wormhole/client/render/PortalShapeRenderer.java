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

    /** One quad spanning the full interior rectangle, in the end's center plane. */
    private static MeshData buildRect(PortalEnd end, Camera camera, int color) {
        Vec3 cam = camera.position();
        BlockPos o = end.getOrigin();
        double y0 = o.getY();
        double y1 = o.getY() + end.getHeight();
        Vec3 p00;
        Vec3 p01;
        Vec3 p11;
        Vec3 p10;
        if (end.getAxis() == Direction.Axis.X) {
            double plane = end.getCenter().z;
            double x0 = o.getX();
            double x1 = o.getX() + end.getWidth();
            p00 = new Vec3(x0, y0, plane);
            p01 = new Vec3(x0, y1, plane);
            p11 = new Vec3(x1, y1, plane);
            p10 = new Vec3(x1, y0, plane);
        } else {
            double plane = end.getCenter().x;
            double z0 = o.getZ();
            double z1 = o.getZ() + end.getWidth();
            p00 = new Vec3(plane, y0, z0);
            p01 = new Vec3(plane, y1, z0);
            p11 = new Vec3(plane, y1, z1);
            p10 = new Vec3(plane, y0, z1);
        }

        ByteBufferBuilder byteBuf =
            new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addVertex(b, p00, cam, color);
        addVertex(b, p01, cam, color);
        addVertex(b, p11, cam, color);
        addVertex(b, p10, cam, color);
        return b.build();
    }

    private static void addVertex(BufferBuilder b, Vec3 p, Vec3 cam, int color) {
        b.addVertex((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(color);
    }
}
