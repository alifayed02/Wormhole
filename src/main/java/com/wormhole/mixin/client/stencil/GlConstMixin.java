package com.wormhole.mixin.client.stencil;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.TextureFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces depth textures to be allocated as {@code DEPTH24_STENCIL8} instead of {@code DEPTH32F}, so
 * every render target's depth texture also carries a stencil buffer.
 */
@Mixin(GlConst.class)
public abstract class GlConstMixin {
    // GL_DEPTH24_STENCIL8 = 0x88F0 (35056); GL_DEPTH_STENCIL = 0x84F9 (34041);
    // GL_UNSIGNED_INT_24_8 = 0x84FA (34042).
    @Inject(method = "toGlInternalId", at = @At("HEAD"), cancellable = true)
    private static void wormhole$changeDepthFormat(TextureFormat format, CallbackInfoReturnable<Integer> cir) {
        if (format == TextureFormat.DEPTH32) {
            cir.setReturnValue(35056);
        }
    }

    @Inject(method = "toGlExternalId", at = @At("HEAD"), cancellable = true)
    private static void wormhole$changeDepthExternalFormat(TextureFormat format, CallbackInfoReturnable<Integer> cir) {
        if (format == TextureFormat.DEPTH32) {
            cir.setReturnValue(34041);
        }
    }

    @Inject(method = "toGlType", at = @At("HEAD"), cancellable = true)
    private static void wormhole$changeDepthType(TextureFormat format, CallbackInfoReturnable<Integer> cir) {
        if (format == TextureFormat.DEPTH32) {
            cir.setReturnValue(34042);
        }
    }
}
