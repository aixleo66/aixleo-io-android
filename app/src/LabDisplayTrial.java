package dev.xr.rayneo.probe;

/** One bounded display experiment. Transport completion never means lens visibility. */
final class LabDisplayTrial {
    static final int NONE = 0, TEXT = 5, EXIT = 3, FINISHED = -1;
    final String id;
    String phase = "config_pending", issue = "";
    long deadline;
    boolean configAccepted, textSent, exitSent;

    LabDisplayTrial(String id, long now) { this.id = id; deadline = now + 10000; }
    boolean owns(String value) { return id.equals(value); }
    int configuration(String sid, Object code, long now) {
        if (!owns(sid) || !phase.equals("config_pending")) return NONE;
        if (!(code instanceof Number) || (((Number) code).doubleValue() != 1 && ((Number) code).doubleValue() != 2))
            return stop(now, "config_not_accepted");
        configAccepted = true; phase = "text_pending"; deadline = now + 8000; return TEXT;
    }
    int sent(String kind, boolean success, long now) {
        if (kind.equals("config") && phase.equals("config_pending") && !success)
            return stop(now, "config_send_failed");
        if (kind.equals("text") && phase.equals("text_pending")) {
            if (!success) return stop(now, "text_send_failed");
            textSent = true; phase = "visible_unverified"; deadline = now + 45000;
        }
        if (kind.equals("exit") && phase.equals("exit_pending")) {
            exitSent = success; phase = "exit_unverified";
            if (!success) issue = "exit_send_failed";
            return FINISHED;
        }
        return NONE;
    }
    int tick(long now) {
        if (now < deadline || phase.equals("exit_unverified")) return NONE;
        if (phase.equals("exit_pending")) { phase = "exit_unverified"; issue = "exit_send_timeout"; return FINISHED; }
        return stop(now, phase.equals("visible_unverified") ? "" : phase + "_timeout");
    }
    int stop(long now, String reason) {
        if (phase.equals("exit_pending") || phase.equals("exit_unverified")) return NONE;
        if (!reason.isEmpty()) issue = reason;
        phase = "exit_pending"; deadline = now + 8000; return EXIT;
    }
}
