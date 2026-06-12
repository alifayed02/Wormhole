package com.wormhole.client.render.lens;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.wormhole.Wormhole;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Bakes the DNeg <i>around</i>-ray bending angle ({@link DnegLensModel#aroundDeflection}) into a 1D LUT
 * sampled by the around-the-mouth lens shader. Texel <b>u</b> maps the impact parameter
 * {@code b = 1 + u·(B_MAX-1)} (ρ units) to the deflection δ(b): full at the silhouette (b→1, the
 * Einstein ring), fading to ~0 at the edge of influence. Computed in full double precision, clamped to
 * {@link #DELTA_MAX}, stored normalized in the red channel (sampled LINEAR → no banding).
 */
public final class AroundDeflectionLut {
    public static final int W = 512;
    public static final double B_MAX = 2.6;          // must match B_MAX in wormhole_around.fsh
    public static final double DELTA_MAX = 2.5;      // must match DELTA_MAX in wormhole_around.fsh
    public static final double A_OVER_RHO = 0.005;
    public static final double W_OVER_RHO = 0.45;    // lensing width: bigger = stronger, wider warp

    private static DynamicTexture texture;
    private static boolean failed;

    private AroundDeflectionLut() {
    }

    public static GpuTextureView view() {
        if (texture == null && !failed) {
            bake();
        }
        return texture == null ? null : texture.getTextureView();
    }

    private static void bake() {
        try {
            DnegLensModel model = new DnegLensModel(A_OVER_RHO, W_OVER_RHO);
            NativeImage img = new NativeImage(W, 1, false);
            for (int col = 0; col < W; col++) {
                double u = col / (double) (W - 1);
                double b = 1.0 + (B_MAX - 1.0) * u;
                double delta = b <= 1.0 ? DELTA_MAX : Math.min(model.aroundDeflection(b), DELTA_MAX);
                int v = (int) Math.round(Math.max(0.0, delta) / DELTA_MAX * 255.0) & 0xFF;
                img.setPixelABGR(col, 0, 0xFF000000 | v);
            }
            texture = new DynamicTexture(() -> "wormhole_around_deflection_lut", img);
            Wormhole.LOGGER.info("[lens] around-deflection LUT baked {} px (W/rho={})", W, W_OVER_RHO);
        } catch (Exception e) {
            failed = true;
            Wormhole.LOGGER.error("[lens] failed to bake around-deflection LUT", e);
        }
    }
}
