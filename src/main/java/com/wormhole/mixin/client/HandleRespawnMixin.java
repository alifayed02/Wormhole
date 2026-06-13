package com.wormhole.mixin.client;

import com.wormhole.Wormhole;
import com.wormhole.client.render.remote.RemoteDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientLevel.ClientLevelData;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-first seamless cross-dimensional swap: when crossing into a dimension we have a streamed
 * remote level for, substitute that promoted level + its dedicated renderer for the vanilla rebuild
 * in {@code handleRespawn}, so there's no reload/blank. Falls back to vanilla when no remote level is
 * cached or {@link Wormhole#SEAMLESS_CROSSDIM} is off. v1 = forward promote (no demote yet). Ports
 * SeamlessPortals' {@code HandleRespawnMixin}.
 */
@Mixin(ClientPacketListener.class)
public abstract class HandleRespawnMixin {
    private boolean wormhole$seamless;
    private RemoteDimensions.Promotion wormhole$pending;
    private boolean wormhole$promoted;

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void wormhole$detect(ClientboundRespawnPacket packet, CallbackInfo ci) {
        wormhole$seamless = false;
        wormhole$pending = null;
        wormhole$promoted = false;
        if (!Wormhole.SEAMLESS_CROSSDIM) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ResourceKey<Level> dest = packet.commonPlayerSpawnInfo().dimension();
        if (!dest.equals(mc.level.dimension()) && RemoteDimensions.levelFor(dest) != null) {
            wormhole$seamless = true;
        }
    }

    @Redirect(method = "handleRespawn", at = @At(value = "NEW",
        target = "(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/Holder;IILnet/minecraft/client/renderer/LevelRenderer;ZJI)Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel wormhole$redirectNewLevel(ClientPacketListener connection, ClientLevelData data,
            ResourceKey<Level> dimension, Holder<DimensionType> dimType, int chunkRadius, int simDist,
            LevelRenderer renderer, boolean isDebug, long seed, int seaLevel) {
        if (wormhole$seamless) {
            LevelRenderState shared = Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState;
            RemoteDimensions.Promotion p = RemoteDimensions.promote(dimension, shared);
            if (p != null) {
                wormhole$pending = p;
                return p.level();
            }
        }
        return new ClientLevel(connection, data, dimension, dimType, chunkRadius, simDist, renderer, isDebug, seed, seaLevel);
    }

    @Redirect(method = "handleRespawn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V"))
    private void wormhole$redirectSetLevel(Minecraft mc, ClientLevel level) {
        RemoteDimensions.Promotion p = wormhole$pending;
        if (wormhole$seamless && p != null) {
            wormhole$pending = null;
            wormhole$promoted = true;
            ((MinecraftAccessorMixin) mc).wormhole$setLevelRenderer(p.renderer());
            ((MinecraftAccessorMixin) mc).wormhole$setLevel(level);
            mc.particleEngine.setLevel(level);
            mc.gameRenderer.setLevel(level);
            Wormhole.LOGGER.info("[crossdim] installed promoted level {} as live", level.dimension().identifier());
        } else {
            mc.setLevel(level);
        }
    }
}
