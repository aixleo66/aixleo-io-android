package dev.xr.rayneo.probe;

/** Behaviour, not text. The first version of this check searched CommandWait.java for the string
 * "apply-setting" -- which also appears in the javadoc, so the test stayed green even if allows()
 * stopped calling independent() and the exemption became dead code. Same trap as the earlier
 * settings review found. Assert what the method returns. */
public final class CommandWaitCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) {
        // Settings ride LAUNCHER while the assistant occupies VOICE_ASSISTANT, and voice standby is
        // a long-lived command: without this exemption every settings write was swallowed silently
        // while standby was on, which is how it shipped on 2026-09-22.
        check(CommandWait.allows("apply-setting", true, false), "apply-setting must pass while busy");
        // But a command file the device has not consumed yet must still stop it: overwriting one
        // loses the earlier write, and the UI only reports that much later, as a timeout.
        check(!CommandWait.allows("apply-setting", false, true), "apply-setting must wait for a pending command");
        check(!CommandWait.allows("apply-setting", true, true), "apply-setting must wait for a pending command while busy");

        // Ordinary commands stay serialized.
        check(!CommandWait.allows("notify", true, false), "notify must be blocked while busy");
        check(!CommandWait.allows("notify", false, true), "notify must be blocked while pending");
        check(CommandWait.allows("notify", false, false), "idle must allow an ordinary command");

        // Interrupts override everything; that is what lets a user stop a stuck session.
        for (String kind : new String[]{"stop", "standby-off", "record-stop"})
            check(CommandWait.allows(kind, true, true), kind + " must interrupt regardless");
        check(CommandWait.interrupt("stop") && !CommandWait.interrupt("apply-setting"),
              "apply-setting is exempt, not an interrupt");

        System.out.println("command wait checks passed");
    }

    private CommandWaitCheck() {}
}
