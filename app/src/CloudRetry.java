package dev.xr.rayneo.probe;

/** Whether a failed answer request is worth repeating, and how long to wait first.
 *
 * <p>Copied from the official app rather than chosen: its runtime configuration, captured
 * 2026-09-19, reads {@code maxTimeoutRetries=1}, {@code timeoutDelayMs=5000},
 * {@code deepThinkTimeout=60000}. Ours retried zero times, which is why a single unanswered
 * request ended the round.
 *
 * <p>Our own read timeout stays at 45000 and is deliberately not raised to the official 60000:
 * with one retry the worst case is already 45 + 5 + 45 seconds of waiting, and nothing measured
 * says the extra 15 would have turned any observed failure into an answer. The one measured hang
 * returned zero bytes for the full 45 seconds -- a longer wait would only have made the silence
 * longer. */
final class CloudRetry {
    /** Official: maxTimeoutRetries=1. */
    static final int MAX_ATTEMPTS = 2;
    /** Official: timeoutDelayMs=5000. */
    static final long DELAY_MS = 5000;

    /** Only a request that went unanswered is repeated.
     *
     * <p>A refusal, a bad key or a rejected payload will fail again identically, so repeating it
     * just doubles the user's wait before the same notice appears. A cancellation must never be
     * repeated: the round it belonged to is already gone. */
    static boolean worthRepeating(String error) {
        return !CloudFailureNotice.cancelled(error) && CloudFailureNotice.timedOut(error);
    }

    /** Attempt numbers are 1-based; attempt {@code MAX_ATTEMPTS} is the last one. */
    static boolean again(int attempt, String error) {
        return attempt < MAX_ATTEMPTS && worthRepeating(error);
    }
}
