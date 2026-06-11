package com.wormhole.mixin.client.stencil;

import com.mojang.blaze3d.opengl.GlBackend;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Requests an 8-bit stencil buffer from GLFW so portal masking has stencil bits to work with. */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {
    @Inject(method = "setWindowHints", at = @At("TAIL"))
    private void wormhole$addStencilBits(CallbackInfo ci) {
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
    }
}
