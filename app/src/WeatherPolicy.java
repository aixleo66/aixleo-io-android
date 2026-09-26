package dev.xr.rayneo.probe;

/** When the lens weather should be refreshed (09-23 plan stage 0.5).
 *
 * <p>Before: one fixed 30-minute period, and the "connection became ready" nudge could be missed,
 * so after a reconnect the lens kept an old temperature until the next period (user: "30 分钟的后台
 * 更新太傻了"). Now four triggers, each with its own staleness threshold:
 * <ul>
 * <li>periodic: 15 minutes -- the provider's current conditions refresh every 900 s
 *   (Open-Meteo {@code current.interval}); polling faster buys nothing</li>
 * <li>glasses became ready (every reconnect): push once, no age check -- user 09-23: waiting up to
 *   a period after connecting "体感太差" -- subject only to the spacing below</li>
 * <li>app came to the foreground: push if older than 10 minutes</li>
 * <li>phone moved at least 2 km (passive location, no extra fixes): push</li>
 * </ul>
 * Trigger-started attempts are at least {@link #MIN_SPACING_MS} apart. The periodic timer counts
 * down from the last push, not from whenever it was re-armed: before, a reconnect 29 minutes after a
 * push re-armed a full 30 minutes and the lens went 59 minutes without an update (measured 09-23,
 * push 12:40:46, reconnect 13:09:47, alarm 13:39:50).
 * The official app's own triggers are unknown (only a 3600 s timer seen in its logs); stage 1.0
 * captures them. Plain Java so it can be checked off-device. */
final class WeatherPolicy {
    static final long PERIOD_MS = 900000;
    static final long FOREGROUND_STALE_MS = 600000;
    static final float MOVE_METERS = 2000f;
    /** Minimum gap between two real attempts started by a trigger (not by the user's manual refresh). */
    static final long MIN_SPACING_MS = 120000;

    private WeatherPolicy() {}

    /** True when nothing was sent yet, the clock went backwards, or the last push is at least maxAge old. */
    static boolean stale(long lastSentMs, long nowMs, long maxAgeMs) {
        return lastSentMs <= 0 || nowMs < lastSentMs || nowMs - lastSentMs >= maxAgeMs;
    }

    /** Time left until {@link #stale} turns true; 0 when it already is. */
    static long untilStale(long lastMs, long nowMs, long maxAgeMs) {
        return stale(lastMs, nowMs, maxAgeMs) ? 0 : lastMs + maxAgeMs - nowMs;
    }
}
