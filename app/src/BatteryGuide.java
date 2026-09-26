package dev.xr.rayneo.probe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/** Battery optimization "不限制" (09-23 plan 0.5d-2). Without it MIUI may kill the process and the
 * connection and background weather are not kept (background location also depends on the process staying alive). The official app declares
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and asks in-app; before, users had to find the setting. */
final class BatteryGuide {
    private BatteryGuide() {}

    static boolean unrestricted(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static final int ALREADY = 0, OPENED = 1, UNAVAILABLE = 2;

    /** Opens the system prompt; falls back to the list page where a ROM does not handle it.
     * UNAVAILABLE (neither page opens) is told apart from ALREADY (review 09-23: both returned false
     * and the page then claimed "already unrestricted"). */
    static int ask(Activity activity) {
        if (unrestricted(activity)) return ALREADY;
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException e) {
            try { activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (RuntimeException ignored) { return UNAVAILABLE; }
        }
        return OPENED;
    }

    /** Asked once, after the user connects on purpose; the weather page keeps a button for later. */
    static void askOnce(Activity activity) {
        android.content.SharedPreferences p = activity.getSharedPreferences("assistant", Context.MODE_PRIVATE);
        if (p.getBoolean("battery_asked", false) || unrestricted(activity)) return;
        // Marked only once a page really opened, so a ROM without either page is asked again next time.
        if (ask(activity) == OPENED) p.edit().putBoolean("battery_asked", true).apply();
    }
}
