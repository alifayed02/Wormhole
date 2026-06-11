package com.wormhole.portal;

import com.mojang.serialization.Codec;
import com.wormhole.Wormhole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The authoritative portal registry, persisted with the overworld's saved data.
 *
 * <p>{@code pairs} is ordered by creation; the FIFO pending queue is implicit: the first
 * pair with {@code !isLinked()} (in the right dimension) is the next to be linked.
 */
public final class PortalManager extends SavedData {
    private static final Codec<PortalManager> CODEC = PortalPair.CODEC.listOf()
        .fieldOf("pairs")
        .xmap(PortalManager::new, manager -> List.copyOf(manager.pairs))
        .codec();

    public static final SavedDataType<PortalManager> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(Wormhole.MOD_ID, "portals"),
        PortalManager::new,
        CODEC,
        DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final List<PortalPair> pairs;

    public PortalManager() {
        this.pairs = new ArrayList<>();
    }

    private PortalManager(List<PortalPair> loaded) {
        this.pairs = new ArrayList<>(loaded);
    }

    public static PortalManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public List<PortalPair> all() {
        return List.copyOf(this.pairs);
    }

    public PortalPair byId(UUID id) {
        for (PortalPair pair : this.pairs) {
            if (pair.getId().equals(id)) {
                return pair;
            }
        }
        return null;
    }

    /**
     * Registers a newly detected end: links it to the oldest same-dimension pending pair, or
     * starts a new pending pair. Returns the resulting pair, or null if the end overlaps an
     * existing portal (e.g. the detector re-found an active frame).
     */
    public PortalPair addEnd(PortalEnd end) {
        for (PortalPair pair : this.pairs) {
            if (pair.getA().overlaps(end) || (pair.isLinked() && pair.getB().overlaps(end))) {
                return null;
            }
        }
        for (int i = 0; i < this.pairs.size(); i++) {
            PortalPair pair = this.pairs.get(i);
            if (!pair.isLinked() && pair.getA().getDimension().equals(end.getDimension())) {
                PortalPair linked = pair.linkedWith(end);
                this.pairs.set(i, linked);
                this.setDirty();
                return linked;
            }
        }
        PortalPair pending = PortalPair.pending(UUID.randomUUID(), end);
        this.pairs.add(pending);
        this.setDirty();
        return pending;
    }

    /** Result of destroying one end: {@code replacement == null} means the whole pair is gone. */
    public record Mutation(UUID pairId, PortalPair replacement) {
    }

    /**
     * Destroys the first end hit by a block change at {@code pos} ({@code asFrameBlock}: the
     * changed block was part of the frame; otherwise: a block appeared in the interior).
     * Returns null when nothing was hit. Callers loop until null, since separate frames can
     * share a wall of diamond blocks.
     */
    public Mutation removeEndAt(ResourceKey<Level> dimension, BlockPos pos, boolean asFrameBlock) {
        for (int i = 0; i < this.pairs.size(); i++) {
            PortalPair pair = this.pairs.get(i);
            boolean hitA = matches(pair.getA(), dimension, pos, asFrameBlock);
            boolean hitB = pair.isLinked() && matches(pair.getB(), dimension, pos, asFrameBlock);
            if (!hitA && !hitB) {
                continue;
            }
            if (!pair.isLinked() || (hitA && hitB)) {
                this.pairs.remove(i);
                this.setDirty();
                return new Mutation(pair.getId(), null);
            }
            PortalPair pending = pair.asPendingSurvivor(hitA ? pair.getB() : pair.getA());
            this.pairs.set(i, pending);
            this.setDirty();
            return new Mutation(pair.getId(), pending);
        }
        return null;
    }

    public boolean removeById(UUID id) {
        boolean removed = this.pairs.removeIf(pair -> pair.getId().equals(id));
        if (removed) {
            this.setDirty();
        }
        return removed;
    }

    private static boolean matches(PortalEnd end, ResourceKey<Level> dimension, BlockPos pos, boolean asFrameBlock) {
        return end.getDimension().equals(dimension)
            && (asFrameBlock ? end.isFrameBlock(pos) : end.interiorContains(pos));
    }
}
