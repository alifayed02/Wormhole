package com.wormhole.client.render.lens;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.wormhole.Wormhole;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Bakes the DNeg light-bending map ({@link DnegLensModel}) into a small data texture sampled by the
 * sphere shader. Each texel stores the emergent ray azimuth phi (mod 2pi, the in-plane deflection)
 * for a through ray, indexed by:
 * <ul>
 *   <li><b>u</b> = normalized incidence {@code alpha / alpha_crit} in [0,1) (alpha_crit = the
 *       silhouette edge, where the impact parameter equals the throat radius), and</li>
 *   <li><b>v</b> = camera distance {@code d/rho} in [1, D_MAX].</li>
 * </ul>
 * Stored 8-bit in the red channel (RGBA8 is linear here — no sRGB variant exists — so the value
 * round-trips). Baked once, lazily, on the render thread (a one-time hitch).
 */
public final class DeflectionLut {
    public static final int W = 256;        // normalized incidence
    public static final int H = 48;         // camera distance rows
    public static final double D_MAX = 24.0;
    public static final double A_OVER_RHO = 0.005;
    public static final double W_OVER_RHO = 0.05;
    private static final double TWO_PI = Math.PI * 2.0;

    private static DynamicTexture texture;
    private static boolean failed;

    private DeflectionLut() {
    }

    /** Returns the LUT texture view, baking it on first call. Null only if baking failed. */
    public static GpuTextureView view() {
        if (texture == null && !failed) {
            bake();
        }
        return texture == null ? null : texture.getTextureView();
    }

    private static void bake() {
        try {
            long t0 = System.nanoTime();
            DnegLensModel model = new DnegLensModel(A_OVER_RHO, W_OVER_RHO);
            NativeImage img = new NativeImage(W, H, false);
            for (int row = 0; row < H; row++) {
                double dOverRho = 1.0 + (D_MAX - 1.0) * row / (double) (H - 1);
                double alphaCrit = Math.asin(Math.min(1.0, 1.0 / dOverRho));
                for (int col = 0; col < W; col++) {
                    double alphaN = col / (double) (W - 1);
                    double alpha = alphaN * alphaCrit;
                    double phi = model.trace(dOverRho, alpha).phiFinal();
                    double mod = ((phi % TWO_PI) + TWO_PI) % TWO_PI;
                    int v = (int) Math.round(mod / TWO_PI * 255.0) & 0xFF;
                    img.setPixelABGR(col, row, 0xFF000000 | v); // value in the R channel
                }
            }
            texture = new DynamicTexture(() -> "wormhole_deflection_lut", img);
            Wormhole.LOGGER.info("[lens] deflection LUT baked {}x{} (D_MAX={}) in {} ms",
                W, H, D_MAX, (System.nanoTime() - t0) / 1_000_000L);
        } catch (Exception e) {
            failed = true;
            Wormhole.LOGGER.error("[lens] failed to bake deflection LUT", e);
        }
    }
}
