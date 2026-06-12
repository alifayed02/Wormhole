package com.wormhole.client;

import com.wormhole.net.WormholePayloads.ClientCrossedPayload;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Seamless client-side teleport, ported from SeamlessPortals' per-tick volume model
 * ({@code LocalPlayerMixin} + {@code SeamlessClientTeleport.performCrossing}, same-dimension branch).
 *
 * <p><b>Trigger:</b> the player's feet are inside a portal end's volume ({@link PortalEnd#containsPoint}),
 * checked once per client tick at tick start (before the player's own movement updates {@code xo},
 * so the render interpolates cleanly from the new position with no smear).
 *
 * <p><b>Anti-ping-pong:</b> after a crossing, no further crossing fires until the player has left
 * EVERY portal volume. This is what makes the depth-negating transform safe (you land on the
 * mirror side, still inside the destination volume, and simply walk out).
 */
public final class ClientPortalTeleport {
    private static boolean justTeleported;

    private ClientPortalTeleport() {
    }

    /** Driven from {@code ClientTickEvents.START_CLIENT_TICK}. */
    public static void onClientTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            justTeleported = false;
            return;
        }
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(mc.level.dimension());
        if (pairs.isEmpty()) {
            justTeleported = false;
            return;
        }

        if (justTeleported) {
            if (!isInAnyPortal(player, pairs)) {
                justTeleported = false;
            }
            return;
        }

        Vec3 feet = player.position();
        for (PortalPair pair : pairs) {
            if (pair.getA().containsPoint(feet)) {
                performCrossing(player, pair, pair.getA(), true);
                return;
            }
            if (pair.getB().containsPoint(feet)) {
                performCrossing(player, pair, pair.getB(), false);
                return;
            }
        }
    }

    private static boolean isInAnyPortal(LocalPlayer player, List<PortalPair> pairs) {
        Vec3 feet = player.position();
        for (PortalPair pair : pairs) {
            if (pair.getA().containsPoint(feet) || pair.getB().containsPoint(feet)) {
                return true;
            }
        }
        return false;
    }

    private static void performCrossing(LocalPlayer player, PortalPair pair, PortalEnd src, boolean isEndA) {
        Vec3 srcPos = player.position();
        Vec3 destPos = pair.transformTeleportPosition(src, srcPos);
        Vec3 destVel = pair.transformVelocity(src, player.getDeltaMovement());
        float destYaw = pair.transformYaw(src, player.getYRot());
        float destPitch = player.getXRot();

        player.setPos(destPos.x, destPos.y, destPos.z);
        player.setDeltaMovement(destVel);
        player.setYRot(destYaw);
        player.setXRot(destPitch);

        justTeleported = true;
        ClientPlayNetworking.send(new ClientCrossedPayload(pair.getId(), isEndA));

        if (WormholeDebug.ENABLED) {
            WormholeDebug.log(String.format(
                "CROSS end%s src=(%.3f,%.3f,%.3f) -> dest=(%.3f,%.3f,%.3f) vel=(%.3f,%.3f,%.3f) yaw=%.1f",
                isEndA ? "A" : "B", srcPos.x, srcPos.y, srcPos.z,
                destPos.x, destPos.y, destPos.z, destVel.x, destVel.y, destVel.z, destYaw));
        }
    }
}
