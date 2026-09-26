package dev.xr.rayneo.probe;

/** How long a command of each kind may legitimately stay pending.
 *
 * <p>The host waits exactly this long before giving up on a command, so the same table decides
 * when a still-pending command has stopped meaning "work in progress" and started meaning
 * "nobody is coming back for this". It lived as one inline ternary in {@code CloudActivity}; the
 * notification gate needs it too, and a second copy of a nine-entry table is the shape of defect
 * this project keeps paying for.
 *
 * <p>{@code forwardPhoneNotification} refuses every phone notification while
 * {@code last_command} is pending, and nothing ever expires that state. Measured 2026-09-20: an
 * ASR round stopped at {@code stream_microphone_stopped}, left {@code voice-native/pending}
 * behind, and notification forwarding stayed dead at 23 while the listener kept observing 38603
 * posts. The user sees notifications stop arriving, with no error, no prompt and no recovery
 * short of restarting the session. The official app does not gate notifications on command state
 * at all: its NOTIFICATION and VOICE_ASSISTANT frames interleave, 8ms apart in the closest
 * observed sample. These independent flows must not block one another indefinitely. */
final class CommandDeadline {
    /** Beyond its own budget a pending command may still finish; it may not keep blocking
     * unrelated traffic while it does. The grace is for the round trip the host cannot see. */
    static final long GRACE_MS = 15000;

    /** The host's own wait for this kind, in milliseconds. Unknown kinds get the host default. */
    static long budgetMs(String kind) {
        if (kind == null) return 12000;
        if (kind.equals("record-stop")) return 180000;
        if (kind.equals("record-start")) return 40000;
        if (kind.equals("voice-cloud") || kind.equals("voice-native")) return 240000;
        if (kind.equals("voice-standby")) return 40000;
        if (kind.equals("voice")) return 103000;
        if (kind.equals("pair")) return 70000;
        if (kind.equals("spp")) return 28000;
        if (kind.equals("audio")) return 15000;
        return 12000;
    }

    /** Whether a command that has been pending for {@code ageMs} has outlived its own budget.
     *
     * <p>A negative or missing age means the start time was never recorded, which must not be
     * read as "infinitely old": an unknown age keeps the command gating, exactly as before. */
    static boolean expired(String kind, long ageMs) {
        return ageMs >= 0 && ageMs > budgetMs(kind) + GRACE_MS;
    }
}
