package com.wormhole.server;

import com.wormhole.Wormhole;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalManager;
import com.wormhole.portal.PortalPair;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * Applies a client-predicted portal crossing authoritatively on the server.
 *
 * <p>Instrumented for the bounce investigation ({@code [wh-srv]} log tag): logs how far the
 * server's view of the player lags the client's actual crossing position, and how far the
 * server-computed destination diverges from the client's prediction. The server's
 * {@code teleportTo} sends an absolute position packet — any {@code destMismatch} here becomes
 * a client-side snap of the same magnitude (see {@code [wh-corr]} on the client).
 */
public final class PortalCrossingHandler {
    private static final double MAX_VALIDATE_DISTANCE = 8.0;
    /** Debug: log accept/reject decisions. */
    public static boolean DEBUG = true;

    private PortalCrossingHandler() {
    }

    public static void onClientCrossed(ServerPlayer player, UUID pairId, boolean fromEndA,
                                       Vec3 clientSrcPos, Vec3 clientPredictedDest) {
        PortalPair pair = PortalManager.get(player.level().getServer()).byId(pairId);
        if (pair == null || !pair.isLinked()) {
            debug("REJECT: unknown or unlinked pair " + pairId);
            return;
        }
        // The client only triggers on the end in its current dimension, so fromEndA identifies the
        // local (source) end; the partner is the destination, possibly in another dimension.
        PortalEnd src = fromEndA ? pair.getA() : pair.getB();
        PortalEnd dest = fromEndA ? pair.getB() : pair.getA();
        if (!player.level().dimension().equals(src.getDimension())) {
            debug("REJECT: player not in portal dimension");
            return;
        }
        ServerLevel destLevel = player.level().getServer().getLevel(dest.getDimension());
        if (destLevel == null) {
            debug("REJECT: destination dimension not loaded " + dest.getDimension().identifier());
            return;
        }
        Vec3 serverPos = player.position();
        double dist = serverPos.distanceTo(src.getCenter());
        // The client now crosses at the mouth CENTRE, so the server's (lagged) position can trail by
        // up to ~the mouth radius on a fast crossing of a large mouth — scale the budget accordingly,
        // never below the 8-block floor for small mouths.
        double budget = Math.max(MAX_VALIDATE_DISTANCE, src.getRadius() + 4.0);
        if (dist > budget) {
            debug(String.format(Locale.ROOT, "REJECT dist=%.2f>%.1f serverPos=%.1f,%.1f,%.1f",
                dist, budget, serverPos.x, serverPos.y, serverPos.z));
            return;
        }
        Vec3 destPos = pair.transformTeleportPosition(src, serverPos);
        Vec3 destVel = pair.transformVelocity(src, player.getDeltaMovement());

        // Bounce instrumentation: posLag = how stale the server's player position is relative to
        // the position the client actually crossed at; destMismatch = the resulting snap the
        // client will receive from the authoritative teleport packet.
        double posLag = serverPos.distanceTo(clientSrcPos);
        double destMismatch = destPos.distanceTo(clientPredictedDest);
        debug(String.format(Locale.ROOT,
            "ACCEPT pair=%s end%s serverPos=(%.4f,%.4f,%.4f) clientSrcPos=(%.4f,%.4f,%.4f) posLag=%.4f "
                + "serverDest=(%.4f,%.4f,%.4f) clientDest=(%.4f,%.4f,%.4f) destMismatch=%.4f",
            shortId(pairId), fromEndA ? "A" : "B",
            serverPos.x, serverPos.y, serverPos.z,
            clientSrcPos.x, clientSrcPos.y, clientSrcPos.z, posLag,
            destPos.x, destPos.y, destPos.z,
            clientPredictedDest.x, clientPredictedDest.y, clientPredictedDest.z, destMismatch));

        if (!Wormhole.TELEPORT_ENABLED) {
            return;
        }
        // The client already predicted rotation + velocity correctly; mark them RELATIVE (with zero
        // delta) so the authoritative teleport only corrects absolute POSITION and does not overwrite
        // the client's velocity (which caused a momentary stop) or wrap its yaw.
        Set<Relative> relatives = Relative.union(Relative.ROTATION, Relative.DELTA);
        player.teleportTo(destLevel, destPos.x, destPos.y, destPos.z, relatives, 0.0F, 0.0F, false);
        player.setDeltaMovement(destVel);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static void debug(String msg) {
        if (DEBUG) {
            Wormhole.LOGGER.info("[wh-srv] {}", msg);
        }
    }
}
