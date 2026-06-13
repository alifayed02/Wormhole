package com.wormhole.client.render.lens;

import com.wormhole.client.ClientPortalStore;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame crossing state: the nearest local mouth to the camera, the proximity warp intensity, and
 * that mouth (so the full-screen crossing pass knows which partner cube to sample). Recomputed each
 * frame via {@link #update}. Single source of truth shared by the renderers.
 */
public final class CrossingState {
    private static PortalEnd crossingMouth;
    private static double intensity;

    private CrossingState() {
    }

    /** True when the full-screen crossing warp should run this frame. */
    public static boolean active() {
        return crossingMouth != null && intensity > 0.0;
    }

    public static PortalEnd mouth() {
        return crossingMouth;
    }

    public static double intensity() {
        return intensity;
    }

    /** Recompute from the camera + linked pairs in the current dimension. Call once per frame, first. */
    public static void update() {
        crossingMouth = null;
        intensity = 0.0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        ResourceKey<Level> dim = mc.level.dimension();
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(dim);
        for (PortalPair pair : pairs) {
            crossingMouth = consider(pair.getA(), cam, dim, crossingMouth);
            crossingMouth = consider(pair.getB(), cam, dim, crossingMouth);
        }
    }

    private static PortalEnd consider(PortalEnd end, Vec3 cam, ResourceKey<Level> dim, PortalEnd best) {
        if (!end.getDimension().equals(dim)) {
            return best;
        }
        double dist = Math.sqrt(end.getCenter().distanceToSqr(cam));
        double i = CrossingMath.intensity(dist, end.getRadius());
        if (i > intensity) {
            intensity = i;
            return end;
        }
        return best;
    }
}
