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
        maybeLightSnapshot(mc, pairs);

        // Drop per-end renderers whose portal no longer exists.
        java.util.Set<PortalEnd> activeEnds = new java.util.HashSet<>();
        for (PortalPair pair : pairs) {
            activeEnds.add(pair.getA());
            activeEnds.add(pair.getB());
        }
        PortalRenderer.retainOnly(activeEnds);

        for (PortalPair pair : pairs) {
            renderPortal(pair, pair.getA(), camera, camPos, look, FALLBACK_COLOR_A);
            renderPortal(pair, pair.getB(), camera, camPos, look, FALLBACK_COLOR_B);
        }
    }

    // Not MIN_VALUE: gameTime - MIN_VALUE overflows negative and the rate-limiter never fires.
    private static long lastLightSnapshot = -1000;

    /**
     * Lighting-bug instrumentation ({@code [wh-light]}): every 2 seconds while portals render,
     * log the client light engine's block/sky values at each end's opening plus the dedicated
     * destination renderer's state. If the end the bug shows in has normal light values here,
     * the problem is in the destination-view rendering (lightmap/fog/section baking), not the
     * light data itself.
     */
    private static void maybeLightSnapshot(Minecraft mc, List<PortalPair> pairs) {
        if (!com.wormhole.client.WormholeDebug.ENABLED) {
            return;
        }
        long now = mc.level.getGameTime();
        if (now - lastLightSnapshot < 40) {
            return;
        }
        lastLightSnapshot = now;
        for (PortalPair pair : pairs) {
            com.wormhole.client.WormholeDebug.log(String.format(
                "[wh-light] t=%d pair=%s A{%s} B{%s} skyDarken=%d dedicatedSections=%d",
                now, pair.getId().toString().substring(0, 8),
                lightAt(mc, pair.getA()), lightAt(mc, pair.getB()),
                mc.level.getSkyDarken(), PortalRenderer.debugVisibleSections()));
        }
    }

    private static String lightAt(Minecraft mc, PortalEnd end) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(end.getCenter());
        return String.format("center=%s block=%d sky=%d",
            pos.toShortString(),
            mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos),
            mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
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

        logViewDiagnostics(pair, src, camPos, rendered);
    }

    // ----- [wh-view] diagnostics: the standing-in-the-portal double-view investigation -----

    private static final java.util.Map<PortalEnd, String> lastViewState = new java.util.HashMap<>();
    private static long lastViewLog = -1000;

    /**
     * Logs the camera's relation to a rendered end while within 2 blocks of its plane: signed
     * perpendicular distance (negative = behind the plane relative to its normal), whether the
     * camera is inside the trigger volume, whether the quad plane straddles the camera's near
     * plane (|perp| < 0.1 -> the stencil mask only covers part of the screen), and whether the
     * destination pass applied its oblique near-clip (skipped when the virtual camera is within
     * MIN_CLIP_PERP of the destination plane -> back-side geometry bleeds into the view).
     * Logged on every state change, else at most ~6/s.
     */
    private static void logViewDiagnostics(PortalPair pair, PortalEnd src, Vec3 camPos, boolean rendered) {
        if (!com.wormhole.client.WormholeDebug.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 n = src.getNormal();
        Vec3 c = src.getCenter();
        double perp = n.x * (camPos.x - c.x) + n.y * (camPos.y - c.y) + n.z * (camPos.z - c.z);
        if (Math.abs(perp) > 2.0) {
            lastViewState.remove(src);
            return;
        }
        boolean inVolume = src.containsPoint(camPos);
        boolean quadStraddlesNearPlane = Math.abs(perp) < 0.1;
        String key = inVolume + "|" + rendered + "|" + quadStraddlesNearPlane + "|"
            + PortalContextSwitch.lastObliqueApplied;
        String prev = lastViewState.put(src, key);
        long now = mc.level.getGameTime();
        boolean changed = !key.equals(prev);
        if (!changed && now - lastViewLog < 3) {
            return;
        }
        lastViewLog = now;
        com.wormhole.client.WormholeDebug.log(String.format(java.util.Locale.ROOT,
            "[wh-view]%s end=%s perp=%+.3f inVol=%b quadAtNearPlane=%b rendered=%b "
                + "obliqueClip=%b destClipPerp=%.3f suppressed=%b cam=(%.3f,%.3f,%.3f)",
            changed ? " CHANGE" : "", src == pair.getA() ? "A" : "B", perp, inVolume,
            quadStraddlesNearPlane, rendered,
            PortalContextSwitch.lastObliqueApplied, PortalContextSwitch.lastObliquePerp,
            com.wormhole.client.ClientPortalTeleport.isSuppressed(),
            camPos.x, camPos.y, camPos.z));
    }
}
