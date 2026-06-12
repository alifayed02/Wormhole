package com.wormhole.client.render.lens;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

/**
 * A per-frame snapshot of the main scene colour buffer — the actual pixels on screen. The around-pass
 * warps these (the real surroundings the player sees) by the lensing deflection, so we must read them
 * while still writing the lens into the main buffer: copy to a scratch texture first, sample the copy.
 */
public final class SceneCopy {
    private static GpuTexture tex;
    private static GpuTextureView view;
    private static int width;
    private static int height;

    private SceneCopy() {
    }

    public static void capture(RenderTarget rt) {
        GpuTexture src = rt.getColorTexture();
        if (src == null) {
            return;
        }
        int sw = src.getWidth(0);
        int sh = src.getHeight(0);
        if (tex == null || sw != width || sh != height) {
            dispose();
            tex = RenderSystem.getDevice().createTexture(() -> "wormhole_scene_color",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, sw, sh, 1, 1);
            view = RenderSystem.getDevice().createTextureView(tex);
            width = sw;
            height = sh;
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(src, tex, 0, 0, 0, 0, 0, sw, sh);
    }

    public static GpuTextureView view() {
        return view;
    }

    public static void dispose() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (tex != null) {
            tex.close();
            tex = null;
        }
        width = 0;
        height = 0;
    }
}
