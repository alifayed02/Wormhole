package com.wormhole.portal;

/**
 * Pure geometry for the wormhole traversal trigger: did a movement segment pass through a mouth's
 * throat centre this tick? A mouth is a ball of {@code radius} around {@code center}; the faithful
 * crossing point is the centre ({@code ℓ = 0}), i.e. the diametral plane perpendicular to the
 * motion. This is deliberately Minecraft-free so it can be verified by the {@link #main} self-check
 * (the same pattern {@code DnegLensModel} uses — the project has no JUnit runner).
 */
public final class MouthCrossing {
    private static final double EPS = 1e-12;

    private MouthCrossing() {
    }

    /**
     * True if the segment {@code P -> Q} crosses the plane through {@code C} perpendicular to the
     * segment direction (the {@code ℓ = 0} throat centre) AND actually passes within {@code r} of
     * {@code C} (through the ball, not skimming past). Tunnelling-safe: a fast one-tick pass fully
     * through the ball still returns true.
     */
    public static boolean segmentCrossesCenter(
            double px, double py, double pz,
            double qx, double qy, double qz,
            double cx, double cy, double cz,
            double r) {
        double dx = qx - px, dy = qy - py, dz = qz - pz;
        double len2 = dx * dx + dy * dy + dz * dz;
        if (len2 < EPS) {
            return false; // no movement this tick
        }
        double dotP = (px - cx) * dx + (py - cy) * dy + (pz - cz) * dz;
        double dotQ = (qx - cx) * dx + (qy - cy) * dy + (qz - cz) * dz;
        // Crossing the centre plane along the motion: P is before it (dotP<0), Q is at/after (dotQ>=0).
        if (!(dotP < 0.0 && dotQ >= 0.0)) {
            return false;
        }
        double t = -dotP / len2; // closest-approach parameter, guaranteed in [0,1] by the test above
        double clx = px + t * dx - cx;
        double cly = py + t * dy - cy;
        double clz = pz + t * dz - cz;
        double perp2 = clx * clx + cly * cly + clz * clz;
        return perp2 < r * r;
    }

    // ----- standalone self-check (run via javac+java; see the plan) -----

    public static void main(String[] args) {
        int failures = 0;
        // 1. straight through the centre -> crosses
        failures += check("through-centre",
            segmentCrossesCenter(-2, 0, 0, 2, 0, 0, 0, 0, 0, 1.0), true);
        // 2. parallel skim above the ball (perp distance 2 > r) -> no
        failures += check("skim-above",
            segmentCrossesCenter(-2, 2, 0, 2, 2, 0, 0, 0, 0, 1.0), false);
        // 3. fast one-tick pass fully through (tunnelling-safe) -> crosses
        failures += check("tunnel-through",
            segmentCrossesCenter(-5, 0, 0, 5, 0, 0, 0, 0, 0, 1.0), true);
        // 4. still approaching, not yet at the centre plane -> no
        failures += check("approaching",
            segmentCrossesCenter(-3, 0, 0, -2, 0, 0, 0, 0, 0, 1.0), false);
        // 5. already past the centre plane -> no
        failures += check("already-past",
            segmentCrossesCenter(0.5, 0, 0, 2, 0, 0, 0, 0, 0, 1.0), false);
        // 6. zero-length segment (no movement) -> no
        failures += check("no-movement",
            segmentCrossesCenter(0.3, 0, 0, 0.3, 0, 0, 0, 0, 0, 1.0), false);
        // 7. off-centre but still through the ball (perp 0.5 < r) -> crosses
        failures += check("through-offset",
            segmentCrossesCenter(-2, 0.5, 0, 2, 0.5, 0, 0, 0, 0, 1.0), true);

        // Transform oracle: the production transform (PortalPair.transformTeleportPosition) is a pure
        // translation dest = C_B + (src - C_A). Lock the formula here (PortalPair needs Minecraft's
        // Vec3, so it cannot run standalone; this asserts the maths the renderer/teleport rely on).
        double[] dest = translate(/*src*/ 10, 64, -5, /*C_A*/ 10, 64, -5, /*C_B*/ 200, 70, 200);
        failures += check("translate-centre-to-centre",
            dest[0] == 200 && dest[1] == 70 && dest[2] == 200, true);
        double[] dest2 = translate(10.3, 64, -5, 10, 64, -5, 200, 70, 200);
        failures += check("translate-keeps-offset",
            Math.abs(dest2[0] - 200.3) < 1e-9 && dest2[1] == 70 && dest2[2] == 200, true);

        if (failures > 0) {
            System.out.println("MouthCrossing self-check: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("MouthCrossing self-check: all passed");
    }

    private static double[] translate(double sx, double sy, double sz,
                                      double ax, double ay, double az,
                                      double bx, double by, double bz) {
        return new double[] {sx - ax + bx, sy - ay + by, sz - az + bz};
    }

    private static int check(String name, boolean actual, boolean expected) {
        boolean ok = actual == expected;
        System.out.println((ok ? "PASS " : "FAIL ") + name
            + " (expected " + expected + ", got " + actual + ")");
        return ok ? 0 : 1;
    }
}
