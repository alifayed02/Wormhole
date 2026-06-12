package com.wormhole.server;

import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalManager;
import com.wormhole.portal.PortalPair;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Per-tick volume-overlap crossing for non-player entities, mirroring the client player model:
 * an entity whose position enters a linked end's volume is teleported once, then suppressed
 * until it has left every portal volume (same suppress-until-exit rule as the player).
 */
public final class ServerEntityCrossing {
    /** Entities that were inside any portal volume last tick (suppressed from re-crossing). */
    private static final Set<UUID> insideLastTick = new HashSet<>();

    private ServerEntityCrossing() {
    }

    /** Driven from {@code ServerTickEvents.END_SERVER_TICK}. */
    public static void onServerTick(MinecraftServer server) {
        PortalManager manager = PortalManager.get(server);
        Set<UUID> insideNow = new HashSet<>();
        Set<UUID> crossedThisTick = new HashSet<>();
        for (PortalPair pair : manager.all()) {
            if (!pair.isLinked()) {
                continue;
            }
            ServerLevel level = server.getLevel(pair.getA().getDimension());
            if (level == null) {
                continue;
            }
            crossEntities(level, pair, pair.getA(), insideNow, crossedThisTick);
            crossEntities(level, pair, pair.getB(), insideNow, crossedThisTick);
        }
        insideLastTick.clear();
        insideLastTick.addAll(insideNow);
    }

    private static void crossEntities(ServerLevel level, PortalPair pair, PortalEnd source,
                                      Set<UUID> insideNow, Set<UUID> crossedThisTick) {
        AABB volume = source.getBoundingBox().inflate(0.1);
        for (Entity entity : level.getEntities((Entity) null, volume,
                e -> !(e instanceof ServerPlayer) && e.isAlive() && !e.isPassenger() && !e.isVehicle())) {
            if (!source.containsPoint(entity.position())) {
                continue;
            }
            UUID id = entity.getUUID();
            insideNow.add(id);
            if (insideLastTick.contains(id) || crossedThisTick.contains(id)) {
                continue;
            }
            Vec3 destPos = pair.transformTeleportPosition(source, entity.position());
            Vec3 destVel = pair.transformVelocity(source, entity.getDeltaMovement());
            float destYaw = pair.transformYaw(source, entity.getYRot());
            entity.teleportTo(level, destPos.x, destPos.y, destPos.z, Set.of(), destYaw, entity.getXRot(), false);
            entity.setDeltaMovement(destVel);
            crossedThisTick.add(id);
        }
    }
}
