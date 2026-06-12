package com.wormhole.client.render.lens;

/**
 * DNeg wormhole light-bending model (James/von Tunzelmann/Franklin/Thorne, arXiv:1502.03809).
 * Pure math — no engine dependencies — so it can be unit-checked standalone (see {@link #main}).
 *
 * <p>All distances are in units of the throat radius ρ (we set ρ = 1 internally; callers scale by
 * the actual mouth radius). The model is parameterized by two dimensionless quantities:
 * {@code aOverRho} (throat half-length / ρ) and {@code wOverRho} (lensing width / ρ), with the
 * mass parameter {@code M/ρ = (W/ρ) / 1.42953} (Eq 7).
 *
 * <p>The geodesics are traced in the equatorial plane (θ = π/2, p_θ = 0, so B² = b²), which by
 * spherical symmetry captures every ray after rotating into the plane spanned by the camera's
 * radial direction and the view ray. See {@code docs/references/dneg-wormhole-rendering.md}.
 */
public final class DnegLensModel {
    /** W = 1.42953·M (Eq 7). */
    private static final double W_OVER_M = 1.42953;

    public final double aOverRho;
    public final double wOverRho;
    private final double mOverRho;

    public DnegLensModel(double aOverRho, double wOverRho) {
        this.aOverRho = aOverRho;
        this.wOverRho = wOverRho;
        this.mOverRho = wOverRho / W_OVER_M;
    }

    /** Areal radius r(ℓ)/ρ as a function of proper distance ℓ/ρ (Eqs 5a–5c). */
    public double radius(double l) {
        double absL = Math.abs(l);
        if (absL <= aOverRho) {
            return 1.0;
        }
        double x = 2.0 * (absL - aOverRho) / (Math.PI * mOverRho);
        return 1.0 + mOverRho * (x * Math.atan(x) - 0.5 * Math.log1p(x * x));
    }

    /** dr/dℓ (dimensionless): 0 inside the throat, (2/π)·arctan(x)·sign(ℓ) outside (footnote 19). */
    public double drdl(double l) {
        double absL = Math.abs(l);
        if (absL <= aOverRho) {
            return 0.0;
        }
        double x = 2.0 * (absL - aOverRho) / (Math.PI * mOverRho);
        return (2.0 / Math.PI) * Math.atan(x) * Math.signum(l);
    }

    /** The positive-side ℓ at which r(ℓ) = d (camera areal radius = world distance from centre). */
    public double invertRadius(double d) {
        if (d <= 1.0) {
            return aOverRho; // at/inside the throat mouth
        }
        // r is strictly increasing for ℓ > a; bisect.
        double lo = aOverRho;
        double hi = aOverRho + 1.0;
        while (radius(hi) < d) {
            hi *= 2.0;
            if (hi > 1.0e9) {
                return hi;
            }
        }
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            if (radius(mid) < d) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** Result of tracing one ray back to a celestial sphere. */
    public record Trace(int side, double phiFinal, boolean ring) {
        /** {@code side}: +1 = near celestial sphere (ℓ→+∞), -1 = far / through the throat (ℓ→-∞). */
    }

    private static final double L_FAR = 80.0;     // |ℓ| at which a ray is considered escaped
    private static final double DT = 0.01;        // affine/time step (ρ units)
    private static final int MAX_STEPS = 200_000;

    /**
     * Traces the backward ray for a camera at world distance {@code d} (≥1, in ρ units) whose view
     * ray makes angle {@code alpha} with the inward radial direction (0 = straight at the centre,
     * π/2 = tangent). Returns which celestial sphere it reaches and the accumulated azimuth
     * {@code phiFinal} (the in-plane deflection).
     */
    public Trace trace(double d, double alpha) {
        double lc = invertRadius(d);
        double rc = radius(lc);            // == d to numerical precision
        double b = rc * Math.sin(alpha);   // conserved impact parameter
        double l = lc;
        double phi = 0.0;
        double pl = -Math.cos(alpha);      // inward (toward the throat)

        for (int step = 0; step < MAX_STEPS; step++) {
            if (Math.abs(l) >= L_FAR) {
                return new Trace((int) Math.signum(l), phi, false);
            }
            // RK4 on state (l, phi, pl); b is constant.
            double[] k1 = deriv(l, pl, b);
            double[] k2 = deriv(l + 0.5 * DT * k1[0], pl + 0.5 * DT * k1[2], b);
            double[] k3 = deriv(l + 0.5 * DT * k2[0], pl + 0.5 * DT * k2[2], b);
            double[] k4 = deriv(l + DT * k3[0], pl + DT * k3[2], b);
            l += DT / 6.0 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]);
            phi += DT / 6.0 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]);
            pl += DT / 6.0 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2]);
        }
        // Did not escape in MAX_STEPS — a ray winding at the throat: the Einstein ring.
        return new Trace((int) Math.signum(l), phi, true);
    }

    /**
     * Asymptotic light-bending angle for an AROUND ray (impact parameter {@code b > ρ}, which turns
     * back to the near side instead of going through). Equals {@code 2·∫ dφ − π}: the total azimuth
     * a ray sweeps from infinity, in past the turning point at {@code r = b}, and back out to
     * infinity, minus the straight-line π. → 0 for large {@code b} (no bending), → ∞ as {@code b → ρ}
     * (the Einstein ring). {@code b} is in ρ units.
     */
    public double aroundDeflection(double b) {
        if (b <= 1.0) {
            return 0.0;
        }
        double lt = invertRadius(b);      // turning point: r(lt) = b on the near side
        double dl = 0.002;
        double half = 0.0;
        for (double l = lt + dl * 0.5; l < L_FAR; l += dl) {
            double r = radius(l);
            double denomSq = 1.0 - (b * b) / (r * r);
            if (denomSq > 1.0e-9) {
                half += (b / (r * r)) / Math.sqrt(denomSq) * dl;
            }
        }
        return 2.0 * half - Math.PI;
    }

    /** Derivatives [dl/dt, dphi/dt, dpl/dt] for the equatorial geodesic (Eqs A.7a,c,d with B²=b²). */
    private double[] deriv(double l, double pl, double b) {
        double r = radius(l);
        double r2 = r * r;
        return new double[] {
            pl,                               // dℓ/dt
            b / r2,                           // dφ/dt
            b * b * drdl(l) / (r2 * r),       // dp_ℓ/dt
        };
    }

    // ----- standalone numeric check (no engine deps): javac + java this class directly -----

    public static void main(String[] args) {
        DnegLensModel m = new DnegLensModel(0.005, 0.05); // Interstellar-like
        for (double d : new double[] {2.0, 3.0, 10.0}) {
            double critDeg = Math.toDegrees(Math.asin(1.0 / d));
            System.out.printf("%n=== camera d=%.1fρ   α_crit=asin(ρ/d)=%.3f°  (b=ρ boundary) ===%n", d, critDeg);
            System.out.printf("%8s %8s %6s %12s %6s%n", "α(deg)", "b/ρ", "side", "Δφ(deg)", "ring");
            for (double aDeg : new double[] {0, 5, 10, 15, 18, 19, 19.4, 19.6, 20, 25, 30, 45, 60, 80, 89}) {
                double a = Math.toRadians(aDeg);
                Trace t = m.trace(d, a);
                System.out.printf("%8.2f %8.4f %6s %12.2f %6s%n",
                    aDeg, d * Math.sin(a),
                    t.side() > 0 ? "near" : "far",
                    Math.toDegrees(t.phiFinal()), t.ring() ? "RING" : "");
            }
        }
    }
}
