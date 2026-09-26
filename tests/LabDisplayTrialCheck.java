package dev.xr.rayneo.probe;

public final class LabDisplayTrialCheck {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        LabDisplayTrial t = new LabDisplayTrial("new", 0);
        check(t.configuration("old", 1, 10) == 0 && !t.configAccepted);
        check(t.sent("text", true, 10) == 0 && !t.textSent);
        check(t.configuration("new", 1, 10) == 5);
        check(t.configuration("new", 1, 20) == 0);
        check(t.sent("config", false, 30) == 0);
        t.sent("text", true, 40);
        check(t.phase.equals("visible_unverified") && t.tick(45039) == 0);
        check(t.tick(45040) == 3 && t.stop(45041, "again") == 0);
        check(t.sent("text", true, 45042) == 0);
        check(t.sent("exit", true, 45043) == -1 && t.phase.equals("exit_unverified"));
        check(t.tick(999999) == 0 && t.textSent && t.exitSent);
        LabDisplayTrial denied = new LabDisplayTrial("x", 0);
        check(denied.configuration("x", "1", 0) == 3 && !denied.configAccepted);
        check(denied.tick(8000) == -1 && denied.issue.equals("exit_send_timeout"));
        LabDisplayTrial timeout = new LabDisplayTrial("x", 0);
        check(timeout.tick(9999) == 0 && timeout.tick(10000) == 3);
        check(timeout.sent("exit", false, 10001) == -1 && !timeout.exitSent);
        LabDisplayTrial interrupted = new LabDisplayTrial("x", 0);
        interrupted.configuration("x", 2, 0);
        check(interrupted.stop(1, "audio_received") == 3);
        interrupted.sent("text", true, 2);
        check(!interrupted.textSent && interrupted.issue.equals("audio_received"));
        System.out.println("display lifecycle checks passed");
    }
}
