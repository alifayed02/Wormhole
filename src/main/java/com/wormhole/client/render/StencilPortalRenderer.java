package com.wormhole.client.render;

import com.wormhole.client.ClientPortalStore;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Orchestrates the portal stencil passes. Registered on {@code AFTER_TRANSLUCENT_TERRAIN}.
 *
 * <p>For each portal end: mask the opening into the stencil buffer, then render the destination
 * world (from a virtual camera) into the masked region. While the dedicated renderer's chunks are
 * still compiling, a flat color is shown instead so the opening is never blank.
 */
public final class StencilPortalRenderer {
    private static final double RENDER_DISTANCE = 128.0;
    private static final int FALLBACK_COLOR_A = 0xFFFF00FF; // magenta
    private static final int FALLBACK_COLOR_B = 0xFF00FFFF; // cyan
    private static final int BORDER_COLOR = 0xFF33CCFF; // light blue frame

    private StencilPortalRenderer() {
    }

    public static void render() {
        // Guard against recursion: the nested renderLevel re-fires AFTER_TRANSLUCENT_TERRAIN.
        if (PortalContextSwitch.isRenderingPortal) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(mc.level.dimension());
        if (pairs.isEmpty()) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        Vec3 look = mc.player.getViewVector(1.0F);
        if (com.wormhole.client.WormholeDebug.ENABLED) {
            double best = Double.MAX_VALUE;
            for (PortalPair p : pairs) {
                best = Math.min(best, Math.min(p.getA().getCenter().distanceTo(camPos),
                    p.getB().getCenter().distanceTo(camPos)));
            }
            if (best < 6.0) {
                com.wormhole.client.WormholeDebug.log(String.format(
                    "F%d RCAM=(%.3f,%.3f,%.3f) yaw=%.1f pitch=%.1f",
                    com.wormhole.client.WormholeDebug.frame, camPos.x, camPos.y, camPos.z,
                    camera.yRot(), camera.xRot()));
            }
        }
        for (PortalPair pair : pairs) {
            renderPortal(pair, pair.getA(), camera, camPos, look, FALLBACK_COLOR_A);
            renderPortal(pair, pair.getB(), camera, camPos, look, FALLBACK_COLOR_B);
        }
    }

    private static void renderPortal(PortalPair pair, PortalEnd src, Camera camera, Vec3 camPos, Vec3 look, int fallbackColor) {
        Vec3 toEnd = src.getCenter().subtract(camPos);
        double dist = toEnd.length();
        // Cull: too far, or clearly behind the camera (keep nearby ends so turning stays responsive).
        if (dist > RENDER_DISTANCE) {
            return;
        }
        if (dist > 3.0 && toEnd.dot(look) < 0.0) {
            return;
        }
        PortalEnd dest = pair.linkFor(src);

        if (com.wormhole.client.WormholeDebug.ENABLED) {
            net.minecraft.core.BlockPos o = src.getOrigin();
            boolean axisX = src.getAxis() == net.minecraft.core.Direction.Axis.X;
            double perp = axisX ? (camPos.z - src.getCenter().z) : (camPos.x - src.getCenter().x);
            boolean latInside = axisX
                ? (camPos.x >= o.getX() && camPos.x <= o.getX() + src.getWidth()
                   && camPos.y >= o.getY() && camPos.y <= o.getY() + src.getHeight())
                : (camPos.z >= o.getZ() && camPos.z <= o.getZ() + src.getWidth()
                   && camPos.y >= o.getY() && camPos.y <= o.getY() + src.getHeight());
            if (Math.abs(perp) < 1.5) {
                com.wormhole.client.WormholeDebug.log(String.format(
                    "STENCIL F%d %s perpDist=%.3f latInside=%b cam=(%.3f,%.3f,%.3f)",
                    com.wormhole.client.WormholeDebug.frame, (src == pair.getA() ? "A" : "B"),
                    perp, latInside, camPos.x, camPos.y, camPos.z));
            }
        }

        GL11.glEnable(GL11.GL_STENCIL_TEST);

        // Clear the bound FBO's stencil buffer to 0.
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_ZERO);
        GL11.glStencilMask(0xFF);
        PortalShapeRenderer.drawStencilQuad(src, camera);
        int fbo = StencilState.lastBoundFbo;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        // Write stencil = 1 where the portal plane is visible (depth-tested).
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glStencilMask(0xFF);
        PortalShapeRenderer.drawStencilDepthTested(src, camera);

        // Push depth of the masked region to the far plane.
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilMask(0x00);
        GL11.glDepthRange(0.0, 0.0);
        PortalShapeRenderer.drawDepthClear(src, camera);
        GL11.glDepthRange(0.0, 1.0);

        // Render the destination world into the masked region (or fall back to a flat color).
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilMask(0x00);
        boolean rendered = PortalContextSwitch.renderDestinationWorld(src, dest, camera);
        if (!rendered) {
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilMask(0x00);
            PortalShapeRenderer.drawColorFill(src, camera, fallbackColor);
        }

        // Reset the stencil region back to 0 and disable the test.
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glStencilMask(0xFF);
        PortalShapeRenderer.drawStencilQuad(src, camera);
        GL11.glStencilMask(0xFF);
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        // Visible frame around the opening.
        PortalShapeRenderer.drawBorder(src, camera, BORDER_COLOR);
    }
}
