package com.wormhole.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.wormhole.Wormhole;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalPair;
import com.wormhole.portal.PortalRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /wormhole} command: creates and clears linked portal pairs.
 *
 * <ul>
 *   <li>{@code /wormhole link <aPos> <aAxis> <bPos> <bAxis> [w] [h]} -- explicit pair.</li>
 *   <li>{@code /wormhole create <axis>} -- two-step: marks end A at your position, then links on the
 *       second call.</li>
 *   <li>{@code /wormhole clear} -- removes all pairs.</li>
 * </ul>
 */
public final class WormholeCommand {
    private static final SimpleCommandExceptionType BAD_AXIS =
        new SimpleCommandExceptionType(Component.literal("Axis must be 'x' or 'z'"));

    /** Pending first end for the two-step {@code create} flow, keyed by player UUID. */
    private static final Map<UUID, PendingEnd> PENDING = new ConcurrentHashMap<>();

    private static final int DEFAULT_WIDTH = 4;
    private static final int DEFAULT_HEIGHT = 4;

    private WormholeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("wormhole")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("link")
                    .then(Commands.argument("aPos", BlockPosArgument.blockPos())
                        .then(Commands.argument("aAxis", StringArgumentType.word())
                            .then(Commands.argument("bPos", BlockPosArgument.blockPos())
                                .then(Commands.argument("bAxis", StringArgumentType.word())
                                    .executes(ctx -> link(ctx, DEFAULT_WIDTH, DEFAULT_HEIGHT))
                                    .then(Commands.argument("w", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("h", IntegerArgumentType.integer(1))
                                            .executes(ctx -> link(ctx,
                                                IntegerArgumentType.getInteger(ctx, "w"),
                                                IntegerArgumentType.getInteger(ctx, "h"))))))))))
                .then(Commands.literal("create")
                    .then(Commands.argument("axis", StringArgumentType.word())
                        .executes(ctx -> create(ctx, DEFAULT_WIDTH, DEFAULT_HEIGHT))
                        .then(Commands.argument("w", IntegerArgumentType.integer(1))
                            .then(Commands.argument("h", IntegerArgumentType.integer(1))
                                .executes(ctx -> create(ctx,
                                    IntegerArgumentType.getInteger(ctx, "w"),
                                    IntegerArgumentType.getInteger(ctx, "h")))))))
                .then(Commands.literal("clear")
                    .executes(WormholeCommand::clear)));
    }

    private static int link(CommandContext<CommandSourceStack> ctx, int width, int height) throws CommandSyntaxException {
        BlockPos aPos = BlockPosArgument.getLoadedBlockPos(ctx, "aPos");
        Direction.Axis aAxis = parseAxis(StringArgumentType.getString(ctx, "aAxis"));
        BlockPos bPos = BlockPosArgument.getLoadedBlockPos(ctx, "bPos");
        Direction.Axis bAxis = parseAxis(StringArgumentType.getString(ctx, "bAxis"));

        PortalPair pair = new PortalPair(UUID.randomUUID(),
            new PortalEnd(aPos, aAxis, width, height),
            new PortalEnd(bPos, bAxis, width, height));
        PortalRegistry.serverAdd(pair);
        Wormhole.syncAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Linked wormhole " + pair.getId()), true);
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> ctx, int width, int height) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Direction.Axis axis = parseAxis(StringArgumentType.getString(ctx, "axis"));
        BlockPos pos = player.blockPosition();

        PendingEnd pending = PENDING.remove(player.getUUID());
        if (pending == null) {
            PENDING.put(player.getUUID(), new PendingEnd(pos, axis, width, height));
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Marked first end at " + pos + " (" + width + "x" + height
                    + "). Run /wormhole create <axis> at the other end to link."), false);
            return 1;
        }

        PortalPair pair = new PortalPair(UUID.randomUUID(),
            new PortalEnd(pending.pos(), pending.axis(), pending.width(), pending.height()),
            new PortalEnd(pos, axis, pending.width(), pending.height()));
        PortalRegistry.serverAdd(pair);
        Wormhole.syncAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Linked wormhole " + pair.getId()), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        PortalRegistry.serverClear();
        PENDING.clear();
        Wormhole.syncAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared all wormholes"), true);
        return 1;
    }

    private static Direction.Axis parseAxis(String name) throws CommandSyntaxException {
        Direction.Axis axis = Direction.Axis.byName(name.toLowerCase());
        if (axis != Direction.Axis.X && axis != Direction.Axis.Z) {
            throw BAD_AXIS.create();
        }
        return axis;
    }

    private record PendingEnd(BlockPos pos, Direction.Axis axis, int width, int height) {
    }
}
