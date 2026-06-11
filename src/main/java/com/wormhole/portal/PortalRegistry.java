package com.wormhole.portal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the active portal pairs. Same-dimension prototype, so a single flat list (no per-dimension
 * map). Session-only -- not persisted to disk.
 *
 * <p>The {@code SERVER} list is authoritative and mutated by the command; the {@code CLIENT} list is
 * a mirror rebuilt from the sync payload and read by the renderer.
 */
public final class PortalRegistry {
    private static final List<PortalPair> SERVER = new ArrayList<>();
    private static volatile List<PortalPair> CLIENT = List.of();

    private PortalRegistry() {
    }

    // ----- server side -----

    public static void serverAdd(PortalPair pair) {
        SERVER.add(pair);
    }

    public static void serverClear() {
        SERVER.clear();
    }

    /** A defensive snapshot of the authoritative list, safe to iterate while sending. */
    public static List<PortalPair> serverPairs() {
        return new ArrayList<>(SERVER);
    }

    // ----- client side -----

    public static void clientSet(List<PortalPair> pairs) {
        CLIENT = List.copyOf(pairs);
    }

    public static List<PortalPair> clientPairs() {
        return Collections.unmodifiableList(CLIENT);
    }
}
