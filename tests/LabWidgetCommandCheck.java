package dev.xr.rayneo.probe;

/** Card install/uninstall must not report success unless the glasses said code 0, must not take
 * the dashboard reply (same type 19, no code) for its ack, and must refuse to install without a
 * fresh baseline or over an existing card of ours. */
public final class LabWidgetCommandCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) {
        // ---- happy path ------------------------------------------------------------------
        LabWidgetCommand c = new LabWidgetCommand("id", true, 0);
        c.sent(true, 10);
        check(!c.done, "not done before the ack");
        check(c.reply("LAUNCHER", 19, 0, "", 20), "code 0 on type 19 is the ack");
        check(c.done && c.passed(), "send + code 0 = passed");

        // ---- ack may land before the send callback --------------------------------------
        c = new LabWidgetCommand("id", false, 0);
        check(c.reply("LAUNCHER", 19, 0, "", 5), "early ack accepted");
        check(!c.done, "but not done until the send callback");
        c.sent(true, 10);
        check(c.passed(), "then passes");

        // ---- a refusal is recorded, not passed ------------------------------------------
        c = new LabWidgetCommand("id", true, 0);
        c.sent(true, 10);
        check(c.reply("LAUNCHER", 19, 3, "bad", 20), "a non-zero code is still the ack");
        check(c.done && !c.passed() && c.issue.equals("device_refused") && c.errMsg.equals("bad"),
            "non-zero code = device_refused, message kept");

        // ---- frames that are not our ack ------------------------------------------------
        c = new LabWidgetCommand("id", true, 0);
        c.sent(true, 10);
        check(!c.reply("LAUNCHER", 19, null, "", 20), "the dashboard reply (no code) is not our ack");
        check(!c.reply("LAUNCHER", 18, 0, "", 20), "type 18 is not an ack");
        check(!c.reply("NOTIFICATION", 19, 0, "", 20), "other business ignored");
        check(!c.done, "still waiting");

        // ---- send failure and timeouts --------------------------------------------------
        c = new LabWidgetCommand("id", true, 0);
        c.sent(false, 10);
        check(c.done && c.issue.equals("send_failed") && !c.passed(), "send failure is terminal");
        c = new LabWidgetCommand("id", true, 0);
        c.tick(12000);
        check(c.done && c.issue.equals("send_callback_timeout"), "timeout before send ack");
        c = new LabWidgetCommand("id", true, 0);
        c.sent(true, 10);
        c.tick(12000);
        check(c.done && c.issue.equals("no_ack") && !c.passed(), "timeout after send ack");
        check(!c.reply("LAUNCHER", 19, 0, "", 12500), "late ack cannot resurrect it");
        check(!c.passed(), "a timeout is never a pass");

        // ---- install preconditions ------------------------------------------------------
        check("baseline_missing_or_stale".equals(LabWidgetCommand.refusal(true, -1, false, 400)), "no baseline");
        check("baseline_missing_or_stale".equals(LabWidgetCommand.refusal(true, 120001, false, 400)), "stale baseline");
        check("card_already_present".equals(LabWidgetCommand.refusal(true, 1000, true, 400)), "never overwrite our id");
        check(LabWidgetCommand.refusal(true, 1000, false, 400) == null, "fresh baseline without our card: go");
        check(LabWidgetCommand.refusal(true, 120000, false, 400) == null, "120 s is still fresh");
        check(LabWidgetCommand.refusal(false, -1, false, 100) == null, "uninstall needs no baseline");

        // ---- frame budget: 509 proven by the third party, minus its 10-byte margin, minus 7 envelope bytes
        check(LabWidgetCommand.MAX_BODY_BYTES == 509 - 10 - 7, "budget derived from the proven frame");
        check(LabWidgetCommand.refusal(true, 1000, false, 492) == null, "492 bytes fits");
        check("frame_over_budget".equals(LabWidgetCommand.refusal(true, 1000, false, 493)), "493 bytes is over");
        check("frame_over_budget".equals(LabWidgetCommand.refusal(false, -1, false, 493)), "uninstall is size-gated too");
        check("frame_over_budget".equals(LabWidgetCommand.refusal(true, 1000, false, -1)), "unknown size is refused");

        // ---- a device refusal that arrived first survives a later send failure ----------
        c = new LabWidgetCommand("id", true, 0);
        check(c.reply("LAUNCHER", 19, 5, "nope", 5), "early refusal recorded");
        c.sent(false, 10);
        check(c.done && c.issue.equals("device_refused") && c.code == 5, "send failure does not erase the device's reason");

        // ---- the id is ours and cannot collide with firmware cards ----------------------
        check(LabWidgetCommand.CARD_ID.matches("[a-z0-9_]{10,63}") && !LabWidgetCommand.CARD_ID.matches("\\d+"),
            "pinned id is ours, not a numeric firmware id like the todo card's \"2\"");

        System.out.println("widget command checks passed");
    }

    private LabWidgetCommandCheck() {}
}
