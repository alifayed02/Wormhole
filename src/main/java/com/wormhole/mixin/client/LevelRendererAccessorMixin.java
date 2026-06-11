package com.wormhole.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessors for driving the dedicated portal renderer's culling and render state. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessorMixin {
    @Accessor("viewArea")
    ViewArea wormhole$getViewArea();

    @Accessor("visibleSections")
    ObjectArrayList<RenderSection> wormhole$getVisibleSections();

    @Accessor("levelRenderState")
    LevelRenderState wormhole$getLevelRenderState();

    @Accessor("levelRenderState")
    @Mutable
    void wormhole$setLevelRenderState(LevelRenderState state);
}
