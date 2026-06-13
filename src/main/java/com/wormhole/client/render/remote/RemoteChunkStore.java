package com.wormhole.client.render.remote;

import com.wormhole.Wormhole;
import io.netty.buffer.Unpooled;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * Client receiver for streamed remote-dimension chunks (cross-dimensional through-view). Received
 * payloads are queued and budget-drained each client tick: deserialized into {@link LevelChunkSection}s
 * + sky/block light, then fed into the synthetic level via {@link RemoteDimensions#feedChunk}. Wire
 * format mirrors {@code CrossDimChunkStreamer} (varInt section count, {@code section.write} per
 * section, then per-section sky then block light: boolean present + 2048-byte {@link DataLayer}).
 */
public final class RemoteChunkStore {
    private static final ConcurrentLinkedQueue<Pending> PENDING = new ConcurrentLinkedQueue<>();
    private static final int DRAIN_PER_TICK = 8;
    private static final long DRAIN_BUDGET_NS = 6_000_000L;

    private RemoteChunkStore() {
    }

    private record Pending(ResourceKey<Level> dim, int cx, int cz, byte[] data) {
    }

    public static void handleChunkData(String dimId, int cx, int cz, byte[] data) {
        ResourceKey<Level> dim = parseDim(dimId);
        if (dim != null) {
            PENDING.add(new Pending(dim, cx, cz, data));
        }
    }

    public static void handleChunkUnload(String dimId, int cx, int cz) {
        // v1 (static geometry): out-of-range chunks simply stop being re-fed; the synthetic level
        // keeps what it has until the level is disposed. Tracked here for protocol completeness.
    }

    /** Budget-drained each client tick (≤8 chunks / ~6 ms). */
    public static void drainPending() {
        long start = System.nanoTime();
        for (int i = 0; i < DRAIN_PER_TICK && System.nanoTime() - start < DRAIN_BUDGET_NS; i++) {
            Pending p = PENDING.poll();
            if (p == null) {
                break;
            }
            try {
                feed(p);
            } catch (Exception e) {
                Wormhole.LOGGER.error("[crossdim] failed to load remote chunk", e);
            }
        }
    }

    private static void feed(Pending p) {
        ClientLevel level = RemoteDimensions.getOrCreate(p.dim());
        if (level == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(p.data()));
        try {
            int count = buf.readVarInt();
            PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
            LevelChunkSection[] sections = new LevelChunkSection[count];
            for (int i = 0; i < count; i++) {
                LevelChunkSection s = new LevelChunkSection(factory);
                s.read(buf);
                sections[i] = s;
            }
            DataLayer[] sky = readLight(buf, count);
            DataLayer[] block = readLight(buf, count);
            RemoteDimensions.feedChunk(p.dim(), p.cx(), p.cz(), sections, sky, block);
        } finally {
            buf.release();
        }
    }

    private static DataLayer[] readLight(FriendlyByteBuf buf, int count) {
        DataLayer[] out = new DataLayer[count];
        for (int i = 0; i < count; i++) {
            if (buf.readBoolean()) {
                byte[] d = new byte[2048];
                buf.readBytes(d);
                out[i] = new DataLayer(d);
            }
        }
        return out;
    }

    private static ResourceKey<Level> parseDim(String dimId) {
        try {
            return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimId));
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear() {
        PENDING.clear();
    }
}
