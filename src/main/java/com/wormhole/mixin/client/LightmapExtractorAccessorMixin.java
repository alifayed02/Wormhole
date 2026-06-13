package com.wormhole.mixin.client;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets us force the lightmap to recompute on demand: {@code extract} only does work when
 * {@code needsUpdate} is set (normally once per tick), so a mid-frame cross-dimensional capture sets
 * it true to re-extract the lightmap for the remote dimension's environment.
 */
@Mixin(LightmapRenderStateExtractor.class)
public interface LightmapExtractorAccessorMixin {
    @Accessor("needsUpdate")
    @Mutable
    void wormhole$setNeedsUpdate(boolean needsUpdate);
}
