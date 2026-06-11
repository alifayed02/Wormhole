package com.wormhole.client;

import com.wormhole.client.render.PortalRenderTypes;
import com.wormhole.client.render.PortalRenderer;
import com.wormhole.client.render.StencilPortalRenderer;
import com.wormhole.net.WormholePayloads.SyncPairsPayload;
import com.wormhole.portal.PortalRegistry;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Client entrypoint. Mirrors portal state from the server and drives the look-through render on the
 * {@code AFTER_TRANSLUCENT_TERRAIN} phase.
 */
public class WormholeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SyncPairsPayload.TYPE, (payload, context) ->
            context.client().execute(() -> PortalRegistry.clientSet(payload.pairs())));

        PortalRenderTypes.init();

        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> StencilPortalRenderer.render());

        // Seamless crossing: per-tick volume check at tick start (ported from SeamlessPortals).
        ClientTickEvents.START_CLIENT_TICK.register(ClientPortalTeleport::onClientTick);

        // Drop the dedicated renderer and stale portal state when leaving a world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PortalRenderer.dispose();
            PortalRegistry.clientSet(List.of());
        });
    }
}
