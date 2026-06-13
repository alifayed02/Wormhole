package com.wormhole;

import com.wormhole.command.WormholeCommand;
import com.wormhole.net.WormholePayloads;
import com.wormhole.net.WormholePayloads.ClientCrossedPayload;
import com.wormhole.net.WormholePayloads.RemovePairPayload;
import com.wormhole.net.WormholePayloads.SyncPairsPayload;
import com.wormhole.net.WormholePayloads.UpsertPairPayload;
import com.wormhole.server.PortalCrossingHandler;
import com.wormhole.server.ServerEntityCrossing;
import com.wormhole.portal.PortalManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.wormhole.portal.PortalPair;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Registers payload types and syncs portal state to joining players.
 */
public class Wormhole implements ModInitializer {
    public static final String MOD_ID = "wormhole";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Master switch for the PLAYER teleport effect. When false, entering a mouth is still detected
     * (the crossing logic runs and logs) but the player is not actually moved. Phase 1 traversal:
     * true. Gates {@code ClientPortalTeleport} (client predict) and {@code PortalCrossingHandler}
     * (server reconciliation).
     */
    public static final boolean TELEPORT_ENABLED = true;

    /**
     * Switch for NON-PLAYER entity crossing (items, mobs, projectiles). Off in Phase 1 (player-only);
     * gates {@code ServerEntityCrossing}. Flip on in a later phase once entity crossing is tested.
     */
    public static final boolean ENTITY_TELEPORT_ENABLED = false;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {}", MOD_ID);

        WormholePayloads.registerTypes();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(handler.player));

        // Client predicts crossings locally and reports them; the server applies them authoritatively.
        ServerPlayNetworking.registerGlobalReceiver(ClientCrossedPayload.TYPE, (payload, context) ->
            context.server().execute(() ->
                PortalCrossingHandler.onClientCrossed(context.player(), payload.pairId(), payload.fromEndA(),
                    payload.clientSrcPos(), payload.clientPredictedDest())));

        ServerTickEvents.END_SERVER_TICK.register(ServerEntityCrossing::onServerTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            WormholeCommand.register(dispatcher));
    }

    /** Sends the full portal state to a single player. */
    public static void syncTo(ServerPlayer player) {
        PortalManager manager = PortalManager.get(player.level().getServer());
        ServerPlayNetworking.send(player, new SyncPairsPayload(manager.all()));
    }

    public static void broadcastUpsert(MinecraftServer server, PortalPair pair) {
        broadcast(server, new UpsertPairPayload(pair));
    }

    public static void broadcastRemove(MinecraftServer server, UUID pairId) {
        broadcast(server, new RemovePairPayload(pairId));
    }

    private static void broadcast(MinecraftServer server, CustomPacketPayload payload) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
