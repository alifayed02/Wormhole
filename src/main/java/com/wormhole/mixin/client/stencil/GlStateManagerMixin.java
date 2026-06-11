package com.wormhole.mixin.client.stencil;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.wormhole.client.render.StencilState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the currently bound draw framebuffer so we can target it when clearing the stencil. */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {
    @Inject(method = "_glBindFramebuffer", at = @At("HEAD"))
    private static void wormhole$captureFramebufferBind(int target, int framebuffer, CallbackInfo ci) {
        // GL_FRAMEBUFFER = 0x8D40 (36160)
        if (framebuffer > 0 && target == 36160) {
            StencilState.lastBoundFbo = framebuffer;
        }
    }
}
