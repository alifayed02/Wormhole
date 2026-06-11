package com.wormhole.client;

import com.wormhole.net.WormholePayloads.RemovePairPayload;
import com.wormhole.net.WormholePayloads.SyncPairsPayload;
import com.wormhole.net.WormholePayloads.UpsertPairPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client entrypoint. Mirrors portal state from the server. */
public class WormholeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SyncPairsPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientPortalStore.setAll(payload.pairs())));
        ClientPlayNetworking.registerGlobalReceiver(UpsertPairPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientPortalStore.upsert(payload.pair())));
        ClientPlayNetworking.registerGlobalReceiver(RemovePairPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientPortalStore.remove(payload.pairId())));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPortalStore.clear());
    }
}
