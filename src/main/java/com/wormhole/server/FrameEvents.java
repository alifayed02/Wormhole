package com.wormhole.server;

import com.wormhole.Wormhole;
import com.wormhole.portal.FrameDetector;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalManager;
import com.wormhole.portal.PortalPair;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Routes server-side block changes into portal creation/destruction:
 * diamond removed from a frame or block placed in an interior -> destroy that end (partner
 * reverts to pending); diamond placed -> try to complete a frame (auto-ignite).
 */
public final class FrameEvents {
    private FrameEvents() {
    }

    /** Called from LevelChunkMixin on the server thread, after a real state change. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        MinecraftServer server = level.getServer();
        PortalManager manager = PortalManager.get(server);
        BlockPos immutable = pos.immutable();

        if (oldState.is(Blocks.DIAMOND_BLOCK) && !newState.is(Blocks.DIAMOND_BLOCK)) {
            applyRemovals(server, manager, level.dimension(), immutable, true);
        }
        if (!newState.isAir()) {
            applyRemovals(server, manager, level.dimension(), immutable, false);
        }
        if (newState.is(Blocks.DIAMOND_BLOCK)) {
            PortalEnd end = FrameDetector.detect(level, immutable);
            if (end != null) {
                PortalPair pair = manager.addEnd(end);
                if (pair != null) {
                    Wormhole.broadcastUpsert(server, pair);
                    Wormhole.LOGGER.info("Portal end created at {} ({}); pair {} is {}",
                        end.getOrigin(), end.getAxis(), pair.getId(), pair.isLinked() ? "linked" : "pending");
                }
            }
        }
    }

    private static void applyRemovals(MinecraftServer server, PortalManager manager,
                                      ResourceKey<Level> dimension, BlockPos pos, boolean asFrameBlock) {
        PortalManager.Mutation mutation;
        while ((mutation = manager.removeEndAt(dimension, pos, asFrameBlock)) != null) {
            if (mutation.replacement() != null) {
                Wormhole.broadcastUpsert(server, mutation.replacement());
                Wormhole.LOGGER.info("Portal end destroyed; pair {} reverted to pending", mutation.pairId());
            } else {
                Wormhole.broadcastRemove(server, mutation.pairId());
                Wormhole.LOGGER.info("Portal pair {} destroyed", mutation.pairId());
            }
        }
    }
}
