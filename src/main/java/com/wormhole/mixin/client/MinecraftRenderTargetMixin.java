package com.wormhole.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets us redirect the main render target to the portal FBO during the portal pass. */
@Mixin(Minecraft.class)
public interface MinecraftRenderTargetMixin {
    @Accessor("mainRenderTarget")
    @Mutable
    void wormhole$setMainRenderTarget(RenderTarget target);
}
