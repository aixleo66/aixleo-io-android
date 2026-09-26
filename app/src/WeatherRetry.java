package dev.xr.rayneo.probe;

/** How long to wait before the next weather check, given when and how the last push went.
 *
 * <p>Measured 2026-09-23: a 60-minute recording blocked the scheduled weather push, and because
 * every non-success waited the full 30-minute period, the lens showed a 1.5-hour-old temperature
 * with no hint why. When the only obstacle is that the glasses are busy, the push is retried after a
 * short interval instead; the busy check itself is a local file read, so polling it is cheap. */
final class WeatherRetry {
    static final long PERIOD_MS = WeatherPolicy.PERIOD_MS;
    /** Floor for a scheduled check, so a due-but-not-yet-attempted state cannot spin. */
    static final long MIN_DELAY_MS = 60000;
    static final long BUSY_RETRY_MS = 60000;
    /** The refusals TodoGlassesClient raises for a device or client that is merely busy: the four of
     * ensureIdle, plus its in-process "another glasses operation" lock. */
    static final String[] BUSY = {"上一项眼镜操作尚未完成", "请先结束当前录音", "请先结束当前语音问答", "请先退出眼镜当前阅读页",
        "另一项眼镜操作正在进行"};

    private WeatherRetry() {}

    static boolean deviceBusy(String message) {
        if (message == null) return false;
        for (String reason : BUSY) if (message.contains(reason)) return true;
        return false;
    }

    /** A busy refusal is retried soon only while an update is actually due; a manual refresh that hit
     * a busy device while the reading was still fresh waits for the normal schedule instead of polling.
     * Otherwise the next check is when the last push -- or, after a failure, the last attempt --
     * becomes a period old: counted from those times, not from now. */
    static long nextDelay(String lastResult, long lastSentMs, long lastAttemptMs, long nowMs) {
        boolean due = WeatherPolicy.stale(lastSentMs, nowMs, PERIOD_MS);
        if (due && deviceBusy(lastResult)) return BUSY_RETRY_MS;
        long wait = Math.max(WeatherPolicy.untilStale(lastSentMs, nowMs, PERIOD_MS), WeatherPolicy.untilStale(lastAttemptMs, nowMs, PERIOD_MS));
        return Math.max(MIN_DELAY_MS, wait);
    }
}
