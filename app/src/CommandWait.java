package dev.xr.rayneo.probe;

/** Main-thread command waits; an interrupt invalidates all older UI callbacks. */
final class CommandWait {
    private long generation;
    long begin() { return ++generation; }
    void invalidate() { ++generation; }
    boolean current(long ticket) { return ticket == generation; }
    static boolean interrupt(String kind) {
        return "stop".equals(kind) || "standby-off".equals(kind) || "record-stop".equals(kind);
    }
    /** Commands that do not contend with whatever the UI is already waiting on.
     *
     * <p>apply-setting rides the LAUNCHER channel while the assistant occupies VOICE_ASSISTANT, so
     * a running voice-standby does not block it. Without this the local busy flag swallowed every
     * settings write while standby was on -- silently, since the caller just returns. The device
     * still applies its own gate, so this only removes a UI-side block, not a real one. */
    static boolean independent(String kind) {
        return "apply-setting".equals(kind);
    }
    static boolean allows(String kind, boolean busy, boolean pending) {
        if (interrupt(kind)) return true;
        // Exempt from busy only. A command file the device has not consumed yet must still block:
        // the next write overwrites session-command.json, so the earlier one is simply lost and the
        // user finds out much later as a timeout. Flagged by independent review 2026-09-22 after
        // the first version exempted pending as well.
        if (independent(kind)) return !pending;
        return !busy && !pending;
    }
}
