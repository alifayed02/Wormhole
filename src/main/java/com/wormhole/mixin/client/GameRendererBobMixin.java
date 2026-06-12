package com.wormhole.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Disables view-bob (and hurt-shake) in the main level projection, ported from SeamlessPortals'
 * {@code MainProjectionBobMixin}. The portal destination pass renders with an unbobbed camera;
 * if the main pass bobs, the see-through region and the surrounding world move differently with
 * every step — the "portal sliced in half, halves moving with head bob" artifact.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBobMixin {
    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"
        )
    )
    private void wormhole$skipMainBobHurt(GameRenderer self, CameraRenderState cameraState, PoseStack poseStack) {
    }

    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"
        )
    )
    private void wormhole$skipMainBobView(GameRenderer self, CameraRenderState cameraState, PoseStack poseStack) {
    }
}
