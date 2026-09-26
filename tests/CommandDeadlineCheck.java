package dev.xr.rayneo.probe;

/** A pending command must stop silencing phone notifications once it is past saving. */
final class CommandDeadlineCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // The budgets are the host's own waits; drifting from them would mean the device releases
        // the link while the host is still expecting an answer, or long after it gave up.
        check(CommandDeadline.budgetMs("record-stop") == 180000, "record-stop budget drifted");
        check(CommandDeadline.budgetMs("record-start") == 40000, "record-start budget drifted");
        check(CommandDeadline.budgetMs("voice-cloud") == 240000, "voice-cloud budget drifted");
        check(CommandDeadline.budgetMs("voice-native") == 240000, "voice-native budget drifted");
        check(CommandDeadline.budgetMs("voice-standby") == 40000, "voice-standby budget drifted");
        check(CommandDeadline.budgetMs("voice") == 103000, "voice budget drifted");
        check(CommandDeadline.budgetMs("pair") == 70000, "pair budget drifted");
        check(CommandDeadline.budgetMs("spp") == 28000, "spp budget drifted");
        check(CommandDeadline.budgetMs("audio") == 15000, "audio budget drifted");
        check(CommandDeadline.budgetMs("status") == 12000, "unknown kind lost the host default");
        check(CommandDeadline.budgetMs(null) == 12000, "a null kind must not throw");

        // The observed failure: a voice-native round stopped at stream_microphone_stopped and
        // the pending state never expired, so notification forwarding stayed dead for the rest of
        // the session while the listener kept observing posts.
        check(!CommandDeadline.expired("voice-native", 0), "a fresh command stopped gating");
        check(!CommandDeadline.expired("voice-native", 240000), "expired exactly at the budget");
        check(!CommandDeadline.expired("voice-native", 240000 + CommandDeadline.GRACE_MS),
            "expired at the edge of the grace period");
        check(CommandDeadline.expired("voice-native", 240001 + CommandDeadline.GRACE_MS),
            "a voice round past its budget still silences notifications");
        check(CommandDeadline.expired("status", 60000), "a short command hung for a minute and still gated");

        // A long recording must not be cut loose early: record-stop legitimately takes minutes,
        // and releasing the gate at the voice budget would let notifications interleave with a
        // save that is still running.
        check(!CommandDeadline.expired("record-stop", 170000), "a recording still saving lost its gate");
        check(CommandDeadline.expired("record-stop", 180001 + CommandDeadline.GRACE_MS),
            "a recording past three minutes still gated");

        // An unknown age means the start was never recorded. Reading that as "infinitely old"
        // would silently disable the gate for every command written before this field existed.
        check(!CommandDeadline.expired("voice-native", -1), "a missing start time disabled the gate");

        System.out.println("command deadline checks passed");
    }
}
