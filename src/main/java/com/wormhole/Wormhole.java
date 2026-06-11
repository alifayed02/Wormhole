package com.wormhole;

import com.wormhole.command.WormholeCommand;
import com.wormhole.net.WormholePayloads;
import com.wormhole.net.WormholePayloads.ClientCrossedPayload;
import com.wormhole.net.WormholePayloads.SyncPairsPayload;
import com.wormhole.portal.PortalRegistry;
import com.wormhole.server.PortalCrossingHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Registers the payload types, the {@code /wormhole} command, the per-tick
 * crossing/teleport handler, and syncs portal state to joining players.
 */
public class Wormhole implements ModInitializer {
    public static final String MOD_ID = "wormhole";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {}", MOD_ID);

        WormholePayloads.registerTypes();

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            WormholeCommand.register(dispatcher));

        // Client predicts crossings locally and reports them; the server applies them authoritatively.
        ServerPlayNetworking.registerGlobalReceiver(ClientCrossedPayload.TYPE, (payload, context) ->
            context.server().execute(() ->
                PortalCrossingHandler.onClientCrossed(context.player(), payload.pairId(), payload.fromEndA())));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            syncTo(handler.player));
    }

    /** Sends the current portal pairs to a single player. */
    public static void syncTo(ServerPlayer player) {
        ServerPlayNetworking.send(player, new SyncPairsPayload(PortalRegistry.serverPairs()));
    }

    /** Broadcasts the current portal pairs to every connected player. */
    public static void syncAll(MinecraftServer server) {
        SyncPairsPayload payload = new SyncPairsPayload(PortalRegistry.serverPairs());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
