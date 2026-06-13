package com.wormhole.client;

import com.wormhole.client.render.PortalRenderTypes;
import com.wormhole.client.render.capture.CubeCapture;
import com.wormhole.client.render.capture.WorldCapture;
import com.wormhole.client.render.lens.AroundRenderer;
import com.wormhole.client.render.lens.LensRenderPipelines;
import com.wormhole.client.render.lens.LensSphereRenderer;
import com.wormhole.client.render.lens.PortalWindowRenderer;
import com.wormhole.client.render.lens.SceneCopy;
import net.minecraft.client.Minecraft;
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
        LensRenderPipelines.init();

        // Draw the mouth spheres with the custom lens shader after translucent terrain (depth-tested so
        // the world occludes them correctly). The destination window is captured inside render() (a
        // nested world render), so guard against re-entering this hook during that capture.
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            if (WorldCapture.isCapturing()) {
                return;
            }
            // Snapshot the on-screen scene first so the around-pass can warp those pixels (it samples
            // this copy at displaced coordinates) while still writing the lens into the main buffer.
            SceneCopy.capture(Minecraft.getInstance().getMainRenderTarget());
            LensSphereRenderer.render();  // the window through each mouth
            AroundRenderer.render();      // the surroundings bending around each mouth
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientPortalStore.clear();
            CubeCapture.dispose();
            PortalWindowRenderer.dispose();
            SceneCopy.dispose();
            WorldCapture.dispose();
        });

        ClientTickEvents.START_CLIENT_TICK.register(ClientPortalTeleport::onClientTick);
    }
}
