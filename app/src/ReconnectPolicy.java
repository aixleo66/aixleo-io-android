package dev.xr.rayneo.probe;

/** Backoff for automatic reconnection (09-23 plan stage 0.5).
 *
 * <p>Measured 2026-09-23: three separate losses -- overnight, a link-supervision timeout while worn
 * (HCI reason 0x08), and fold-to-sleep then crown-wake -- all ended with the phone never trying
 * again, although the system already reported the glasses nearby. Reconnection is attempted only
 * when the user has not explicitly disconnected (the existing {@code auto_connect} preference). */
final class ReconnectPolicy {
    /** Delay before attempt n (0-based): quick first tries for a blip, then back off. */
    private static final long[] DELAYS_MS = {5000, 15000, 30000, 60000, 120000};
    static final long STEADY_DELAY_MS = 300000;
    /** Past this, only a "glasses nearby" event from the system starts a new round. */
    static final int MAX_ATTEMPTS = 20;
    /** A session up at least this long counts as recovered: its next drop starts a fresh round. */
    static final long STABLE_MS = 120000;
    /** "Glasses nearby" is ignored within this long of the last attempt. */
    static final long PRESENCE_QUIET_MS = 300000;
    /** With a round waiting on backoff, "nearby" pulls the next attempt to this delay... */
    static final long PRESENCE_RETRY_MS = 1000;
    /** ...unless the last attempt was less than this long ago. */
    static final long PRESENCE_MIN_GAP_MS = 10000;

    private ReconnectPolicy() {}

    static long delay(int attempt) {
        if (attempt < 0) attempt = 0;
        return attempt < DELAYS_MS.length ? DELAYS_MS[attempt] : STEADY_DELAY_MS;
    }

    static boolean exhausted(int attempt) { return attempt >= MAX_ATTEMPTS; }

    /** readyAtMs is when the ending session became ready; 0 when it never did. */
    static boolean stable(long readyAtMs, long nowMs) { return readyAtMs > 0 && nowMs - readyAtMs >= STABLE_MS; }
}
