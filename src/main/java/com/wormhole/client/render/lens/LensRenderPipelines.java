package com.wormhole.client.render.lens;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline.Snippet;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.wormhole.Wormhole;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Custom render pipeline for drawing the wormhole mouth spheres with our own GLSL shader.
 * {@code RenderPipelines.register} is non-public, so we reach it by reflection (same approach as
 * {@code PortalRenderTypes}). The pipeline is depth-tested (occludes / is occluded by the world)
 * and uses the mod-namespaced shader {@code wormhole:core/wormhole_sphere}, which samples the
 * destination view ({@link DestinationCapture}) as a screen-aligned window.
 */
public final class LensRenderPipelines {
    private static RenderPipeline spherePipeline;
    private static RenderPipeline aroundPipeline;
    private static RenderPipeline crossingPipeline;

    private LensRenderPipelines() {
    }

    public static RenderPipeline sphere() {
        return spherePipeline;
    }

    public static RenderPipeline around() {
        return aroundPipeline;
    }

    public static RenderPipeline crossing() {
        return crossingPipeline;
    }

    /** Forces the static initializer to run (and log) early. */
    public static void init() {
    }

    static {
        try {
            Method register = RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
            register.setAccessible(true);

            RenderPipeline pipeline = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/lens_sphere")
                .withVertexShader(Identifier.fromNamespaceAndPath("wormhole", "core/wormhole_sphere"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("wormhole", "core/wormhole_sphere"))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("CubeBasis", UniformType.UNIFORM_BUFFER)
                .withUniform("LensParams", UniformType.UNIFORM_BUFFER)
                .withSampler("Face0")
                .withSampler("Face1")
                .withSampler("Face2")
                .withSampler("Face3")
                .withSampler("Face4")
                .withSampler("Face5")
                .withSampler("DeflectionLut")
                .withVertexFormat(DefaultVertexFormat.POSITION, Mode.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withCull(false)
                .build();
            spherePipeline = (RenderPipeline) register.invoke(null, pipeline);

            // Around-the-mouth lensing: a billboard over the influence disc, blended over the scene.
            // Depth-TESTED (closer world occludes it) but NO depth write; fragments outside [rho, B_MAX]
            // discard, and the alpha fades to 0 at the edge so it dissolves into the surrounding scene.
            RenderPipeline aroundP = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/lens_around")
                .withVertexShader(Identifier.fromNamespaceAndPath("wormhole", "core/wormhole_around"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("wormhole", "core/wormhole_around"))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("LensParams", UniformType.UNIFORM_BUFFER)
                .withSampler("SceneColor")
                .withSampler("DeflectionLut")
                .withVertexFormat(DefaultVertexFormat.POSITION, Mode.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
            aroundPipeline = (RenderPipeline) register.invoke(null, aroundP);

            // Full-screen crossing warp: a screen-filling quad running the geodesic per pixel,
            // crossfaded over the frame by intensity. NO depth test (overlay), TRANSLUCENT blend.
            RenderPipeline crossingP = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/lens_crossing")
                .withVertexShader(Identifier.fromNamespaceAndPath("wormhole", "core/wormhole_crossing"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("wormhole", "core/wormhole_crossing"))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("CubeBasis", UniformType.UNIFORM_BUFFER)
                .withUniform("LensParams", UniformType.UNIFORM_BUFFER)
                .withUniform("CrossingParams", UniformType.UNIFORM_BUFFER)
                .withSampler("Face0")
                .withSampler("Face1")
                .withSampler("Face2")
                .withSampler("Face3")
                .withSampler("Face4")
                .withSampler("Face5")
                .withSampler("Src0")
                .withSampler("Src1")
                .withSampler("Src2")
                .withSampler("Src3")
                .withSampler("Src4")
                .withSampler("Src5")
                .withSampler("DeflectionLut")
                .withSampler("AroundLut")
                .withVertexFormat(DefaultVertexFormat.POSITION, Mode.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
            crossingPipeline = (RenderPipeline) register.invoke(null, crossingP);
            Wormhole.LOGGER.info("[lens] sphere + around + crossing pipelines registered");
        } catch (Exception e) {
            Wormhole.LOGGER.error("[lens] failed to create lens pipelines", e);
        }
    }
}
