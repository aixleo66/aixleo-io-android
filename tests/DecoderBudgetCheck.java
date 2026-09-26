package dev.xr.rayneo.probe;

/** Decode time budget scales with the recording (09-23: a complete 30-minute file hit the fixed 120 s). */
public final class DecoderBudgetCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) {
        long perMinute = 3000; // 20 ms packets
        check(RecordingDecoder.budgetMs(0) == 120000, "empty keeps the floor");
        check(RecordingDecoder.budgetMs(3 * perMinute) == 120000, "short recordings keep 120 s");
        check(RecordingDecoder.budgetMs(4 * perMinute) == 120000, "4 min: half is exactly the floor");
        check(RecordingDecoder.budgetMs(15 * perMinute) == 450000, "15 min -> 7.5 min (measured 66.6 s)");
        check(RecordingDecoder.budgetMs(30 * perMinute) == 900000, "30 min -> 15 min (failed at 120 s on 09-23)");
        check(RecordingDecoder.budgetMs(120 * perMinute) == 3600000, "120-minute cap -> 60 min, bounded");
        long raw30 = 21587520L / 240; // the 09-23 file: 89,948 packets
        check(RecordingDecoder.budgetMs(raw30) > 133000 * 3, "30-minute file gets over 3x the linear estimate");
        System.out.println("decoder budget checks passed");
    }

    private DecoderBudgetCheck() {}
}
