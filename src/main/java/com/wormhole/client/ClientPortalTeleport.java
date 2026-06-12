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
 * EVERY portal volume. The translation teleport lands you inside the destination volume at the
 * same relative depth, moving forward; suppression holds while you traverse it and exit the far
 * side.
 */
public final class ClientPortalTeleport {
    private static boolean justTeleported;

    private ClientPortalTeleport() {
    }

    /** Debug ({@code [wh-view]}): whether re-crossing is currently suppressed. */
    public static boolean isSuppressed() {
        return justTeleported;
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

        WormholeDebug.tickSample(player.position(), player.getDeltaMovement(), player.getYRot(),
            isInAnyPortal(player, pairs), justTeleported);

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
        Vec3 srcVel = player.getDeltaMovement();
        float srcYaw = player.getYRot();
        Vec3 destPos = pair.transformTeleportPosition(src, srcPos);
        Vec3 destVel = pair.transformVelocity(src, srcVel);
        float destYaw = pair.transformYaw(src, srcYaw);
        float destPitch = player.getXRot();

        player.setPos(destPos.x, destPos.y, destPos.z);
        player.setDeltaMovement(destVel);
        player.setYRot(destYaw);
        player.setXRot(destPitch);

        justTeleported = true;
        // Carries the client's source position + predicted destination so the server can log how
        // far its own (possibly stale-position) computation diverges — see [wh-srv] in the log.
        ClientPlayNetworking.send(new ClientCrossedPayload(pair.getId(), isEndA, srcPos, destPos));

        WormholeDebug.crossing(src, pair.linkFor(src), isEndA,
            srcPos, destPos, srcVel, destVel, srcYaw, destYaw);
    }
}
