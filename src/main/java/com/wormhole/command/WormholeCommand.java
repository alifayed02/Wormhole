package com.wormhole.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.wormhole.Wormhole;
import com.wormhole.portal.PortalEnd;
import com.wormhole.portal.PortalManager;
import com.wormhole.portal.PortalPair;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Debug aids: list portals, remove a pair, teleport to a pair. */
public final class WormholeCommand {
    private WormholeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormhole")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("list").executes(WormholeCommand::list))
            .then(Commands.literal("remove")
                .then(Commands.argument("id", UuidArgument.uuid()).executes(WormholeCommand::remove)))
            .then(Commands.literal("tp")
                .then(Commands.argument("id", UuidArgument.uuid()).executes(WormholeCommand::tp))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<PortalPair> pairs = PortalManager.get(ctx.getSource().getServer()).all();
        if (pairs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No portals."), false);
            return 0;
        }
        for (PortalPair pair : pairs) {
            String line = pair.getId() + "  A=" + describe(pair.getA())
                + (pair.isLinked() ? "  B=" + describe(pair.getB()) : "  B=<pending>");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return pairs.size();
    }

    private static String describe(PortalEnd end) {
        return end.getDimension().identifier() + " " + end.getOrigin().toShortString()
            + " axis=" + end.getAxis() + " " + end.getWidth() + "x" + end.getHeight();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        UUID id = UuidArgument.getUuid(ctx, "id");
        boolean removed = PortalManager.get(ctx.getSource().getServer()).removeById(id);
        if (removed) {
            Wormhole.broadcastRemove(ctx.getSource().getServer(), id);
            ctx.getSource().sendSuccess(() -> Component.literal("Removed pair " + id), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("No pair with id " + id));
        return 0;
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Players only."));
            return 0;
        }
        PortalPair pair = PortalManager.get(ctx.getSource().getServer()).byId(UuidArgument.getUuid(ctx, "id"));
        if (pair == null) {
            ctx.getSource().sendFailure(Component.literal("No such pair."));
            return 0;
        }
        PortalEnd end = pair.getA();
        Vec3 center = end.getCenter();
        Vec3 spot = center.add(end.getNormal().scale(2.0));
        player.teleportTo(ctx.getSource().getServer().getLevel(end.getDimension()),
            spot.x, end.getOrigin().getY(), spot.z, Set.of(), player.getYRot(), player.getXRot(), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Teleported to pair " + pair.getId()), false);
        return 1;
    }
}
