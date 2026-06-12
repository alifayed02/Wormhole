package com.wormhole.client;

import com.wormhole.Wormhole;
import com.wormhole.net.WormholePayloads.ClientCrossedPayload;
import com.wormhole.portal.MouthCrossing;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Seamless client-side teleport for spherical mouths.
 *
 * <p><b>Trigger:</b> once per client tick, the feet SEGMENT (last tick -> this tick) is tested
 * against each linked mouth; a crossing fires when the segment passes through the mouth's throat
 * centre — the diametral plane through the centre, within the radius (see {@link MouthCrossing}).
 * This is the faithful {@code ℓ = 0} crossing point and is tunnelling-safe (a fast one-tick pass
 * still fires). Entry is omnidirectional — any approach direction works, matching a wormhole mouth.
 *
 * <p><b>Crossing:</b> a pure translation (see {@link PortalPair#transformTeleportPosition}) lands
 * the player at the same offset inside the destination sphere, moving the same way.
 *
 * <p><b>Anti-ping-pong:</b> after a crossing, no further crossing fires until the player has left
 * EVERY mouth volume. The player lands inside the destination sphere and traverses out of it
 * under their carried momentum while suppression holds.
 */
public final class ClientPortalTeleport {
    private static boolean justTeleported;
    /** Previous tick's feet position; null = no segment to test yet (just connected / teleported). */
    private static Vec3 lastFeet;

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
            lastFeet = null;
            return;
        }
        Vec3 feet = player.position();
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(mc.level.dimension());
        if (pairs.isEmpty()) {
            justTeleported = false;
            lastFeet = null;
            return;
        }

        WormholeDebug.tickSample(feet, player.getDeltaMovement(), player.getYRot(),
            isInAnyMouth(feet, pairs), justTeleported);

        // Advance the segment: prev -> feet. Always track lastFeet, even while suppressed, so the
        // first post-suppression segment doesn't span the whole exit-walk.
        Vec3 prev = lastFeet;
        lastFeet = feet;

        if (justTeleported) {
            if (!isInAnyMouth(feet, pairs)) {
                justTeleported = false;
            }
            return;
        }
        if (prev == null) {
            return; // first sampled tick — no segment yet
        }

        for (PortalPair pair : pairs) {
            if (segmentCrossesCenter(prev, feet, pair.getA())) {
                performCrossing(player, pair, pair.getA(), true);
                return;
            }
            if (segmentCrossesCenter(prev, feet, pair.getB())) {
                performCrossing(player, pair, pair.getB(), false);
                return;
            }
        }
    }

    /** True if the feet segment {@code prev -> feet} passes through this mouth's throat centre. */
    private static boolean segmentCrossesCenter(Vec3 prev, Vec3 feet, PortalEnd end) {
        Vec3 c = end.getCenter();
        return MouthCrossing.segmentCrossesCenter(
            prev.x, prev.y, prev.z, feet.x, feet.y, feet.z, c.x, c.y, c.z, end.getRadius());
    }

    private static boolean isInAnyMouth(Vec3 feet, List<PortalPair> pairs) {
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

        // Entry is always detected; the actual move (and the server report) only happen when the
        // teleport effect is enabled. Suppression is set either way so a single entry fires once.
        if (Wormhole.TELEPORT_ENABLED) {
            player.setPos(destPos.x, destPos.y, destPos.z);
            player.setDeltaMovement(destVel);
            player.setYRot(destYaw);
            player.setXRot(destPitch);
            // Carries the client's source position + predicted destination so the server can log
            // how far its own (possibly stale-position) computation diverges — see [wh-srv].
            ClientPlayNetworking.send(new ClientCrossedPayload(pair.getId(), isEndA, srcPos, destPos));
        }
        justTeleported = true;
        lastFeet = destPos;

        WormholeDebug.crossing(src, pair.linkFor(src), isEndA,
            srcPos, destPos, srcVel, destVel, srcYaw, destYaw);
    }
}
