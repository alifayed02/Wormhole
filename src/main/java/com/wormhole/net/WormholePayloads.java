package com.wormhole.net;

import com.wormhole.Wormhole;
import com.wormhole.portal.PortalPair;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Server->client: full sync on join plus upsert/remove deltas on registry change.
 * Client->server: predicted crossing reports.
 */
public final class WormholePayloads {
    private WormholePayloads() {
    }

    /** Registers payload types on both sides. Call from the common initializer. */
    public static void registerTypes() {
        PayloadTypeRegistry.clientboundPlay().register(SyncPairsPayload.TYPE, SyncPairsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(UpsertPairPayload.TYPE, UpsertPairPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RemovePairPayload.TYPE, RemovePairPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ClientCrossedPayload.TYPE, ClientCrossedPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RemoteChunkPayload.TYPE, RemoteChunkPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RemoteChunkUnloadPayload.TYPE, RemoteChunkUnloadPayload.CODEC);
    }

    /** Server -> client: one chunk's serialized sections + light for a remote (cross-dim) view. */
    public record RemoteChunkPayload(String dimId, int chunkX, int chunkZ, byte[] data)
        implements CustomPacketPayload {
        public static final Type<RemoteChunkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Wormhole.MOD_ID, "remote_chunk"));

        public static final StreamCodec<FriendlyByteBuf, RemoteChunkPayload> CODEC =
            CustomPacketPayload.codec(RemoteChunkPayload::write, RemoteChunkPayload::new);

        private RemoteChunkPayload(FriendlyByteBuf buf) {
            this(buf.readUtf(), buf.readInt(), buf.readInt(), buf.readByteArray());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(this.dimId);
            buf.writeInt(this.chunkX);
            buf.writeInt(this.chunkZ);
            buf.writeByteArray(this.data);
        }

        @Override
        public Type<RemoteChunkPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: a remote chunk left range and should be dropped. */
    public record RemoteChunkUnloadPayload(String dimId, int chunkX, int chunkZ)
        implements CustomPacketPayload {
        public static final Type<RemoteChunkUnloadPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Wormhole.MOD_ID, "remote_chunk_unload"));

        public static final StreamCodec<FriendlyByteBuf, RemoteChunkUnloadPayload> CODEC =
            CustomPacketPayload.codec(RemoteChunkUnloadPayload::write, RemoteChunkUnloadPayload::new);

        private RemoteChunkUnloadPayload(FriendlyByteBuf buf) {
            this(buf.readUtf(), buf.readInt(), buf.readInt());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(this.dimId);
            buf.writeInt(this.chunkX);
            buf.writeInt(this.chunkZ);
        }

        @Override
        public Type<RemoteChunkUnloadPayload> type() {
            return TYPE;
        }
    }

    /** Full state: the entire pair list (sent on join). */
    public record SyncPairsPayload(List<PortalPair> pairs) implements CustomPacketPayload {
        public static final Type<SyncPairsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Wormhole.MOD_ID, "sync_pairs"));

        public static final StreamCodec<FriendlyByteBuf, SyncPairsPayload> CODEC =
            CustomPacketPayload.codec(SyncPairsPayload::write, SyncPairsPayload::new);

        private SyncPairsPayload(FriendlyByteBuf buf) {
            this(readPairs(buf));
        }

        private static List<PortalPair> readPairs(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<PortalPair> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(PortalPair.read(buf));
            }
            return list;
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.pairs.size());
            for (PortalPair pair : this.pairs) {
                pair.write(buf);
            }
        }

        @Override
        public Type<SyncPairsPayload> type() {
            return TYPE;
        }
    }

    /** Delta: one pair created or changed (pending->linked, linked->pending survivor). */
    public record UpsertPairPayload(PortalPair pair) implements CustomPacketPayload {
        public static final Type<UpsertPairPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Wormhole.MOD_ID, "upsert_pair"));

        public static final StreamCodec<FriendlyByteBuf, UpsertPairPayload> CODEC =
            CustomPacketPayload.codec(UpsertPairPayload::write, UpsertPairPayload::new);

        private UpsertPairPayload(FriendlyByteBuf buf) {
            this(PortalPair.read(buf));
        }

        private void write(FriendlyByteBuf buf) {
            this.pair.write(buf);
        }

        @Override
        public Type<UpsertPairPayload> type() {
            return TYPE;
        }
    }

    /** Delta: a pair is gone entirely. */
    public record RemovePairPayload(UUID pairId) implements CustomPacketPayload {
        public static final Type<RemovePairPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Wormhole.MOD_ID, "remove_pair"));

        public static final StreamCodec<FriendlyByteBuf, RemovePairPayload> CODEC =
            CustomPacketPayload.codec(RemovePairPayload::write, RemovePairPayload::new);

        private RemovePairPayload(FriendlyByteBuf buf) {
            this(buf.readUUID());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUUID(this.pairId);
        }

        @Override
        public Type<RemovePairPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> server: the local player predicted a crossing of {@code pairId} from the given end.
     * Also carries the client's source position and predicted destination — debug instrumentation
     * so the server can quantify how far its own computation diverges (the bounce investigation).
     */
    public record ClientCrossedPayload(UUID pairId, boolean fromEndA, Vec3 clientSrcPos, Vec3 clientPredictedDest)
        implements CustomPacketPayload {
        public static final Type<ClientCrossedPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Wormhole.MOD_ID, "client_crossed"));

        public static final StreamCodec<FriendlyByteBuf, ClientCrossedPayload> CODEC =
            CustomPacketPayload.codec(ClientCrossedPayload::write, ClientCrossedPayload::new);

        private ClientCrossedPayload(FriendlyByteBuf buf) {
            this(buf.readUUID(), buf.readBoolean(), readVec3(buf), readVec3(buf));
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUUID(this.pairId);
            buf.writeBoolean(this.fromEndA);
            writeVec3(buf, this.clientSrcPos);
            writeVec3(buf, this.clientPredictedDest);
        }

        private static Vec3 readVec3(FriendlyByteBuf buf) {
            return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        private static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
            buf.writeDouble(v.x);
            buf.writeDouble(v.y);
            buf.writeDouble(v.z);
        }

        @Override
        public Type<ClientCrossedPayload> type() {
            return TYPE;
        }
    }
}
