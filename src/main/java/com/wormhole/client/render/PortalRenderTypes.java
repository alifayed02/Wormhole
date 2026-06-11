package com.wormhole.client.render;

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
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Custom render pipelines for the portal stencil passes. {@code RenderPipelines.register} and
 * {@code RenderType.create} are non-public, so we reach them by reflection (as the reference does).
 *
 * <p>The stencil passes write no color ({@link ColorTargetState} with an empty write mask) and rely
 * on the raw GL stencil state set by {@link StencilPortalRenderer}; the composite pass samples the
 * portal FBO into the masked region.
 */
public final class PortalRenderTypes {
    private static RenderType portalStencilOnly;
    private static RenderType portalStencilWithDepth;
    private static RenderType portalNoDepthColor;
    private static RenderType portalDepthClear;
    private static RenderType portalFboComposite;
    private static RenderType portalBorder;

    private PortalRenderTypes() {
    }

    public static RenderType portalBorder() {
        return portalBorder;
    }

    public static RenderType portalStencilOnly() {
        return portalStencilOnly;
    }

    public static RenderType portalStencilWithDepth() {
        return portalStencilWithDepth;
    }

    public static RenderType portalNoDepthColor() {
        return portalNoDepthColor;
    }

    public static RenderType portalDepthClear() {
        return portalDepthClear;
    }

    public static RenderType portalFboComposite() {
        return portalFboComposite;
    }

    /** Forces the static initializer to run (and log) early. */
    public static void init() {
    }

    static {
        try {
            Method registerMethod = RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
            registerMethod.setAccessible(true);
            Method createMethod = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            createMethod.setAccessible(true);

            RenderPipeline stencilPipeline = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/portal_stencil")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), 0))
                .withDepthStencilState(Optional.empty())
                .withCull(false)
                .build();
            stencilPipeline = (RenderPipeline) registerMethod.invoke(null, stencilPipeline);
            portalStencilOnly = (RenderType) createMethod.invoke(null, "wormhole_stencil",
                RenderSetup.builder(stencilPipeline).createRenderSetup());

            RenderPipeline stencilDepthPipeline = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/portal_stencil_depth")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), 0))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withCull(false)
                .build();
            stencilDepthPipeline = (RenderPipeline) registerMethod.invoke(null, stencilDepthPipeline);
            portalStencilWithDepth = (RenderType) createMethod.invoke(null, "wormhole_stencil_depth",
                RenderSetup.builder(stencilDepthPipeline).createRenderSetup());

            RenderPipeline noDepthPipeline = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/portal_nodepth_color")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
                .withDepthStencilState(Optional.empty())
                .withCull(false)
                .build();
            noDepthPipeline = (RenderPipeline) registerMethod.invoke(null, noDepthPipeline);
            portalNoDepthColor = (RenderType) createMethod.invoke(null, "wormhole_nodepth_color",
                RenderSetup.builder(noDepthPipeline).createRenderSetup());

            RenderPipeline depthClearPipeline = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/portal_depth_clear")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), 0))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withCull(false)
                .build();
            depthClearPipeline = (RenderPipeline) registerMethod.invoke(null, depthClearPipeline);
            portalDepthClear = (RenderType) createMethod.invoke(null, "wormhole_depth_clear",
                RenderSetup.builder(depthClearPipeline).createRenderSetup());

            RenderPipeline fboCompositePipeline = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/portal_fbo_composite")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withVertexShader("core/position_tex")
                .withFragmentShader("core/position_tex")
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX, Mode.QUADS)
                .withDepthStencilState(Optional.empty())
                .withCull(false)
                .build();
            fboCompositePipeline = (RenderPipeline) registerMethod.invoke(null, fboCompositePipeline);
            portalFboComposite = (RenderType) createMethod.invoke(null, "wormhole_fbo_composite",
                RenderSetup.builder(fboCompositePipeline).createRenderSetup());

            // Opaque, depth-tested colored geometry for the visible portal frame/border.
            RenderPipeline borderPipeline = RenderPipeline.builder(new Snippet[0])
                .withLocation("wormhole/pipeline/portal_border")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, Mode.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withCull(false)
                .build();
            borderPipeline = (RenderPipeline) registerMethod.invoke(null, borderPipeline);
            portalBorder = (RenderType) createMethod.invoke(null, "wormhole_border",
                RenderSetup.builder(borderPipeline).createRenderSetup());

            Wormhole.LOGGER.info("[render] Portal render types created");
        } catch (Exception e) {
            Wormhole.LOGGER.error("[render] Failed to create portal render types; falling back to debugQuads", e);
            portalStencilOnly = RenderTypes.debugQuads();
            portalStencilWithDepth = RenderTypes.debugQuads();
            portalNoDepthColor = RenderTypes.debugQuads();
            portalDepthClear = RenderTypes.debugQuads();
            portalFboComposite = RenderTypes.debugQuads();
            portalBorder = RenderTypes.debugQuads();
        }
    }
}
