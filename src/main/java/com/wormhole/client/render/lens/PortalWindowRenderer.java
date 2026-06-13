package com.wormhole.client.render.lens;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.wormhole.client.render.capture.WorldCapture;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Renders the destination world for one mouth into a screen-sized off-screen target, from the
 * player's viewpoint translated through the wormhole (pure translation; identity rotation — the
 * Phase 1 teleport transform), using the player's own projection so the result can be sampled by
 * screen coordinate. An oblique near-clip at the destination mouth removes near-side geometry. This
 * is the parallax-correct "window" the sphere shader blends into its undistorted centre.
 */
public final class PortalWindowRenderer {
    private static final Map<PortalEnd, TextureTarget> TARGETS = new HashMap<>();

    private PortalWindowRenderer() {
    }

    /** The most recent window target for a mouth, or null if not rendered this frame. */
    public static TextureTarget target(PortalEnd end) {
        return TARGETS.get(end);
    }

    /**
     * Render the destination window seen THROUGH {@code end} (whose partner is {@code partner}):
     * a parallax-correct view of {@code partner}'s surroundings from the player's eye translated to
     * the partner side. The projection is rebuilt from the camera FOV to match the main view so the
     * result is screen-aligned (sampleable by {@code gl_FragCoord}). Returns true if drawn.
     */
    public static boolean render(PortalPair pair, PortalEnd end, PortalEnd partner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.position();
        // Virtual eye: translate the player's eye by the same offset the teleport uses.
        Vec3 eye = pair.transformTeleportPosition(end, camPos);
        // Clip plane at the destination mouth centre; normal = camera forward so destination geometry
        // between the virtual eye and the mouth is culled (near-side bleed).
        Vec3 fwd = mc.player.getViewVector(1.0F);

        var mainRt = mc.getMainRenderTarget();
        TextureTarget target = TARGETS.get(end);
        if (target == null) {
            target = new TextureTarget("wormhole_window", mainRt.width, mainRt.height, true);
            TARGETS.put(end, target);
        } else if (target.width != mainRt.width || target.height != mainRt.height) {
            target.resize(mainRt.width, mainRt.height);
        }

        // Match the main level projection: a plain perspective from the camera FOV + window aspect.
        float aspect = (float) mainRt.width / (float) mainRt.height;
        float far = Math.max(mc.options.getEffectiveRenderDistance(), 2) * 16.0F * 4.0F;
        Matrix4f proj = new Matrix4f().setPerspective(
            (float) Math.toRadians(cam.getFov()), aspect, 0.05F, far,
            RenderSystem.getDevice().isZZeroToOne());

        return WorldCapture.captureWithProjection(
            eye, mc.player.getYRot(), mc.player.getXRot(), proj,
            partner.getCenter(), fwd, target);
    }

    public static void retainOnly(Set<PortalEnd> active) {
        var it = TARGETS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!active.contains(e.getKey())) {
                e.getValue().destroyBuffers();
                it.remove();
            }
        }
    }

    public static void dispose() {
        for (TextureTarget t : TARGETS.values()) {
            t.destroyBuffers();
        }
        TARGETS.clear();
    }
}
