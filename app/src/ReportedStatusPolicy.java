package dev.xr.rayneo.probe;

/** Read-only Lab diagnostics: retain scalar states without guessing enum meanings. */
final class ReportedStatusPolicy {
    static boolean include(String key, Object value) {
        if (!("battery".equals(key) || "brightness".equals(key)
                || "automaticBrightness".equals(key) || "batt_temp".equals(key)
                || "isCharging".equals(key) || "chargeType".equals(key)
                || "screenStatus".equals(key) || "hallStatus".equals(key)
                || "micStatus".equals(key) || "focusMode".equals(key))) return false;
        return scalar(value);
    }
    static boolean includeFocus(String path, Object value) {
        if (!("enable".equals(path) || "enableGlassClose".equals(path)
                || "policy.auto".equals(path) || "policy.type".equals(path)
                || "policy.begin".equals(path) || "policy.end".equals(path)
                || "policy.weekday".equals(path))) return false;
        // Keep numeric/boolean raw values only. Strings, schedules and arrays
        // are described by shape, not silently converted or copied wholesale.
        return scalar(value);
    }
    private static boolean scalar(Object value) {
        if (value instanceof Boolean) return true;
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return !Double.isNaN(number) && !Double.isInfinite(number);
    }
}
