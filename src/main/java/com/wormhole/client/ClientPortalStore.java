package com.wormhole.client;

import com.wormhole.portal.PortalPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client-side mirror of the server's portal registry. Only ever touched on the client main
 * thread (payload handlers run via {@code client.execute}); read by teleport and rendering.
 */
public final class ClientPortalStore {
    private static final Map<UUID, PortalPair> PAIRS = new LinkedHashMap<>();

    private ClientPortalStore() {
    }

    public static void setAll(List<PortalPair> pairs) {
        PAIRS.clear();
        for (PortalPair pair : pairs) {
            PAIRS.put(pair.getId(), pair);
        }
    }

    public static void upsert(PortalPair pair) {
        PAIRS.put(pair.getId(), pair);
    }

    public static void remove(UUID id) {
        PAIRS.remove(id);
    }

    public static void clear() {
        PAIRS.clear();
    }

    /**
     * Linked pairs with at least one end in the given dimension — the ones teleport/render act on.
     * A cross-dimensional pair has exactly one local end here; a same-dimension pair has both.
     * Callers guard each end by {@code end.getDimension().equals(dimension)} to act only on the
     * local mouth(s).
     */
    public static List<PortalPair> linkedPairsIn(ResourceKey<Level> dimension) {
        List<PortalPair> result = new ArrayList<>();
        for (PortalPair pair : PAIRS.values()) {
            if (pair.isLinked()
                && (pair.getA().getDimension().equals(dimension)
                    || pair.getB().getDimension().equals(dimension))) {
                result.add(pair);
            }
        }
        return result;
    }
}
