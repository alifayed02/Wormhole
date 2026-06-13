package com.wormhole.client.render.capture;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.wormhole.Wormhole;
import com.wormhole.portal.PortalEnd;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Per-mouth cubemap capture: 6 off-screen faces (±X, ±Y, ±Z) rendered from the mouth centre via
 * {@link WorldCapture}, sampled on the sphere by direction. Static — captured on first sight and on
 * demand. The per-face camera basis (forward/right/up) is recorded so the shader maps a direction
 * to the right face + UV without convention guessing; it is identical across mouths (same 6
 * orientations), so it's stored once.
 */
public final class CubeCapture {
    public static final int FACE_SIZE = 256;
    private static final float[] FACE_YAW = {-90.0F, 90.0F, 0.0F, 0.0F, 0.0F, 180.0F};
    private static final float[] FACE_PITCH = {0.0F, 0.0F, -90.0F, 90.0F, 0.0F, 0.0F};

    private static final Map<PortalEnd, TextureTarget[]> MOUTHS = new HashMap<>();
    private static final Set<PortalEnd> READY = new HashSet<>();
    private static final Vector3f[] fwd = new Vector3f[6];
    private static final Vector3f[] right = new Vector3f[6];
    private static final Vector3f[] up = new Vector3f[6];
    private static boolean basisReady = false;

    private CubeCapture() {
    }

    public static boolean isReady(PortalEnd end) {
        return READY.contains(end) && basisReady;
    }

    public static TextureTarget[] faces(PortalEnd end) {
        return MOUTHS.get(end);
    }

    public static Vector3f forward(int i) {
        return fwd[i];
    }

    public static Vector3f right(int i) {
        return right[i];
    }

    public static Vector3f up(int i) {
        return up[i];
    }

    /**
     * Attempt to capture all 6 faces of the through-view for {@code end} from {@code fromPos} — the
     * point the through-view is seen from (the camera's image on the partner side, so the geodesic
     * sample is parallax-correct). Marks the mouth ready once every face has succeeded.
     */
    public static void capture(PortalEnd end, Vec3 fromPos) {
        TextureTarget[] targets = MOUTHS.computeIfAbsent(end, k -> {
            TextureTarget[] t = new TextureTarget[6];
            for (int i = 0; i < 6; i++) {
                t[i] = new TextureTarget("wormhole_cube_" + i, FACE_SIZE, FACE_SIZE, true);
            }
            return t;
        });
        Vec3 c = fromPos;
        boolean allOk = true;
        for (int i = 0; i < 6; i++) {
            boolean ok = WorldCapture.capture(c, FACE_YAW[i], FACE_PITCH[i], 90.0F, targets[i]);
            if (ok) {
                if (fwd[i] == null) {
                    fwd[i] = new Vector3f();
                    right[i] = new Vector3f();
                    up[i] = new Vector3f();
                }
                fwd[i].set(WorldCapture.lastForward());
                right[i].set(WorldCapture.lastRight());
                up[i].set(WorldCapture.lastUp());
            } else {
                allOk = false;
            }
        }
        if (allOk) {
            basisReady = true;
            if (READY.add(end)) {
                Wormhole.LOGGER.info("[lens] cubemap captured for mouth at {}", c);
            }
        }
    }

    /** Force a fresh capture next frame for all known mouths. */
    public static void invalidateAll() {
        READY.clear();
    }

    public static void retainOnly(Set<PortalEnd> active) {
        var it = MOUTHS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!active.contains(e.getKey())) {
                for (TextureTarget t : e.getValue()) {
                    if (t != null) {
                        t.destroyBuffers();
                    }
                }
                READY.remove(e.getKey());
                it.remove();
            }
        }
    }

    public static void dispose() {
        for (TextureTarget[] arr : MOUTHS.values()) {
            for (TextureTarget t : arr) {
                if (t != null) {
                    t.destroyBuffers();
                }
            }
        }
        MOUTHS.clear();
        READY.clear();
        basisReady = false;
    }
}
