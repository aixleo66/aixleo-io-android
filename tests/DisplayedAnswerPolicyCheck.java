package dev.xr.rayneo.probe;

final class DisplayedAnswerPolicyCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // The regression this class exists for. After the phone finishes sending, the round sets
        // awaitingDisplayComplete=true and followupUntil=0, then waits for the glasses. The receipt
        // must be accepted even though no deadline exists yet: it is what creates the deadlines.
        check(DisplayedAnswerPolicy.continuable(true, true, 100_000L, 0L),
              "Display-complete receipt rejected while awaiting it");

        // Measured 2026-09-20: delivery at 50.48s, receipt at 86.10s, followupUntil still 0.
        // Before the fix this returned false and only the 60s fallback could close the round.
        check(DisplayedAnswerPolicy.continuable(true, true, 86_100L, 0L),
              "Late receipt rejected; round can only close from the fallback");

        // Once the windows are running the deadline governs again.
        check(DisplayedAnswerPolicy.continuable(true, false, 5_000L, 10_000L),
              "Continuation lost inside the follow-up window");
        check(!DisplayedAnswerPolicy.continuable(true, false, 10_000L, 10_000L),
              "Continuation accepted exactly at the deadline");
        check(!DisplayedAnswerPolicy.continuable(true, false, 15_000L, 10_000L),
              "Continuation accepted after the follow-up window closed");

        // No answer on the lens: nothing is continuable regardless of the other inputs.
        check(!DisplayedAnswerPolicy.continuable(false, true, 100L, 0L),
              "Continuation accepted with no reading round");
        check(!DisplayedAnswerPolicy.continuable(false, false, 5_000L, 10_000L),
              "Continuation accepted with no reading round inside a window");

        System.out.println("displayed-answer policy passed");
    }
}
