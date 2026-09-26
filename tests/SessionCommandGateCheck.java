package dev.xr.rayneo.probe;

public final class SessionCommandGateCheck {
    private static void check(String label, String expected, boolean same, boolean duplicate, boolean limit,
            boolean pending, boolean display, boolean standby, boolean recording, boolean ready, String kind) {
        for (int iteration = 1; iteration <= 10; iteration++) {
            String actual = SessionCommandGate.rejection(same, duplicate, limit, pending, display, standby, recording, ready, kind);
            if (expected == null ? actual != null : !expected.equals(actual))
                throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
            System.out.println(label + " iteration=" + iteration + " passed");
        }
    }
    public static void main(String[] args) {
        check("stale-session-before-all", "stale_session", false,true,true,true,true,true,true,false,"stop");
        check("duplicate-before-busy", "duplicate_command", true,true,false,true,false,false,true,true,"record-start");
        check("bounded-session-limit", "command_limit", true,false,true,false,false,false,false,true,"status");
        check("pending-query", "previous_command_pending", true,false,false,true,false,false,false,true,"status");
        check("pending-record-start", "previous_command_pending", true,false,false,true,false,false,true,true,"record-start");
        check("pending-stop", null, true,false,false,true,true,true,true,false,"stop");
        check("pending-record-stop", null, true,false,false,true,false,false,true,true,"record-stop");
        check("pending-standby-off", null, true,false,false,true,false,true,false,false,"standby-off");
        check("display-record-stop", "display_trial_requires_session_end", true,false,false,false,true,false,true,true,"record-stop");
        check("display-status", null, true,false,false,false,true,false,false,true,"status");
        check("standby-firmware", "standby_busy", true,false,false,false,false,true,false,true,"lab-firmware-query");
        check("standby-record-start", null, true,false,false,false,false,true,false,true,"record-start");
        // Settings ride LAUNCHER while standby uses VOICE_ASSISTANT; making a user turn off
        // voice standby to change brightness was a lab-trial artefact, not a device limit.
        check("standby-apply-setting", null, true,false,false,false,false,true,false,true,"apply-setting");
        check("standby-notify", null, true,false,false,false,false,true,false,true,"notify");
        check("recording-query", null, true,false,false,false,false,false,true,true,"status");
        check("recording-firmware", "recording_busy", true,false,false,false,false,false,true,true,"lab-firmware-query");
        check("recording-start", "recording_busy", true,false,false,false,false,false,true,true,"record-start");
        check("recording-stop", null, true,false,false,false,false,false,true,true,"record-stop");
        check("recording-standby-off", "recording_busy", true,false,false,false,false,false,true,true,"standby-off");
        check("not-ready-notify", "pairing_incomplete", true,false,false,false,false,false,false,false,"notify");
        check("not-ready-pair", null, true,false,false,false,false,false,false,false,"pair");
        check("not-ready-record-stop", "pairing_incomplete", true,false,false,false,false,false,true,false,"record-stop");
        check("idle-query", null, true,false,false,false,false,false,false,true,"status");
        System.out.println("22 scenarios x 10 repeats passed; gate only, not Android dispatch or event replay");
    }
}
