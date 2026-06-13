package com.wormhole.mixin.client;

import com.wormhole.client.ClientPortalTeleport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses the vanilla dimension-change loading screen ({@code LevelLoadingScreen}, shown via
 * {@code Minecraft.setScreenAndShow} inside {@code startWaitingForNewLevel}) while a wormhole
 * crossing is in flight, so cross-dimensional traversal stays seamless instead of flashing the
 * portal/loading screen. Only that one call is redirected; the level swap itself is untouched, and
 * normal joins/respawns (no crossing) show the screen as usual.
 */
@Mixin(ClientPacketListener.class)
public abstract class CrossDimScreenMixin {
    @Redirect(
        method = "startWaitingForNewLevel",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"),
        require = 0)
    private void wormhole$skipLoadingScreenDuringCrossing(Minecraft mc, Screen screen) {
        if (ClientPortalTeleport.suppressDimensionScreen()) {
            return; // seamless wormhole crossing — don't show the dimension loading screen
        }
        mc.setScreenAndShow(screen);
    }
}
