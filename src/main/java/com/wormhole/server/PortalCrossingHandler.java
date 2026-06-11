package com.wormhole.server;

import com.wormhole.Wormhole;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import com.wormhole.portal.PortalRegistry;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * Applies a client-predicted portal crossing authoritatively on the server, with debug feedback sent
 * to the player's chat so the client/server crossing sequence is visible in-game.
 */
public final class PortalCrossingHandler {
    private static final double MAX_VALIDATE_DISTANCE = 8.0;
    /** Debug: report accept/reject decisions to the player's chat. */
    public static boolean DEBUG = true;

    private PortalCrossingHandler() {
    }

    public static void onClientCrossed(ServerPlayer player, UUID pairId, boolean fromEndA) {
        PortalPair pair = findPair(pairId);
        if (pair == null) {
            debug(player, "§c[S] REJECT: unknown pair " + pairId);
            return;
        }
        PortalEnd src = fromEndA ? pair.getA() : pair.getB();
        double dist = player.position().distanceTo(src.getCenter());
        if (dist > MAX_VALIDATE_DISTANCE) {
            debug(player, String.format(
                "§c[S] REJECT dist=%.2f>%.1f serverPos=%.1f,%.1f,%.1f",
                dist, MAX_VALIDATE_DISTANCE, player.getX(), player.getY(), player.getZ()));
            return;
        }
        Vec3 dest = pair.transformTeleportPosition(src, player.position());
        debug(player, String.format(
            "§a[S] ACCEPT dist=%.2f serverPos=%.1f,%.1f,%.1f -> tp %.1f,%.1f,%.1f",
            dist, player.getX(), player.getY(), player.getZ(), dest.x, dest.y, dest.z));
        performCrossing(player, pair, src);
    }

    private static PortalPair findPair(UUID id) {
        for (PortalPair pair : PortalRegistry.serverPairs()) {
            if (pair.getId().equals(id)) {
                return pair;
            }
        }
        return null;
    }

    private static void performCrossing(ServerPlayer player, PortalPair pair, PortalEnd source) {
        Vec3 destPos = pair.transformTeleportPosition(source, player.position());
        Vec3 destVel = pair.transformVelocity(source, player.getDeltaMovement());

        // The client already predicted rotation + velocity correctly; mark them RELATIVE (with zero
        // delta) so the authoritative teleport only corrects absolute POSITION and does not overwrite
        // the client's velocity (which caused a momentary stop) or wrap its yaw. Only the server's
        // position is enforced.
        Set<Relative> relatives = Relative.union(Relative.ROTATION, Relative.DELTA);
        player.teleportTo(player.level(), destPos.x, destPos.y, destPos.z, relatives, 0.0F, 0.0F, false);
        // Keep the server's own velocity in sync (does not disturb the client thanks to DELTA-relative).
        player.setDeltaMovement(destVel);
    }

    private static void debug(ServerPlayer player, String msg) {
        if (DEBUG) {
            Wormhole.LOGGER.info("[wh-dbg-srv] {}", msg);
        }
    }
}
