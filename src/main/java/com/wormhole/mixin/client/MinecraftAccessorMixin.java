package com.wormhole.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets us swap the active level renderer to the dedicated portal renderer during the portal pass. */
@Mixin(Minecraft.class)
public interface MinecraftAccessorMixin {
    @Accessor("levelRenderer")
    LevelRenderer wormhole$getLevelRenderer();

    @Accessor("levelRenderer")
    @Mutable
    void wormhole$setLevelRenderer(LevelRenderer renderer);
}
