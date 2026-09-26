package dev.xr.rayneo.probe;

import java.util.List;

/** Decides whether a CompanionDeviceManager association still needs to be requested.
 * Associations do not replace one another: com.rokid.sprite.aiapp was observed holding five
 * associations for one MAC, and only the earliest kept notify-on-nearby. So every request must be
 * preceded by a lookup, and a match must be reused rather than re-requested. */
final class CompanionAssociationPolicy {
    /** MAC comparison is case-insensitive: the synthetic address 02:00:00:00:ab:cd may be reported in lower case while
     * our own configuration carries it upper case. */
    static boolean holds(List<String> existing, String target) {
        if (target == null || target.isEmpty()) return false;
        for (String held : existing) if (target.equalsIgnoreCase(held)) return true;
        return false;
    }

    /** True when associate() should be called. Never true for a MAC already held, so a repeated
     * launch cannot accumulate duplicates. */
    static boolean needsRequest(List<String> existing, String target) {
        return !target.isEmpty() && !holds(existing, target);
    }

    /** onDeviceAppeared arrives only on API 31+; below that the association still grants the
     * background privileges but no presence callback, so the caller must keep its own reconnect. */
    static boolean presenceCallbackAvailable(int sdkInt) { return sdkInt >= 31; }
}
