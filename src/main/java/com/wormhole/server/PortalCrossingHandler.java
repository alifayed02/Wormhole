package com.wormhole.server;

import com.wormhole.Wormhole;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalManager;
import com.wormhole.portal.PortalPair;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * Applies a client-predicted portal crossing authoritatively on the server.
 */
public final class PortalCrossingHandler {
    private static final double MAX_VALIDATE_DISTANCE = 8.0;
    /** Debug: log accept/reject decisions. */
    public static boolean DEBUG = true;

    private PortalCrossingHandler() {
    }

    public static void onClientCrossed(ServerPlayer player, UUID pairId, boolean fromEndA) {
        PortalPair pair = PortalManager.get(player.level().getServer()).byId(pairId);
        if (pair == null || !pair.isLinked()) {
            debug("REJECT: unknown or unlinked pair " + pairId);
            return;
        }
        PortalEnd src = fromEndA ? pair.getA() : pair.getB();
        if (!player.level().dimension().equals(src.getDimension())) {
            debug("REJECT: player not in portal dimension");
            return;
        }
        double dist = player.position().distanceTo(src.getCenter());
        if (dist > MAX_VALIDATE_DISTANCE) {
            debug(String.format("REJECT dist=%.2f>%.1f serverPos=%.1f,%.1f,%.1f",
                dist, MAX_VALIDATE_DISTANCE, player.getX(), player.getY(), player.getZ()));
            return;
        }
        Vec3 destPos = pair.transformTeleportPosition(src, player.position());
        Vec3 destVel = pair.transformVelocity(src, player.getDeltaMovement());
        debug(String.format("ACCEPT dist=%.2f -> tp %.1f,%.1f,%.1f", dist, destPos.x, destPos.y, destPos.z));

        // The client already predicted rotation + velocity correctly; mark them RELATIVE (with zero
        // delta) so the authoritative teleport only corrects absolute POSITION and does not overwrite
        // the client's velocity (which caused a momentary stop) or wrap its yaw.
        Set<Relative> relatives = Relative.union(Relative.ROTATION, Relative.DELTA);
        player.teleportTo(player.level(), destPos.x, destPos.y, destPos.z, relatives, 0.0F, 0.0F, false);
        player.setDeltaMovement(destVel);
    }

    private static void debug(String msg) {
        if (DEBUG) {
            Wormhole.LOGGER.info("[wh-dbg-srv] {}", msg);
        }
    }
}
