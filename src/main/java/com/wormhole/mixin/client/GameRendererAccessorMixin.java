package com.wormhole.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessors for swapping the active camera and reading the fog renderer during the portal pass,
 *  plus the lightmap + its extractor so a cross-dimensional capture can recompute the lightmap for
 *  the remote dimension (else the through-view is lit by the wrong dimension's light curve). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessorMixin {
    @Accessor("fogRenderer")
    FogRenderer wormhole$getFogRenderer();

    @Accessor("mainCamera")
    Camera wormhole$getMainCamera();

    @Accessor("mainCamera")
    @Mutable
    void wormhole$setMainCamera(Camera camera);

    @Accessor("lightmap")
    Lightmap wormhole$getLightmap();

    @Accessor("lightmapRenderStateExtractor")
    LightmapRenderStateExtractor wormhole$getLightmapExtractor();
}
