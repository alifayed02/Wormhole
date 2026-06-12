package com.wormhole.mixin.client.stencil;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.wormhole.Wormhole;
import com.wormhole.client.render.StencilState;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When an FBO is created with a depth texture, re-attaches that texture as a combined
 * {@code GL_DEPTH_STENCIL_ATTACHMENT} (instead of depth-only) so the stencil buffer is usable.
 * Paired with {@link GlConstMixin}, which makes the depth texture a {@code DEPTH24_STENCIL8}.
 */
@Mixin(GlTextureView.class)
public abstract class StencilFboMixin {
    @Inject(method = "createFbo(Lcom/mojang/blaze3d/opengl/DirectStateAccess;I)I", at = @At("RETURN"))
    private void wormhole$fixStencilAttachment(DirectStateAccess dsa, int depthId, CallbackInfoReturnable<Integer> cir) {
        if (depthId == 0) {
            return;
        }
        int fbo = cir.getReturnValue();
        int oldFbo = GL30.glGetInteger(36006); // GL_DRAW_FRAMEBUFFER_BINDING
        GL30.glBindFramebuffer(36160, fbo); // GL_FRAMEBUFFER
        // Move the depth texture from GL_DEPTH_ATTACHMENT (36096) to GL_DEPTH_STENCIL_ATTACHMENT (33306).
        GL30.glFramebufferTexture2D(36160, 36096, 3553, 0, 0);
        GL30.glFramebufferTexture2D(36160, 33306, 3553, depthId, 0);
        int status = GL30.glCheckFramebufferStatus(36160);
        if (status != 36053) { // GL_FRAMEBUFFER_COMPLETE
            Wormhole.LOGGER.error("[stencil] FBO {} incomplete after depth-stencil reattach (status {}); reverting", fbo, status);
            GL30.glFramebufferTexture2D(36160, 33306, 3553, 0, 0);
            GL30.glFramebufferTexture2D(36160, 36096, 3553, depthId, 0);
        } else {
            StencilState.gameFboId = fbo;
        }
        GL30.glBindFramebuffer(36160, oldFbo);
    }
}
