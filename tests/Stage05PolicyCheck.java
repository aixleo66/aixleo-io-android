package dev.xr.rayneo.probe;

/** Stage 0.5 pure rules: weather staleness thresholds and reconnect backoff. */
public final class Stage05PolicyCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) {
        long now = 1790140000000L;
        long five = 300000;
        check(WeatherPolicy.stale(0, now, five), "never sent is stale");
        check(WeatherPolicy.stale(now + 1, now, five), "future timestamp is stale");
        check(!WeatherPolicy.stale(now - 299999, now, five), "just under the age is fresh");
        check(WeatherPolicy.stale(now - 300000, now, five), "exactly the age is stale");
        check(!WeatherPolicy.stale(now - 599999, now, WeatherPolicy.FOREGROUND_STALE_MS), "under 10 min fresh for foreground");
        check(WeatherPolicy.stale(now - 1, now, 0), "maxAge 0 is always stale");
        check(WeatherPolicy.untilStale(now - 60000, now, 300000) == 240000, "time left");
        check(WeatherPolicy.untilStale(now - 400000, now, 300000) == 0, "already stale: 0");
        check(WeatherPolicy.MIN_SPACING_MS < WeatherPolicy.FOREGROUND_STALE_MS
            && WeatherPolicy.FOREGROUND_STALE_MS < WeatherPolicy.PERIOD_MS, "thresholds ordered");
        check(WeatherPolicy.MOVE_METERS == 2000f && WeatherPolicy.MIN_SPACING_MS == 120000, "move trigger constants");

        long[] expected = {5000, 15000, 30000, 60000, 120000, 300000, 300000};
        for (int i = 0; i < expected.length; i++) check(ReconnectPolicy.delay(i) == expected[i], "delay " + i);
        check(ReconnectPolicy.delay(-3) == 5000, "negative attempt clamps");
        check(ReconnectPolicy.delay(1000) == ReconnectPolicy.STEADY_DELAY_MS, "steady after the ramp");
        check(!ReconnectPolicy.stable(0, now), "never ready is never stable");
        check(!ReconnectPolicy.stable(now - ReconnectPolicy.STABLE_MS + 1, now), "ready just under the window");
        check(ReconnectPolicy.stable(now - ReconnectPolicy.STABLE_MS, now), "ready for the window is stable");
        check(!ReconnectPolicy.exhausted(ReconnectPolicy.MAX_ATTEMPTS - 1), "last attempt allowed");
        check(ReconnectPolicy.exhausted(ReconnectPolicy.MAX_ATTEMPTS), "stops at the cap");
        // Budget of the delay table only; that the real path keeps counting is asserted on the
        // receiver source in test_stage0 (afterDrop keeps the count, session_exists increments).
        long total = 0;
        for (int i = 0; i < ReconnectPolicy.MAX_ATTEMPTS; i++) total += ReconnectPolicy.delay(i);
        check(total > 3600000 && total < 2 * 3600000, "delay table spans one to two hours: " + total);
        check(ReconnectPolicy.STABLE_MS >= 60000 && ReconnectPolicy.PRESENCE_QUIET_MS >= ReconnectPolicy.delay(4),
            "stable and presence-quiet windows");
        System.out.println("stage 0.5 policy checks passed");
    }

    private Stage05PolicyCheck() {}
}
