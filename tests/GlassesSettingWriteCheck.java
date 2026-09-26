package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** The write path is the first thing in this project that can change a user's glasses, so the
 * ranges are pinned here rather than trusted to the UI. Every accepted value cites where it comes
 * from; every refusal exists because the alternative is writing something the user did not ask for. */
public final class GlassesSettingWriteCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    static void refuses(String target, JSONObject request, String label) throws Exception {
        try { GlassesSettingWrite.body(target, request); }
        catch (GlassesSettingWrite.Refusal expected) { return; }
        throw new AssertionError("accepted what it should refuse: " + label);
    }

    public static void main(String[] args) throws Exception {
        // ---- brightness: type 2, 1..17 --------------------------
        check(GlassesSettingWrite.type("brightness") == 2, "brightness is type 2, not 5");
        JSONObject bright = GlassesSettingWrite.body("brightness", new JSONObject().put("value", 9));
        check("brightness_change".equals(bright.getString("cmd")), "brightness cmd");
        check(bright.getJSONObject("payload").getInt("value") == 9, "brightness value");
        check(bright.getJSONObject("payload").getInt("mode") == 0, "brightness mode");
        check("".equals(bright.getJSONObject("payload").getString("data")), "brightness data empty");
        GlassesSettingWrite.body("brightness", new JSONObject().put("value", 1));
        GlassesSettingWrite.body("brightness", new JSONObject().put("value", 17));
        refuses("brightness", new JSONObject().put("value", 0), "brightness 0 (scale starts at 1)");
        refuses("brightness", new JSONObject().put("value", 18), "brightness 18");
        refuses("brightness", new JSONObject(), "brightness with no value");

        // ---- auto lock: only the seven official options -----------
        check(GlassesSettingWrite.type("auto_lock") == 5, "auto_lock is type 5");
        JSONObject lock = GlassesSettingWrite.body("auto_lock", new JSONObject().put("value", 25));
        check("auto_lock_time".equals(lock.getString("cmd")), "auto_lock cmd");
        check(lock.getJSONObject("payload").getInt("value") == 25, "auto_lock value");
        // mode and data are explicit nulls here, unlike every other write. isNull() alone cannot
        // show that: it is also true for a key that was never put, so dropping both fields entirely
        // would keep this green while changing the bytes on the wire. Assert presence as well.
        JSONObject lockPayload = lock.getJSONObject("payload");
        check(lockPayload.has("mode") && lockPayload.has("data"), "auto_lock must carry both keys");
        check(lockPayload.opt("mode") == JSONObject.NULL, "auto_lock mode must be an explicit null");
        check(lockPayload.opt("data") == JSONObject.NULL, "auto_lock data must be an explicit null");
        for (int option : new int[]{5, 10, 15, 25, 40, 60, 120})
            GlassesSettingWrite.body("auto_lock", new JSONObject().put("value", option));
        refuses("auto_lock", new JSONObject().put("value", 30), "auto_lock 30 is not an option");
        refuses("auto_lock", new JSONObject().put("value", 0), "auto_lock 0");

        // ---- wakeup: three fields in one packet, data is a JSON string --------------
        JSONObject wake = GlassesSettingWrite.body("wakeup", new JSONObject()
            .put("headup_switch", 1).put("headup_degree", 20).put("crown_switch", 0));
        check("wakeup_config".equals(wake.getString("cmd")), "wakeup cmd");
        check(wake.getJSONObject("payload").getInt("value") == 0, "wakeup value is 0");
        JSONObject wakeData = new JSONObject(wake.getJSONObject("payload").getString("data"));
        check(wakeData.getInt("headup_degree") == 20, "degree survives the string round trip");
        check(wakeData.getInt("crown_switch") == 0, "crown_switch survives");
        GlassesSettingWrite.body("wakeup", new JSONObject()
            .put("headup_switch", 0).put("headup_degree", 90).put("crown_switch", 3));
        refuses("wakeup", new JSONObject().put("headup_switch", 1).put("headup_degree", 91)
            .put("crown_switch", 0), "degree 91");
        // crown_switch is a 0..3 mode, not a boolean -- 4 must not be accepted.
        refuses("wakeup", new JSONObject().put("headup_switch", 1).put("headup_degree", 20)
            .put("crown_switch", 4), "crown_switch 4");
        // A partial request must be refused, not completed with zeros: the three fields travel in
        // one packet, so a missing one would overwrite a setting the user never touched.
        refuses("wakeup", new JSONObject().put("headup_switch", 1).put("headup_degree", 20),
            "wakeup missing crown_switch");

        // ---- crown: value carries direction, data is a JSON string ------------------
        JSONObject crown = GlassesSettingWrite.body("crown", new JSONObject()
            .put("direction", 1).put("double", 3).put("longPress", 1));
        check("crown_config".equals(crown.getString("cmd")), "crown cmd");
        check(crown.getJSONObject("payload").getInt("value") == 1, "direction rides in value");
        JSONObject crownData = new JSONObject(crown.getJSONObject("payload").getString("data"));
        check(crownData.getInt("double") == 3 && crownData.getInt("longPress") == 1, "crown data");
        // 全天智记 (6) is on the double-tap page only; the screen-off long-press page omits it.
        GlassesSettingWrite.body("crown", new JSONObject().put("direction", 0).put("double", 6).put("longPress", 7));
        refuses("crown", new JSONObject().put("direction", 0).put("double", 0).put("longPress", 6),
            "action 6 on long press");
        refuses("crown", new JSONObject().put("direction", 2).put("double", 0).put("longPress", 0), "direction 2");
        refuses("crown", new JSONObject().put("direction", 1).put("double", 10).put("longPress", 0), "action 10");

        // ---- head: direction rides in mode, data stays empty ------------------------
        JSONObject head = GlassesSettingWrite.body("head", new JSONObject().put("enabled", 1).put("mode", 0));
        check("head_gestures".equals(head.getString("cmd")), "head cmd");
        check(head.getJSONObject("payload").getInt("value") == 1, "enabled rides in value");
        check(head.getJSONObject("payload").getInt("mode") == 0, "direction rides in mode");
        check("".equals(head.getJSONObject("payload").getString("data")), "head data empty");
        refuses("head", new JSONObject().put("enabled", 1).put("mode", 2), "head mode 2");
        refuses("head", new JSONObject().put("enabled", 1), "head missing mode");

        // ---- unknown targets and malformed values ---------------------------------------------
        refuses("display_height", new JSONObject().put("value", 1), "display height is not writable yet");
        refuses("brightness", null, "null request");
        // A non-integer must not be truncated into a different setting than the user picked.
        refuses("brightness", new JSONObject().put("value", 9.5), "fractional brightness");
        refuses("brightness", new JSONObject().put("value", "9"), "brightness as string");
        try { GlassesSettingWrite.type("nope"); throw new AssertionError("unknown target got a type"); }
        catch (GlassesSettingWrite.Refusal expected) { }

        System.out.println("glasses setting write checks passed");
    }

    private GlassesSettingWriteCheck() {}
}
