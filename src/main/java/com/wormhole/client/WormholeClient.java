package com.wormhole.client;

import com.wormhole.client.render.PortalRenderTypes;
import com.wormhole.client.render.SphereRenderer;
import com.wormhole.net.WormholePayloads.RemovePairPayload;
import com.wormhole.net.WormholePayloads.SyncPairsPayload;
import com.wormhole.net.WormholePayloads.UpsertPairPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Client entrypoint. Mirrors portal state from the server and draws the mouth spheres. */
public class WormholeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SyncPairsPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientPortalStore.setAll(payload.pairs())));
        ClientPlayNetworking.registerGlobalReceiver(UpsertPairPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientPortalStore.upsert(payload.pair())));
        ClientPlayNetworking.registerGlobalReceiver(RemovePairPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientPortalStore.remove(payload.pairId())));

        PortalRenderTypes.init();

        // Draw the mouth spheres after the world's translucent terrain (validated hook point).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> SphereRenderer.render());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPortalStore.clear());

        // Seamless crossing: per-tick sphere-volume check at tick start.
        ClientTickEvents.START_CLIENT_TICK.register(ClientPortalTeleport::onClientTick);
    }
}
