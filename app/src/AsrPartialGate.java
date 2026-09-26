package dev.xr.rayneo.probe;

/** Gates partial ASR text before it reaches the glasses. The earlier fixed 500ms poll redrew a
 * sentence the recognizer was still revising, which reads as jitter. Release only on
 * a round's first frame, after the recognizer has gone quiet, or once the text already on screen
 * has survived untouched and grown by a whole step. Final text does not pass through here. */
final class AsrPartialGate {
    private final int prefixStep;
    private final int idleMs;
    private final int maxPerRound;
    private long lastAt;
    private String lastText = "";
    private int released;

    AsrPartialGate(int prefixStep, int idleMs, int maxPerRound) {
        this.prefixStep = prefixStep; this.idleMs = idleMs; this.maxPerRound = maxPerRound;
    }

    /** A released frame may still be abandoned by the caller before it reaches the glasses, so the
     * baseline the next frame is compared against only moves in commit(). */
    boolean accept(String value, long now) {
        if (value.equals(lastText)) return false;
        if (released >= maxPerRound) return false;
        if (lastText.isEmpty()) { released++; return true; }
        if (now - lastAt >= idleMs) { released++; return true; }
        if (value.startsWith(lastText) && value.length() - lastText.length() >= prefixStep) { released++; return true; }
        return false;
    }

    /** Released partials and final text alike become the baseline for what follows. */
    void commit(String value, long now) { lastAt = now; lastText = value; }

    int released() { return released; }
}
