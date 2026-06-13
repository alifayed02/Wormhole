package com.wormhole.client;

import com.wormhole.Wormhole;
import com.wormhole.net.WormholePayloads.ClientCrossedPayload;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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
    /** >0 for a short window after a cross-dimensional crossing — suppresses the vanilla dimension
     *  loading screen so the wormhole transition stays seamless (see the ClientPacketListener mixin). */
    private static int dimScreenSuppressTicks;

    private ClientPortalTeleport() {
    }

    /** Debug ({@code [wh-view]}): whether re-crossing is currently suppressed. */
    public static boolean isSuppressed() {
        return justTeleported;
    }

    /** True briefly after a cross-dim crossing: skip the vanilla dimension-change loading screen. */
    public static boolean suppressDimensionScreen() {
        return dimScreenSuppressTicks > 0;
    }

    /** Driven from {@code ClientTickEvents.START_CLIENT_TICK}. */
    public static void onClientTick(Minecraft mc) {
        if (dimScreenSuppressTicks > 0) {
            dimScreenSuppressTicks--;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            justTeleported = false;
            lastFeet = null;
            return;
        }
        Vec3 feet = player.position();
        ResourceKey<Level> dim = mc.level.dimension();
        List<PortalPair> pairs = ClientPortalStore.linkedPairsIn(dim);
        if (pairs.isEmpty()) {
            justTeleported = false;
            lastFeet = null;
            return;
        }

        WormholeDebug.tickSample(feet, player.getDeltaMovement(), player.getYRot(),
            isInAnyMouth(feet, pairs, dim), justTeleported);

        // Advance the segment: prev -> feet. Always track lastFeet, even while suppressed, so the
        // first post-suppression segment doesn't span the whole exit-walk.
        Vec3 prev = lastFeet;
        lastFeet = feet;

        if (justTeleported) {
            if (!isInAnyMouth(feet, pairs, dim)) {
                justTeleported = false;
            }
            return;
        }
        if (prev == null) {
            return; // first sampled tick — no segment yet
        }

        // Surface trigger: teleport the instant the feet contact a local mouth's sphere. The
        // full-screen crossing warp is now the transition, so we no longer wait for the throat
        // centre. Only the end in the player's current dimension is crossable (cross-dim partner
        // lives in another world).
        for (PortalPair pair : pairs) {
            if (pair.getA().getDimension().equals(dim) && pair.getA().containsPoint(feet)) {
                performCrossing(player, pair, pair.getA(), true);
                return;
            }
            if (pair.getB().getDimension().equals(dim) && pair.getB().containsPoint(feet)) {
                performCrossing(player, pair, pair.getB(), false);
                return;
            }
        }
    }

    private static boolean isInAnyMouth(Vec3 feet, List<PortalPair> pairs, ResourceKey<Level> dim) {
        for (PortalPair pair : pairs) {
            if (pair.getA().getDimension().equals(dim) && pair.getA().containsPoint(feet)) {
                return true;
            }
            if (pair.getB().getDimension().equals(dim) && pair.getB().containsPoint(feet)) {
                return true;
            }
        }
        return false;
    }

    private static void performCrossing(LocalPlayer player, PortalPair pair, PortalEnd src, boolean isEndA) {
        Vec3 srcPos = player.position();
        Vec3 srcVel = player.getDeltaMovement();
        float srcYaw = player.getYRot();
        // Emerge OUTSIDE the destination mouth along the look/travel direction (pop out the far side),
        // not at the same offset inside it — so you face the destination world, mouth behind you.
        Vec3 destPos = pair.exitPosition(src, player.getViewVector(1.0F));
        Vec3 destVel = pair.transformVelocity(src, srcVel);
        float destYaw = pair.transformYaw(src, srcYaw);
        float destPitch = player.getXRot();

        boolean crossDim = !pair.linkFor(src).getDimension().equals(src.getDimension());
        // Entry is always detected; the actual move (and the server report) only happen when the
        // teleport effect is enabled. Suppression is set either way so a single entry fires once.
        if (Wormhole.TELEPORT_ENABLED) {
            if (!crossDim) {
                // Same dimension: client-predict the move for seamlessness.
                player.setPos(destPos.x, destPos.y, destPos.z);
                player.setDeltaMovement(destVel);
                player.setYRot(destYaw);
                player.setXRot(destPitch);
            }
            // Cross-dim: do NOT setPos locally — setPos can't change dimension, so it would land the
            // player at destination coords in the WRONG (source) level → blank. The server teleports
            // to the destination ServerLevel and the respawn swap (HandleRespawnMixin) moves the
            // client. Carries srcPos + predicted dest for the server's divergence log ([wh-srv]).
            ClientPlayNetworking.send(new ClientCrossedPayload(pair.getId(), isEndA, srcPos, destPos));
        }
        justTeleported = true;
        lastFeet = destPos;
        // Cross-dimensional crossing → the server will swap the client's dimension (a respawn packet);
        // suppress the vanilla loading screen for a short window so the transition stays seamless.
        if (crossDim) {
            dimScreenSuppressTicks = 60;
        }

        WormholeDebug.crossing(src, pair.linkFor(src), isEndA,
            srcPos, destPos, srcVel, destVel, srcYaw, destYaw);
    }
}
