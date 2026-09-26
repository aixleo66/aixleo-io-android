package dev.xr.rayneo.probe;

/** Weather push after a busy refusal is retried in a minute; otherwise the next check falls when the
 * last push (or failed attempt) turns a period old, counted from then -- not a full period from now. */
public final class WeatherRetryCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) {
        long now = 1790140000000L, period = WeatherRetry.PERIOD_MS;
        long stale = now - period - 1, fresh = now - 60000;
        for (String reason : WeatherRetry.BUSY) {
            check(WeatherRetry.deviceBusy(reason), "busy: " + reason);
            check(WeatherRetry.deviceBusy(reason + "；本次天气更新未确认"), "busy with suffix: " + reason);
            check(WeatherRetry.nextDelay(reason, stale, 0, now) == WeatherRetry.BUSY_RETRY_MS, "short retry when due: " + reason);
            check(WeatherRetry.nextDelay(reason, fresh, 0, now) == period - 60000, "no polling while still fresh: " + reason);
        }
        check(!WeatherRetry.deviceBusy(null) && !WeatherRetry.deviceBusy(""), "no message is not busy");
        check(!WeatherRetry.deviceBusy("天气获取失败，眼镜保留原显示，请稍后刷新"), "provider failure is not busy");
        check(WeatherRetry.nextDelay("阴 · 32°C\n当前位置天气已发送", now, now, now) == period, "success waits a period");
        check(WeatherRetry.nextDelay("天气获取失败", stale, now, now) == period, "provider failure waits a period from the attempt");
        check(WeatherRetry.deviceBusy("另一项眼镜操作正在进行；本次天气更新未确认"), "in-process lock is busy");
        check(WeatherRetry.BUSY.length == 5, "exactly the five known busy refusals");
        check(WeatherRetry.BUSY_RETRY_MS <= 60000, "busy retry within the 60 s acceptance");
        // 09-23: push 12:40:46, re-armed at 13:09:47 -> must fire when the push turns a period old,
        // not a full period after re-arming.
        long sent = now - 29 * 60000L;
        check(WeatherRetry.nextDelay("眼镜已连接；天气将按计划自动更新", sent, sent, now) == WeatherRetry.MIN_DELAY_MS,
            "a push older than the period is checked within the floor, not re-armed for a full period");
        long sent10 = now - 10 * 60000L;
        check(WeatherRetry.nextDelay("", sent10, sent10, now) == period - 10 * 60000L, "counts down from the last push");
        check(WeatherRetry.nextDelay("", 0, 0, now) == WeatherRetry.MIN_DELAY_MS, "never pushed: floor, not zero");
        check(WeatherRetry.nextDelay("", now + 3600000, 0, now) == WeatherRetry.MIN_DELAY_MS, "clock moved back: treated as due");
        check(period == 900000 && period == WeatherPolicy.PERIOD_MS, "15-minute period, one definition");
        System.out.println("weather retry checks passed");
    }

    private WeatherRetryCheck() {}
}
