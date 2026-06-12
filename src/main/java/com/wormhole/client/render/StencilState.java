package com.wormhole.client.render;

/** Shared stencil bookkeeping written by the GL mixins and read by the portal renderer. */
public final class StencilState {
    /** The game's main-level FBO id, captured once it is reattached with a depth-stencil texture. */
    public static int gameFboId = 0;
    /** The most recently bound draw framebuffer (so we can clear its stencil region). */
    public static int lastBoundFbo = 0;

    private StencilState() {
    }
}
