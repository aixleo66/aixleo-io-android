package dev.xr.rayneo.probe;

/** One read-only dashboard baseline query, to find out whether this firmware answers at all.
 *
 * <p>Background: a third-party project reports an A2UI card surface on LAUNCHER type 18
 * (`dashboard_config` to read the baseline, then `widget_install`). Its own source note says the
 * subset was "recovered from Strix OS 1.0.3.15" — our device runs 1.0.4.12, two versions later, so
 * whether the firmware still answers is unknown. This probe answers exactly that one question and
 * nothing more: it reads, it never installs or removes a card.
 *
 * <p>The request body is the one both of that project's independent implementations send, byte for
 * byte: {@code {"cmd":"dashboard_config","payload":{"version":1,"value":0}}}. Sending our own
 * invented shape would make a non-answer uninterpretable — we would not know whether the firmware
 * lacks the feature or simply rejected our payload.
 *
 * <p>A reply is only counted when it carries a {@code widgets_v2} array. Anything else — including
 * a well-formed LAUNCHER type 18 frame without that key — is ignored rather than treated as
 * success, because the interesting question is whether the card surface exists, not whether the
 * device emits type 18 at all (it does: weather uses the same type). */
final class LabDashboardQuery {
    static final String CMD = "dashboard_config";

    final String id;
    final long deadline;
    boolean sent, reply, done;
    /** Number of entries in widgets_v2; 0 is a valid baseline (no cards installed). */
    int widgets = -1;
    long replyAt = -1;
    String issue = "";

    LabDashboardQuery(String id, long now) { this.id = id; deadline = now + 12000; }

    void sent(boolean ok, long now) {
        if (done) return;
        tick(now); if (done) return;
        if (!ok) { issue = "send_failed"; done = true; return; }
        sent = true; finish();
    }

    /** @param widgetsV2 entry count, or -1 when the frame carried no widgets_v2 array at all. */
    boolean reply(String business, int type, int widgetsV2, long now) {
        if (done) return false;
        tick(now); if (done) return false;
        // 2026-09-23 on 1.0.4.12 the baseline came back on type 19 (request 18 / reply 19, like todo
        // 15 / 16); only accepting 18 turned a real answer into "failed". 18 is kept because the
        // third-party subset was recovered from older firmware. Weather rides 18 too, so the
        // widgets_v2 key is still what separates an answer from an unrelated frame.
        if (!"LAUNCHER".equals(business) || (type != 18 && type != 19) || widgetsV2 < 0) return false;
        reply = true; replyAt = now; widgets = widgetsV2; finish(); return true;
    }

    private void finish() { if (sent && reply) done = true; }

    void tick(long now) {
        if (!done && now >= deadline) {
            done = true;
            issue = sent ? "no_widgets_v2_reply" : "send_callback_timeout";
        }
    }

    /** Passed means the firmware answered with a baseline — not that any card was installed. */
    boolean passed() { return done && issue.isEmpty() && sent && reply && widgets >= 0; }
}
