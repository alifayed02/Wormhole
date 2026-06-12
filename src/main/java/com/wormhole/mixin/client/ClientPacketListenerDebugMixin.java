package com.wormhole.mixin.client;

import com.wormhole.client.WormholeDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Debug instrumentation only: logs every server position correction applied to the local player
 * ({@code [wh-corr]}), so a crossing "bounce" can be attributed (or not) to the authoritative
 * teleport snap. No behavior change.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerDebugMixin {
    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 0)
    private void wormhole$logServerCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // handleMovePlayer is first invoked on the network thread (then re-dispatched); only log
        // the main-thread invocation, where reading the player position is safe.
        if (!mc.isSameThread() || mc.player == null) {
            return;
        }
        WormholeDebug.serverCorrection(packet.change().position(), packet.change().deltaMovement(),
            packet.relatives().toString(), mc.player.position());
    }
}
