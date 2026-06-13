package com.wormhole.client.render.lens;

/** Pure math for the crossing warp: proximity intensity from camera distance to a mouth. */
public final class CrossingMath {
    public static final double WARP_FAR = 4.0;   // camDist/rho beyond which the warp is off
    public static final double WARP_NEAR = 1.0;  // camDist/rho at/under which the warp is full

    private CrossingMath() {
    }

    /** 0 when d >= WARP_FAR, 1 when d <= WARP_NEAR, smoothstep between. d = camDist / rho. */
    public static double intensity(double camDist, double rho) {
        if (rho <= 1e-6) {
            return 0.0;
        }
        double d = camDist / rho;
        double t = (d - WARP_FAR) / (WARP_NEAR - WARP_FAR); // 0 at FAR, 1 at NEAR
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t); // smoothstep
    }

    public static void main(String[] args) {
        int fails = 0;
        fails += check("far-off", intensity(10.0, 1.0) == 0.0, true);
        fails += check("at-far-edge", intensity(4.0, 1.0) == 0.0, true);
        fails += check("at-surface", Math.abs(intensity(1.0, 1.0) - 1.0) < 1e-9, true);
        fails += check("inside", Math.abs(intensity(0.5, 1.0) - 1.0) < 1e-9, true);
        fails += check("midpoint-monotonic", intensity(2.5, 1.0) > 0.0 && intensity(2.5, 1.0) < 1.0, true);
        fails += check("scales-with-rho", Math.abs(intensity(8.0, 8.0) - 1.0) < 1e-9, true); // d=1 -> full
        fails += check("zero-rho", intensity(5.0, 0.0) == 0.0, true);
        if (fails > 0) {
            System.out.println("CrossingMath: " + fails + " FAILED");
            System.exit(1);
        }
        System.out.println("CrossingMath: all passed");
    }

    private static int check(String name, boolean actual, boolean expected) {
        boolean ok = actual == expected;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        return ok ? 0 : 1;
    }
}
