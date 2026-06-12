package com.wormhole.client.render.capture;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.wormhole.Wormhole;
import com.wormhole.client.ClientPortalStore;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

/**
 * Step-2 verification harness for {@link WorldCapture}: on a keypress, capture the nearest linked
 * mouth's OUTWARD view (away from its partner) into an off-screen target and blit it over the
 * screen, so we can confirm the off-screen world render is correct before building the 6-face
 * cubemap on top. Toggle off to return to the normal view. Debug-only; not the final pipeline.
 */
public final class CaptureDebug {
    private static final int SIZE = 512;
    private static final float FOV_DEG = 90.0F;

    private static TextureTarget target;
    private static boolean active;       // blitting the captured image over the screen
    private static boolean recapture;    // a fresh capture is requested

    private CaptureDebug() {
    }

    /** Keybind handler: toggle the overlay on and request a fresh capture. */
    public static void toggle() {
        active = !active;
        if (active) {
            recapture = true;
        }
        Wormhole.LOGGER.info("[capture] debug overlay {}", active ? "ON (capturing)" : "off");
    }

    public static void dispose() {
        active = false;
        recapture = false;
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    /**
     * Capture step — driven from AFTER_TRANSLUCENT_TERRAIN (a valid point for the nested world
     * render). Captures once per toggle into the off-screen target; does NOT draw to the screen.
     */
    public static void captureIfNeeded() {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(mc.level.dimension());
        if (pairs.isEmpty()) {
            return;
        }
        PortalPair pair = nearest(pairs, mc.gameRenderer.getMainCamera().position());
        PortalEnd mouth = pair.getA();
        PortalEnd partner = pair.getB();

        if (target == null) {
            target = new TextureTarget("wormhole_capture_debug", SIZE, SIZE, true);
        }
        if (recapture) {
            // Look outward: from the partner, through this mouth, and onward.
            Vec3 c = mouth.getCenter();
            Vec3 outward = c.subtract(partner.getCenter()).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-outward.x, outward.z));
            double horiz = Math.sqrt(outward.x * outward.x + outward.z * outward.z);
            float pitch = (float) Math.toDegrees(-Math.atan2(outward.y, horiz));
            Wormhole.LOGGER.info(
                "[wh-cap] onWorldRendered: pair={} mouthA=({},{},{}) partnerB=({},{},{}) outward=({},{},{}) yaw={} pitch={}",
                pair.getId().toString().substring(0, 8),
                f(c.x), f(c.y), f(c.z),
                f(partner.getCenter().x), f(partner.getCenter().y), f(partner.getCenter().z),
                f(outward.x), f(outward.y), f(outward.z), yaw, pitch);
            boolean ok = WorldCapture.capture(c, yaw, pitch, FOV_DEG, target);
            if (ok) {
                recapture = false;
                blitLogFrames = 3;
            }
            // if not ok (chunks compiling), keep recapture=true and retry next frame
        }
    }

    /**
     * Overlay step — driven from END_MAIN (after the whole world render: clouds, particles,
     * weather). Blits the captured image over the finished frame so it reads cleanly, instead of
     * being painted over by the rest of the main pass.
     */
    public static void renderOverlay() {
        if (active) {
            blitToScreen();
        }
    }

    private static int blitLogFrames = 0;

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static PortalPair nearest(List<PortalPair> pairs, Vec3 from) {
        PortalPair best = pairs.get(0);
        double bestD = Double.MAX_VALUE;
        for (PortalPair p : pairs) {
            double d = p.getA().getCenter().distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static void blitToScreen() {
        if (target == null || target.getColorTextureView() == null) {
            return;
        }
        var mainRT = Minecraft.getInstance().getMainRenderTarget();
        if (blitLogFrames > 0) {
            blitLogFrames--;
            Wormhole.LOGGER.info("[wh-cap] blit capture(512x512) -> mainRT={}x{}", mainRT.width, mainRT.height);
        }
        // Force the overlay opaque: without this the captured sky's low alpha lets the live POV
        // bleed through (the old portal composite disabled blend here for the same reason).
        GL11.glDisable(GL11.GL_BLEND);
        RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "wormhole_capture_blit", mainRT.getColorTextureView(), OptionalInt.empty(),
            mainRT.getDepthTextureView(), OptionalDouble.empty());
        try {
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", target.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        } finally {
            pass.close();
        }
    }
}
