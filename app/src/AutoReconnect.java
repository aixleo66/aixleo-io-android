package dev.xr.rayneo.probe;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Brings the glasses session back after an unplanned loss (09-23 plan stage 0.5).
 *
 * <p>Triggers: the session ending with a disconnect/failure after it had authenticated
 * ({@link #afterDrop}), and the system reporting the associated glasses nearby again
 * ({@link #onPresence}). Each attempt starts the same session the "连接眼镜" button starts, marked
 * {@code auto_reconnect}. Nothing happens when the user disconnected on purpose ({@code auto_connect}
 * false) or a session already exists.
 *
 * <p>09-23 on device: starting the session as an Activity from the background was refused by MIUI
 * until the user granted "show on top from background". Sessions are now hosted by the
 * connectedDevice foreground service ({@link ConnectionService#startSession}); a companion-device app
 * may start one from the background. A refused start throws and is recorded as
 * {@code start_refused:<type>}; a start that went through stamps {@code session_started_ms}.
 * State lives in the "reconnect" preferences. */
public final class AutoReconnect extends BroadcastReceiver {
    private static final String PREFS = "reconnect";

    /** A session that reached ready and stayed up at least STABLE_MS starts a fresh round; one that
     * dropped sooner -- or never became ready at all -- continues the current backoff, so a failure
     * that recurs right after authenticating cannot turn into a fast endless loop (the ready time is session-specific: the ready time must be this session's, not the last one ever recorded). */
    static void afterDrop(Context context, long sessionReadyAtMs) {
        SharedPreferences p = prefs(context);
        boolean stable = ReconnectPolicy.stable(sessionReadyAtMs, System.currentTimeMillis());
        begin(context, "drop", stable ? 0 : p.getInt("attempt", 0));
    }

    /** "Glasses nearby". While a round is waiting on its backoff, the glasses being back means the
     * next attempt runs now instead of at the end of the wait (09-23: they were back at 14:57:13 and
     * the attempt waited until 15:00:00); the count is kept, so a flapping signal still ends the round.
     * Without a pending round it starts one only if the last attempt is not recent (review S2). */
    static void onPresence(Context context) {
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        long since = now - p.getLong("last_attempt_ms", 0); // negative after a clock step back: treat as long ago
        if (p.getLong("next_ms", 0) > now) {
            if (since < 0 || since >= ReconnectPolicy.PRESENCE_MIN_GAP_MS) {
                p.edit().putString("reason", "nearby_pulled_forward").apply();
                schedule(context, ReconnectPolicy.PRESENCE_RETRY_MS);
            }
            return;
        }
        if (since >= 0 && since < ReconnectPolicy.PRESENCE_QUIET_MS) return;
        begin(context, "nearby", 0);
    }

    /** Phone restarted (BootReconnect): the next_ms left from before refers to an alarm that did not
     * survive, so a fresh round starts. Nothing happens if the user had disconnected on purpose. */
    static void afterBoot(Context context) {
        prefs(context).edit().remove("next_ms").apply();
        begin(context, "boot", 0);
    }

    /** The session is ready again: stop retrying. The attempt count is kept until the session has
     * proven stable (see afterDrop). */
    static void connected(Context context) {
        cancel(context);
        prefs(context).edit().putLong("connected_ms", System.currentTimeMillis()).putString("last_outcome", "connected").apply();
    }

    /** Called by a session an automatic attempt started: the start really went through. */
    static void sessionStarted(Context context) {
        prefs(context).edit().putLong("session_started_ms", System.currentTimeMillis()).apply();
    }

    /** The user disconnected: no retry is pending any more. */
    static void stop(Context context) { cancel(context); prefs(context).edit().putInt("attempt", 0).putString("last_outcome", "user_disconnect").apply(); ConnectionService.reconnectEnded(); }

    /** A round is waiting for its next attempt; the host service stays up for it (ConnectionService.releaseIfIdle).
     * The alarm itself must still exist: a force-stop or reboot drops alarms but not the next_ms
     * preference, and a stale value must not keep the service up forever (review round 11). */
    static boolean pending(Context context) {
        if (!autoConnect(context) || !prefs(context).contains("next_ms")) return false;
        return PendingIntent.getBroadcast(context, 35, new Intent(context, AutoReconnect.class),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE) != null;
    }

    /** A round ended without a session: nothing is pending, and the host may stop. */
    private static void ended(Context context, String outcome) {
        prefs(context).edit().putString("last_outcome", outcome).remove("next_ms").apply();
        ConnectionService.reconnectEnded();
    }

    private static void begin(Context context, String reason, int attempt) {
        if (!autoConnect(context)) return;
        prefs(context).edit().putInt("attempt", attempt).putString("reason", reason).apply();
        schedule(context, ReconnectPolicy.delay(attempt));
    }

    @Override public void onReceive(Context context, Intent intent) {
        run(context, intent == null ? -1 : intent.getIntExtra("gen", -1));
    }

    /** Each schedule() is one period with its own number; the alarm and the in-process timer both
     * carry it and only the first to arrive runs the attempt. A late alarm the timer could no longer
     * withdraw, or one delivered after connected(), finds its number already used (review round 7). */
    private void run(Context context, int gen) {
        MAIN.removeCallbacks(TIMER);
        SharedPreferences p = prefs(context);
        if (gen != p.getInt("gen", 0) || gen == p.getInt("done_gen", -1)) return;
        p.edit().putInt("done_gen", gen).apply();
        int attempt = p.getInt("attempt", 0);
        if (!autoConnect(context)) { ended(context, "user_disconnect"); return; }
        if (ReconnectPolicy.exhausted(attempt)) { ended(context, "gave_up"); return; }
        if (SdkProbeActivity.connectionActive) {
            // An attempt (or the user) is still connecting: look again later, counting it, so a session
            // that never becomes ready also backs off and ends (review S3); connected() cancels this.
            p.edit().putInt("attempt", attempt + 1).putString("last_outcome", "session_exists").apply();
            schedule(context, ReconnectPolicy.delay(attempt + 1));
            return;
        }
        String outcome;
        try {
            String address = CloudConfig.load(context).optString("glasses_address");
            if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) outcome = "no_address";
            else if (android.os.Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) outcome = "no_permission";
            else {
                // Same extras as CloudActivity.connectGlasses(automatic=true): never repair or enter pairing mode.
                ConnectionService.startSession(context, new Intent()
                    .putExtra("target_address", address).putExtra("connect", true).putExtra("pairing_ready", true)
                    .putExtra("repair_unbonded", false).putExtra("pairing_mode_attempt", false)
                    .putExtra("business_probe", "session").putExtra("session_seconds", 1800)
                    .putExtra("companion_ui", true).putExtra("auto_reconnect", true));
                // The service starts asynchronously; the session stamps session_started_ms when it runs.
                outcome = "start_requested";
            }
        } catch (RuntimeException e) { outcome = "start_refused:" + e.getClass().getSimpleName(); }
        catch (Exception e) { outcome = "config_unreadable"; }
        p.edit().putInt("attempt", attempt + 1).putLong("last_attempt_ms", System.currentTimeMillis())
            .putString("last_outcome", outcome).putInt("total", p.getInt("total", 0) + 1).apply();
        if (outcome.equals("no_address") || outcome.equals("no_permission")) { ended(context, outcome); return; }
        // Checked again later: if this attempt connects, connected() cancels it. An attempt that fails
        // before authenticating does not call afterDrop, so this chain is what keeps retrying.
        schedule(context, ReconnectPolicy.delay(attempt + 1));
    }

    private static boolean autoConnect(Context context) {
        return context.getSharedPreferences("assistant", Context.MODE_PRIVATE).getBoolean("auto_connect", false);
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static PendingIntent alarm(Context context, int gen) {
        return PendingIntent.getBroadcast(context, 35, new Intent(context, AutoReconnect.class).putExtra("gen", gen),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** In-process timer next to the alarm: the inexact alarm was deferred ~1.5 min on 09-23 while the
     * process was alive. The alarm still covers a process the system killed or a CPU asleep. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context app;
    private static volatile int timerGen;
    private static final Runnable TIMER = () -> {
        Context c = app; if (c == null) return;
        c.getSystemService(AlarmManager.class).cancel(alarm(c, 0)); // matches regardless of extras
        new AutoReconnect().run(c, timerGen);
    };

    private static void schedule(Context context, long delayMs) {
        app = context.getApplicationContext();
        SharedPreferences p = prefs(context);
        int gen = p.getInt("gen", 0) + 1;
        p.edit().putInt("gen", gen).putLong("next_ms", System.currentTimeMillis() + delayMs).apply();
        timerGen = gen;
        MAIN.removeCallbacks(TIMER); MAIN.postDelayed(TIMER, delayMs);
        context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs, alarm(context, gen));
    }

    private static void cancel(Context context) {
        MAIN.removeCallbacks(TIMER);
        context.getSystemService(AlarmManager.class).cancel(alarm(context, 0));
        SharedPreferences p = prefs(context);
        p.edit().remove("next_ms").putInt("done_gen", p.getInt("gen", 0)).apply(); // an alarm already in flight is void
    }
}
