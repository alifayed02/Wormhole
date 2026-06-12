package com.wormhole.client;

import com.wormhole.Wormhole;
import com.wormhole.portal.PortalEnd;
import java.util.ArrayDeque;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * Structured debug tracing for the two open bugs (crossing bounce, portal lighting).
 * Everything goes to the game log ({@code logs/latest.log}); grep tags:
 *
 * <pre>
 *   [wh-cross]  crossing event + per-tick position/velocity trace around it (client)
 *   [wh-corr]   server position packets applied to the local player (the potential "snap")
 *   [wh-srv]    server accept/reject + client-vs-server transform mismatch
 *   [wh-light]  per-portal light levels and dedicated-renderer state
 * </pre>
 *
 * <p>Bounce diagnosis: hypothesis H1 (server correction snap) shows up as a {@code [wh-corr]}
 * line with a non-trivial {@code snapDist} a few ticks after CROSS, and/or a discontinuity in
 * the {@code [wh-cross] +N} positions that velocity doesn't explain. Hypothesis H2 (transform
 * semantics: momentum opposing facing) shows up as a negative {@code forwardDotVel} on the
 * CROSS line with smooth post-ticks.
 */
public final class WormholeDebug {
    public static boolean ENABLED = true;

    /** Client tick counter (bumped once per sampled tick) so all client logs share an index. */
    public static long tick = 0;

    private static final int PRE_TICKS = 5;
    private static final int POST_TICKS = 15;

    /** Rolling buffer of the most recent pre-crossing tick samples. */
    private static final ArrayDeque<String> PRE_BUFFER = new ArrayDeque<>(PRE_TICKS + 1);
    /** While > 0, tick samples are logged live (we are tracing the aftermath of a crossing). */
    private static int postTicksLeft = 0;
    private static long crossTick = -1;

    private WormholeDebug() {
    }

    /** Free-form debug line (legacy call sites). */
    public static void log(String msg) {
        if (ENABLED) {
            Wormhole.LOGGER.info("[wh-dbg] {}", msg);
        }
    }

    /**
     * One sample per client tick (called at tick start, before the crossing check, so the
     * crossing dump's "pre" lines reflect the state the trigger saw).
     */
    public static void tickSample(Vec3 pos, Vec3 vel, float yaw, boolean inPortal, boolean suppressed) {
        if (!ENABLED) {
            return;
        }
        tick++;
        String line = String.format(Locale.ROOT,
            "T%d pos=(%.4f,%.4f,%.4f) vel=(%.4f,%.4f,%.4f) |v|=%.4f yaw=%.1f inPortal=%b suppressed=%b",
            tick, pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, vel.length(), yaw, inPortal, suppressed);
        if (postTicksLeft > 0) {
            Wormhole.LOGGER.info("[wh-cross] +{} {}", tick - crossTick, line);
            if (--postTicksLeft == 0) {
                Wormhole.LOGGER.info("[wh-cross] trace end (T{})", tick);
            }
        } else {
            PRE_BUFFER.addLast(line);
            if (PRE_BUFFER.size() > PRE_TICKS) {
                PRE_BUFFER.removeFirst();
            }
        }
    }

    /** Full state dump at the moment of a client-predicted crossing. */
    public static void crossing(PortalEnd src, PortalEnd dest, boolean isEndA,
                                Vec3 srcPos, Vec3 destPos, Vec3 srcVel, Vec3 destVel,
                                float srcYaw, float destYaw) {
        if (!ENABLED) {
            return;
        }
        crossTick = tick;
        postTicksLeft = POST_TICKS;
        for (String line : PRE_BUFFER) {
            Wormhole.LOGGER.info("[wh-cross] pre {}", line);
        }
        PRE_BUFFER.clear();

        // Does the post-cross momentum agree with the post-cross facing? Negative = the player
        // moves backward relative to where they look -> transform-semantics suspect (H2).
        double yawRad = Math.toRadians(destYaw);
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
        double forwardDotVel = forward.x * destVel.x + forward.z * destVel.z;

        Wormhole.LOGGER.info(String.format(Locale.ROOT,
            "[wh-cross] CROSS T%d end%s srcAxis=%s destAxis=%s srcNormal=%s destNormal=%s "
                + "pos (%.4f,%.4f,%.4f)->(%.4f,%.4f,%.4f) vel (%.4f,%.4f,%.4f)->(%.4f,%.4f,%.4f) "
                + "|v| %.4f->%.4f yaw %.1f->%.1f forwardDotVel=%.4f",
            crossTick, isEndA ? "A" : "B", src.getAxis(), dest.getAxis(), src.getNormal(), dest.getNormal(),
            srcPos.x, srcPos.y, srcPos.z, destPos.x, destPos.y, destPos.z,
            srcVel.x, srcVel.y, srcVel.z, destVel.x, destVel.y, destVel.z,
            srcVel.length(), destVel.length(), srcYaw, destYaw, forwardDotVel));
    }

    /** A server position packet is about to be applied to the local player. */
    public static void serverCorrection(Vec3 packetPos, Vec3 packetDelta, String relatives, Vec3 clientPos) {
        if (!ENABLED) {
            return;
        }
        double snap = packetPos.distanceTo(clientPos);
        String sinceCross = crossTick < 0 ? "?" : String.valueOf(tick - crossTick);
        Wormhole.LOGGER.info(String.format(Locale.ROOT,
            "[wh-corr] T%d (cross+%s) packetPos=(%.4f,%.4f,%.4f) clientPos=(%.4f,%.4f,%.4f) "
                + "snapDist=%.4f packetDelta=(%.4f,%.4f,%.4f) relatives=%s",
            tick, sinceCross, packetPos.x, packetPos.y, packetPos.z,
            clientPos.x, clientPos.y, clientPos.z, snap,
            packetDelta.x, packetDelta.y, packetDelta.z, relatives));
    }
}
