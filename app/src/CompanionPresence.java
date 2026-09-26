package dev.xr.rayneo.probe;

import android.annotation.TargetApi;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;

/** Bound by the system once an association exists, which is what puts this package into
 * "Bound Companion Applications" and grants the background lifetime — the association alone binds
 * nothing without a service to bind to. Presence callbacks arrive from API 31.
 *
 * Appearing also starts a background reconnect round (09-23 plan stage 0.5, user-approved) when
 * the user has not disconnected on purpose, or pulls a waiting attempt forward. First seen arriving
 * on the real device 09-23 14:57:13 (reconnect prefs {@code nearby_ms}). */
@TargetApi(31)
public final class CompanionPresence extends CompanionDeviceService {
    /** A hint, not a connection judgement: the system reports the associated device being nearby,
     * which is not the same as our own session being connected. */
    static volatile boolean nearby;
    static volatile long changedAt;
    static volatile int appearances;

    @Override public void onDeviceAppeared(AssociationInfo association) {
        nearby = true; changedAt = System.currentTimeMillis(); appearances++;
        getSharedPreferences("reconnect", MODE_PRIVATE).edit().putLong("nearby_ms", changedAt).apply();
        if (!SdkProbeActivity.connectionActive) AutoReconnect.onPresence(this);
    }

    @Override public void onDeviceDisappeared(AssociationInfo association) {
        nearby = false; changedAt = System.currentTimeMillis();
    }
}
