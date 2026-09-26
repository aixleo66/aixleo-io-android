package dev.xr.rayneo.probe;

/** Whether a delivered answer is still on the lens and may be continued.
 *
 * <p>This condition had two inline copies: the wake gate in {@code handleVoiceWire} and the
 * {@code continuation} flag in {@code activateStandbyWake}. Only the second was updated to accept
 * the receipt that starts the windows, so the gate rejected it first and the inner code never ran.
 * Measured 2026-09-20 on a 3-chunk answer: the glasses reported type 11 at +35.6s, the app ignored
 * it and closed the round from the display-wait fallback instead, exiting at +75s.
 *
 * <p>While awaiting the display-complete receipt the windows have not started, so
 * {@code followupUntil} is 0 by construction and no deadline can be inside of: the receipt itself
 * is the start signal. Callers keep their own extra conditions and share only this part. */
final class DisplayedAnswerPolicy {
    static boolean continuable(boolean readingActive, boolean awaitingDisplayComplete,
                               long now, long followupUntil) {
        return readingActive && (awaitingDisplayComplete || now < followupUntil);
    }
}
