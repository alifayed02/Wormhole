package com.wormhole.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Debug aids: create a spherical mouth, list mouths, remove a pair, teleport to a pair. */
public final class WormholeCommand {
    private static final double MIN_RADIUS = 0.5;
    private static final double MAX_RADIUS = 64.0;

    private WormholeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormhole")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("create")
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(MIN_RADIUS, MAX_RADIUS))
                    .executes(WormholeCommand::createInFront)
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(WormholeCommand::createAt))))
            .then(Commands.literal("list").executes(WormholeCommand::list))
            .then(Commands.literal("remove")
                .then(Commands.argument("id", UuidArgument.uuid()).executes(WormholeCommand::remove)))
            .then(Commands.literal("tp")
                .then(Commands.argument("id", UuidArgument.uuid()).executes(WormholeCommand::tp))));
    }

    /** /wormhole create <radius> — spawn a mouth ~radius+2 blocks in front of the player's eyes. */
    private static int createInFront(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Players only (or pass an explicit position)."));
            return 0;
        }
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        Vec3 center = player.getEyePosition().add(player.getLookAngle().scale(radius + 2.0));
        return doCreate(ctx, center, radius);
    }

    /** /wormhole create <radius> <x y z> — spawn a mouth at an explicit position. */
    private static int createAt(CommandContext<CommandSourceStack> ctx) {
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        Vec3 center = Vec3Argument.getVec3(ctx, "pos");
        return doCreate(ctx, center, radius);
    }

    private static int doCreate(CommandContext<CommandSourceStack> ctx, Vec3 center, double radius) {
        PortalManager manager = PortalManager.get(ctx.getSource().getServer());
        PortalEnd end = new PortalEnd(ctx.getSource().getLevel().dimension(), center, radius);
        PortalPair pair = manager.addEnd(end);
        if (pair == null) {
            ctx.getSource().sendFailure(Component.literal("Mouth overlaps an existing one; nothing created."));
            return 0;
        }
        Wormhole.broadcastUpsert(ctx.getSource().getServer(), pair);
        String state = pair.isLinked() ? "linked pair " + pair.getId() : "pending pair " + pair.getId();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
            "Created mouth r=%.1f at (%.1f, %.1f, %.1f) — %s",
            radius, center.x, center.y, center.z, state)), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<PortalPair> pairs = PortalManager.get(ctx.getSource().getServer()).all();
        if (pairs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No mouths."), false);
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
        Vec3 c = end.getCenter();
        return String.format("%s (%.1f,%.1f,%.1f) r=%.1f",
            end.getDimension().identifier(), c.x, c.y, c.z, end.getRadius());
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
        Vec3 c = end.getCenter();
        // Land just outside the sphere so we don't immediately cross.
        Vec3 spot = c.add(end.getRadius() + 2.0, 0.0, 0.0);
        player.teleportTo(ctx.getSource().getServer().getLevel(end.getDimension()),
            spot.x, spot.y, spot.z, Set.of(), player.getYRot(), player.getXRot(), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Teleported to pair " + pair.getId()), false);
        return 1;
    }
}
