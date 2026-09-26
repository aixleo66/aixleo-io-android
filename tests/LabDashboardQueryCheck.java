package dev.xr.rayneo.probe;

/** The A2UI baseline probe answers exactly one question: does this firmware reply with a
 * widgets_v2 baseline at all. These checks pin the two ways that answer could be faked —
 * counting an unrelated type 18 frame (weather rides the same type), and calling a timeout
 * a pass. */
public final class LabDashboardQueryCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) {
        // ---- happy path: send acked, then a baseline with two cards ----------------------
        LabDashboardQuery q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        check(!q.done, "not done before a reply");
        check(q.reply("LAUNCHER", 18, 2, 20), "a widgets_v2 frame is the reply");
        check(q.done && q.passed(), "send + baseline = passed");
        check(q.widgets == 2, "widget count recorded");

        // ---- an empty baseline is still an answer ---------------------------------------
        q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        check(q.reply("LAUNCHER", 18, 0, 20), "zero cards is a valid baseline");
        check(q.passed(), "empty baseline still passes -- the question was whether it answers");

        // ---- weather also rides LAUNCHER type 18: no widgets_v2 means not our answer -----
        q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        check(!q.reply("LAUNCHER", 18, -1, 20), "type 18 without widgets_v2 is not an answer");
        check(!q.done, "and must not finish the probe");
        check(!q.passed(), "and must not pass");

        // ---- 1.0.4.12 answers on type 19 (device-observed 2026-09-23) ---------------------
        q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        check(q.reply("LAUNCHER", 19, 1, 20), "the type 19 baseline is the reply");
        check(q.passed() && q.widgets == 1, "type 19 baseline passes");
        q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        check(!q.reply("LAUNCHER", 19, -1, 20), "type 19 without widgets_v2 (e.g. an install ack) is not an answer");

        // ---- wrong business or type is ignored ------------------------------------------
        q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        check(!q.reply("NOTIFICATION", 18, 3, 20), "other business ignored");
        check(!q.reply("LAUNCHER", 4, 3, 20), "other type ignored");
        check(!q.passed(), "still waiting");

        // ---- send failure ----------------------------------------------------------------
        q = new LabDashboardQuery("id", 0);
        q.sent(false, 10);
        check(q.done && !q.passed() && q.issue.equals("send_failed"), "send failure is terminal");

        // ---- timeouts: the two states are distinguishable --------------------------------
        q = new LabDashboardQuery("id", 0);
        q.tick(12000);
        check(q.done && q.issue.equals("send_callback_timeout"), "timeout before send ack");
        check(!q.passed(), "a timeout is never a pass");

        q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        q.tick(12000);
        check(q.done && q.issue.equals("no_widgets_v2_reply"), "timeout after send ack");
        check(!q.passed(), "a timeout is never a pass");

        // ---- a reply after the deadline must not resurrect the probe ---------------------
        q = new LabDashboardQuery("id", 0);
        q.sent(true, 10);
        q.tick(12000);
        check(!q.reply("LAUNCHER", 18, 5, 12500), "late reply rejected");
        check(!q.passed(), "late reply cannot turn a timeout into a pass");

        System.out.println("dashboard query checks passed");
    }

    private LabDashboardQueryCheck() {}
}
