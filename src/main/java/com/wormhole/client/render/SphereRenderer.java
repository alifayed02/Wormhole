package com.wormhole.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.wormhole.client.ClientPortalStore;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Draws each linked wormhole mouth as a solid-color 3D sphere. Geometry is camera-relative
 * (matching how the level renderer submits geometry) and drawn opaque + depth-tested with
 * back-face culling off, so the mouth reads correctly from every angle and from inside while
 * passing through. The flat color is a placeholder for the light-bending visuals planned later.
 */
public final class SphereRenderer {
    /** Latitude / longitude tessellation of the sphere. */
    private static final int STACKS = 16;
    private static final int SLICES = 24;
    private static final double RENDER_DISTANCE = 256.0;

    private SphereRenderer() {
    }

    /** Registered on {@code AFTER_TRANSLUCENT_TERRAIN}. */
    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(mc.level.dimension());
        if (pairs.isEmpty()) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        for (PortalPair pair : pairs) {
            int color = colorFor(pair.getId());
            drawSphere(pair.getA(), color, cam);
            drawSphere(pair.getB(), color, cam);
        }
    }

    private static void drawSphere(PortalEnd end, int color, Vec3 cam) {
        Vec3 c = end.getCenter();
        if (c.distanceToSqr(cam) > RENDER_DISTANCE * RENDER_DISTANCE) {
            return;
        }
        MeshData mesh = buildSphere(c.x - cam.x, c.y - cam.y, c.z - cam.z, end.getRadius(), color);
        if (mesh != null) {
            PortalRenderTypes.portalBorder().draw(mesh);
        }
    }

    private static MeshData buildSphere(double cx, double cy, double cz, double r, int color) {
        int quads = STACKS * SLICES;
        ByteBufferBuilder byteBuf =
            new ByteBufferBuilder(quads * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder b = new BufferBuilder(byteBuf, Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < STACKS; i++) {
            double phi0 = Math.PI * i / STACKS;
            double phi1 = Math.PI * (i + 1) / STACKS;
            for (int j = 0; j < SLICES; j++) {
                double theta0 = 2.0 * Math.PI * j / SLICES;
                double theta1 = 2.0 * Math.PI * (j + 1) / SLICES;
                vertex(b, cx, cy, cz, r, phi0, theta0, color);
                vertex(b, cx, cy, cz, r, phi1, theta0, color);
                vertex(b, cx, cy, cz, r, phi1, theta1, color);
                vertex(b, cx, cy, cz, r, phi0, theta1, color);
            }
        }
        return b.build();
    }

    private static void vertex(BufferBuilder b, double cx, double cy, double cz, double r,
                               double phi, double theta, int color) {
        double sinPhi = Math.sin(phi);
        float x = (float) (cx + r * sinPhi * Math.cos(theta));
        float y = (float) (cy + r * Math.cos(phi));
        float z = (float) (cz + r * sinPhi * Math.sin(theta));
        b.addVertex(x, y, z).setColor(color);
    }

    /** Deterministic opaque color from the pair UUID; both mouths of a wormhole share it. */
    private static int colorFor(UUID id) {
        int h = id.hashCode();
        int r = 96 + (h & 0x7F);
        int g = 96 + ((h >> 7) & 0x7F);
        int bl = 96 + ((h >> 14) & 0x7F);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
