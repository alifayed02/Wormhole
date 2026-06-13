package com.wormhole.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets us swap the active level renderer to the dedicated portal renderer during the portal pass,
 *  and swap mc.level for the seamless cross-dimensional promote. */
@Mixin(Minecraft.class)
public interface MinecraftAccessorMixin {
    @Accessor("levelRenderer")
    LevelRenderer wormhole$getLevelRenderer();

    @Accessor("levelRenderer")
    @Mutable
    void wormhole$setLevelRenderer(LevelRenderer renderer);

    @Accessor("level")
    @Mutable
    void wormhole$setLevel(ClientLevel level);
}
