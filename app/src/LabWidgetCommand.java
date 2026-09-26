package dev.xr.rayneo.probe;

/** One A2UI card install or uninstall, pinned to a single card id that only we use.
 *
 * <p>Background: 2026-09-23 the glasses on Strix OS 1.0.4.12 answered {@code dashboard_config}
 * with a {@code widgets_v2} baseline (one todo card, id "2"), so the card surface still exists two
 * versions after the 1.0.3.15 firmware the third-party subset was recovered from. Whether install
 * still works is exactly what this command finds out.
 *
 * <p>The shape follows that third-party project (iOS {@code A2UIProtocol.m}, HarmonyOS
 * {@code DeviceFeatures.ets}): request on LAUNCHER type 18, acknowledgement on type 19 carrying a
 * numeric {@code code} where 0 means success. The body is built by the caller; this class only
 * decides when the attempt is over and whether it passed.
 *
 * <p>Guards, all taken from that project because they are what keeps a card we cannot remove from
 * clobbering one we do not own: install needs a baseline read within the last 120 s, and refuses if
 * that baseline already holds our id. The id is fixed, so uninstall can only ever remove our card. */
final class LabWidgetCommand {
    // Kept short on purpose: the third-party author only proved single frames up to 509 bytes
    // (its own gate is packet + 10 <= 509) and marks larger messages unverified. A longer card
    // that failed would be indistinguishable from "this firmware no longer takes cards".
    static final String CARD_ID = "aix_card_01";
    static final String CARD_NAME = "AIX";
    static final String CARD_TEXT = "AIX 测试";
    static final String CATALOG = "https://rayneo.com/a2ui/catalogs/glasses-base/v1/catalog.json";
    static final long BASELINE_MAX_AGE_MS = 120000;
    /** Largest JSON body that stays inside that proven frame: 509 - 10 margin - 7 envelope bytes. */
    static final int MAX_BODY_BYTES = 492;

    final String id;
    final boolean install;
    final long deadline;
    boolean sent, reply, done;
    /** The acknowledgement's code; only meaningful once {@link #reply} is true. */
    int code;
    String errMsg = "", issue = "";

    LabWidgetCommand(String id, boolean install, long now) {
        this.id = id; this.install = install; deadline = now + 12000;
    }

    /** Why this attempt must not be sent at all, or null. Uninstall needs no baseline: the id is
     * pinned to ours, so the worst it can do is report that there was nothing to remove. */
    static String refusal(boolean install, long baselineAgeMs, boolean baselineHasCard, int bodyBytes) {
        if (bodyBytes < 0 || bodyBytes > MAX_BODY_BYTES) return "frame_over_budget";
        if (!install) return null;
        if (baselineAgeMs < 0 || baselineAgeMs > BASELINE_MAX_AGE_MS) return "baseline_missing_or_stale";
        if (baselineHasCard) return "card_already_present";
        return null;
    }

    void sent(boolean ok, long now) {
        if (done) return;
        tick(now); if (done) return;
        // A refusal that arrived before this callback is the more informative reason; keep it.
        if (!ok) { if (issue.isEmpty()) issue = "send_failed"; done = true; return; }
        sent = true; finish();
    }

    /** @param ackCode the frame's numeric code, or null when the frame carries none -- the
     * dashboard reply also rides type 19 and has no code, so it must not be taken for our ack. */
    boolean reply(String business, int type, Integer ackCode, String message, long now) {
        if (done) return false;
        tick(now); if (done) return false;
        if (!"LAUNCHER".equals(business) || type != 19 || ackCode == null) return false;
        reply = true; code = ackCode; errMsg = message == null ? "" : message;
        if (code != 0) issue = "device_refused";
        finish(); return true;
    }

    private void finish() { if (sent && reply) done = true; }

    void tick(long now) {
        if (!done && now >= deadline) {
            done = true;
            if (issue.isEmpty()) issue = sent ? "no_ack" : "send_callback_timeout";
        }
    }

    /** Passed means the glasses acknowledged with code 0 -- not that the card is visible. */
    boolean passed() { return done && issue.isEmpty() && sent && reply && code == 0; }
}
