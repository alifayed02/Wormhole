package com.wormhole.client;

import com.wormhole.Wormhole;

/** Lightweight stdout debug logging (no in-game output). */
public final class WormholeDebug {
    public static boolean ENABLED = true;
    /** Frame counter, bumped once per rendered frame, so correlated logs share an index. */
    public static long frame = 0;

    private WormholeDebug() {
    }

    public static void log(String msg) {
        if (ENABLED) {
            Wormhole.LOGGER.info("[wh-dbg] {}", msg);
        }
    }
}
