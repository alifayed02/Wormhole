package com.wormhole.server;

import com.wormhole.net.WormholePayloads.RemoteChunkPayload;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalManager;
import com.wormhole.portal.PortalPair;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;

/**
 * Streams the chunks around a cross-dimensional mouth's DESTINATION end (in the destination
 * dimension) to nearby players, so the client can render the remote dimension through the mouth.
 * Geometry + light only (v1): each needed chunk is serialized (sections + sky/block light) and sent
 * once (tracked per player); the client rebuilds it into a synthetic level. Mirrors SeamlessPortals'
 * {@code PortalChunkTracker}. Wire format matches {@code RemoteChunkStore}.
 */
public final class CrossDimChunkStreamer {
    private static final int RADIUS = 5;              // chunks around the destination mouth
    private static final double NEAR_BLOCKS = 128.0;  // stream a pair only when its local end is this near a player
    private static final int SCAN_INTERVAL = 20;      // ticks between scans
    private static final int SEND_BUDGET = 8;         // chunks force-loaded + sent per player per scan

    private static int cooldown = 0;
    private static final Map<UUID, Set<Long>> SENT = new HashMap<>();

    private CrossDimChunkStreamer() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (--cooldown > 0) {
            return;
        }
        cooldown = SCAN_INTERVAL;
        PortalManager manager = PortalManager.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePlayer(server, manager, player);
        }
    }

    public static void onPlayerDisconnect(UUID playerId) {
        SENT.remove(playerId);
    }

    private static void updatePlayer(MinecraftServer server, PortalManager manager, ServerPlayer player) {
        ResourceKey<Level> playerDim = player.level().dimension();
        Vec3 ppos = player.position();
        Set<Long> sent = SENT.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
        int budget = SEND_BUDGET;
        for (PortalPair pair : manager.all()) {
            if (budget <= 0) {
                break;
            }
            if (!pair.isLinked()) {
                continue;
            }
            PortalEnd local;
            PortalEnd partner;
            if (pair.getA().getDimension().equals(playerDim)) {
                local = pair.getA();
                partner = pair.getB();
            } else if (pair.getB().getDimension().equals(playerDim)) {
                local = pair.getB();
                partner = pair.getA();
            } else {
                continue;
            }
            if (partner.getDimension().equals(local.getDimension())) {
                continue; // same-dimension pair — the client renders it from mc.level, no streaming
            }
            if (local.getCenter().distanceToSqr(ppos) > NEAR_BLOCKS * NEAR_BLOCKS) {
                continue;
            }
            ServerLevel destLevel = server.getLevel(partner.getDimension());
            if (destLevel == null) {
                continue;
            }
            budget = streamRegion(player, destLevel, partner, sent, budget);
        }
    }

    private static int streamRegion(ServerPlayer player, ServerLevel destLevel, PortalEnd partner,
                                    Set<Long> sent, int budget) {
        int ccx = (int) Math.floor(partner.getCenter().x) >> 4;
        int ccz = (int) Math.floor(partner.getCenter().z) >> 4;
        String dimId = partner.getDimension().identifier().toString();
        for (int dx = -RADIUS; dx <= RADIUS && budget > 0; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS && budget > 0; dz++) {
                int cx = ccx + dx;
                int cz = ccz + dz;
                long key = packKey(partner.getDimension(), cx, cz);
                if (!sent.add(key)) {
                    continue;
                }
                LevelChunk chunk = destLevel.getChunk(cx, cz); // force-load to FULL (once per chunk)
                ServerPlayNetworking.send(player, new RemoteChunkPayload(dimId, cx, cz, serialize(destLevel, chunk, cx, cz)));
                budget--;
            }
        }
        return budget;
    }

    private static byte[] serialize(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LevelChunkSection[] sections = chunk.getSections();
            buf.writeVarInt(sections.length);
            for (LevelChunkSection s : sections) {
                s.write(buf);
            }
            LevelLightEngine le = level.getLightEngine();
            int minSec = level.getMinSectionY();
            writeLight(buf, le, LightLayer.SKY, cx, cz, minSec, sections.length);
            writeLight(buf, le, LightLayer.BLOCK, cx, cz, minSec, sections.length);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static void writeLight(FriendlyByteBuf buf, LevelLightEngine le, LightLayer layer,
                                   int cx, int cz, int minSec, int count) {
        for (int i = 0; i < count; i++) {
            DataLayer d = le.getLayerListener(layer).getDataLayerData(SectionPos.of(cx, minSec + i, cz));
            if (d != null && d.getData() != null) {
                buf.writeBoolean(true);
                buf.writeBytes(d.getData());
            } else {
                buf.writeBoolean(false);
            }
        }
    }

    private static long packKey(ResourceKey<Level> dim, int cx, int cz) {
        return (((long) dim.identifier().hashCode()) << 42) ^ (((long) cx & 0x1FFFFF) << 21) ^ (cz & 0x1FFFFF);
    }
}
