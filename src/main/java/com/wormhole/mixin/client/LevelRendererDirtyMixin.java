package com.wormhole.mixin.client;

import com.wormhole.client.render.PortalRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla only marks sections dirty on the MAIN level renderer (block changes, light updates).
 * Mirror those marks into the dedicated portal renderers so the through-portal view recompiles
 * too — without this, the portal view keeps stale geometry and stale baked light (black patches).
 * All dirty paths (setSectionDirty, setSectionRangeDirty, setSectionDirtyWithNeighbors) funnel
 * into the private (IIIZ) overload targeted here.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererDirtyMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), require = 0)
    private void wormhole$mirrorSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().levelRenderer) {
            PortalRenderer.mirrorSectionDirty(sectionX, sectionY, sectionZ, playerChanged);
        }
    }
}
