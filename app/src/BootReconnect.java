package dev.xr.rayneo.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Phone restarted: the reconnect alarm and the connection service are gone, the preferences are
 * not (09-23 plan 0.5d-1). The official app declares RECEIVE_BOOT_COMPLETED for the same reason.
 * Starts a reconnect round unless the user disconnected on purpose; the session then starts from
 * the background under the companion-device exemption, as any other automatic attempt. */
public final class BootReconnect extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        context.getSharedPreferences("reconnect", Context.MODE_PRIVATE).edit().putLong("boot_ms", System.currentTimeMillis()).apply();
        AutoReconnect.afterBoot(context);
    }
}
