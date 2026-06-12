package com.wormhole.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes {@link Camera}'s protected position/rotation setters and cull-frustum field so we can
 *  build a virtual camera for the portal view. */
@Mixin(Camera.class)
public interface CameraInvokerMixin {
    @Invoker("setPosition")
    void wormhole$setPosition(Vec3 position);

    @Invoker("setRotation")
    void wormhole$setRotation(float yRot, float xRot);

    @Accessor("cullFrustum")
    void wormhole$setCullFrustum(Frustum frustum);

    @Accessor("initialized")
    void wormhole$setInitialized(boolean initialized);
}
