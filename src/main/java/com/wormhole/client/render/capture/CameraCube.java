package com.wormhole.client.render.capture;

import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.world.phys.Vec3;

/**
 * A single 360° cubemap captured from the camera position in the CURRENT dimension — the "own
 * universe" celestial sphere for the crossing warp's around-branch (rays that stay in this universe).
 * Reuses {@link CubeCapture}'s face orientations + shared world-aligned basis (identical faces).
 */
public final class CameraCube {
    private static TextureTarget[] faces;
    private static boolean ready;

    private CameraCube() {
    }

    public static boolean isReady() {
        return ready && faces != null;
    }

    public static TextureTarget[] faces() {
        return faces;
    }

    /** Re-render all 6 faces from {@code fromPos} in the current level. */
    public static void capture(Vec3 fromPos) {
        if (faces == null) {
            faces = new TextureTarget[6];
            for (int i = 0; i < 6; i++) {
                faces[i] = new TextureTarget("wormhole_camcube_" + i, CubeCapture.FACE_SIZE, CubeCapture.FACE_SIZE, true);
            }
        }
        boolean allOk = true;
        for (int i = 0; i < 6; i++) {
            if (!WorldCapture.capture(fromPos, CubeCapture.faceYaw(i), CubeCapture.facePitch(i), 90.0F, faces[i])) {
                allOk = false;
            }
        }
        ready = allOk;
    }

    public static void dispose() {
        if (faces != null) {
            for (TextureTarget t : faces) {
                if (t != null) {
                    t.destroyBuffers();
                }
            }
            faces = null;
        }
        ready = false;
    }
}
