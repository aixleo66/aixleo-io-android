package dev.xr.rayneo.probe;

/** Ordered host-command admission; no transport, recording or JSON side effects. */
final class SessionCommandGate {
    static String rejection(boolean sameSession, boolean duplicate, boolean limitReached,
            boolean pending, boolean display, boolean standby, boolean recording,
            boolean ready, String kind) {
        if (!sameSession) return "stale_session";
        if (duplicate) return "duplicate_command";
        if (limitReached) return "command_limit";
        if (pending && !oneOf(kind, "stop", "standby-off", "record-stop")) return "previous_command_pending";
        if (display && !oneOf(kind, "status", "stop")) return "display_trial_requires_session_end";
        // apply-setting rides the LAUNCHER channel while standby uses VOICE_ASSISTANT, so the
        // two do not contend. Requiring a user to switch off voice standby before changing
        // brightness was an artefact of the lab trials, not a device constraint -- the codebase
        // already relies on that independence (lab-auto-lock-observe drops the same condition).
        if (standby && !oneOf(kind, "status", "stop", "standby-off", "record-start", "notify",
                "setup-finish", "apply-setting"))
            return "standby_busy";
        if (recording && !oneOf(kind, "record-stop", "status", "stop")) return "recording_busy";
        if (!ConnectionReadiness.permitsCommand(ready, kind)) return "pairing_incomplete";
        return null;
    }

    private static boolean oneOf(String kind, String... allowed) {
        for (String value : allowed) if (value.equals(kind)) return true;
        return false;
    }
}
