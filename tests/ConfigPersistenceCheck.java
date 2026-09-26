package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** Config drift (09-23): values equal to the default must not be frozen into storage, and the
 * retired recording-cap default 60 must read as "inherited". */
public final class ConfigPersistenceCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        JSONObject defaults = new JSONObject().put("recording_max_minutes", 30).put("assistant_followup_seconds", 10)
            .put("deepseek_key", "").put("streaming_asr", false);

        // ---- sparse: equal-to-default keys are dropped, chosen values kept ---------------
        JSONObject value = new JSONObject().put("recording_max_minutes", 30).put("assistant_followup_seconds", 25)
            .put("deepseek_key", "sk-x").put("streaming_asr", false).put("glasses_address", "AA");
        JSONObject stored = ConfigPersistence.sparse(value, defaults);
        check(!stored.has("recording_max_minutes"), "default-equal number not stored");
        check(!stored.has("streaming_asr"), "default-equal boolean not stored");
        check(stored.optInt("assistant_followup_seconds") == 25, "user-chosen value kept");
        check("sk-x".equals(stored.optString("deepseek_key")), "non-default secret kept");
        check("AA".equals(stored.optString("glasses_address")), "keys without a default kept");
        check(value.has("recording_max_minutes"), "input object is not mutated");

        // ---- numbers compare by value ------------------------------------------------
        check(ConfigPersistence.same(30, 30L) && ConfigPersistence.same(30, 30.0), "30 == 30L == 30.0");
        check(!ConfigPersistence.same(30, 60), "30 != 60");
        check(!ConfigPersistence.same(null, 30) && ConfigPersistence.same(null, null), "null handling");

        // ---- retired default 60 is dropped on load; a real choice is kept ------------
        JSONObject frozen = new JSONObject().put("recording_max_minutes", 60);
        check(ConfigPersistence.dropRetiredDefaults(frozen) == 1 && !frozen.has("recording_max_minutes"), "frozen 60 dropped");
        JSONObject frozenLong = new JSONObject().put("recording_max_minutes", 60L);
        check(ConfigPersistence.dropRetiredDefaults(frozenLong) == 1, "frozen 60L dropped");
        // The settings screen stores this field as a string on device.
        JSONObject frozenString = new JSONObject().put("recording_max_minutes", "60");
        check(ConfigPersistence.dropRetiredDefaults(frozenString) == 1, "frozen \"60\" (string) dropped");
        JSONObject frozenWindow = new JSONObject().put("assistant_followup_seconds", 15);
        check(ConfigPersistence.dropRetiredDefaults(frozenWindow) == 1, "frozen follow-up 15 dropped");
        check(!ConfigPersistence.sparse(new JSONObject().put("recording_max_minutes", "30"), defaults).has("recording_max_minutes"),
            "string \"30\" equals default 30 and is not stored");
        JSONObject chosen = new JSONObject().put("recording_max_minutes", 45);
        check(ConfigPersistence.dropRetiredDefaults(chosen) == 0 && chosen.optInt("recording_max_minutes") == 45, "45 kept");

        // ---- end to end: frozen file + load merge + save round trip ------------------
        JSONObject loaded = new JSONObject().put("recording_max_minutes", 60).put("assistant_followup_seconds", 25);
        ConfigPersistence.dropRetiredDefaults(loaded);
        for (java.util.Iterator<String> n = defaults.keys(); n.hasNext();) { String k = n.next(); if (!loaded.has(k)) loaded.put(k, defaults.get(k)); }
        check(loaded.optInt("recording_max_minutes") == 30, "after load the cap reads 30");
        JSONObject resaved = ConfigPersistence.sparse(loaded, defaults);
        check(!resaved.has("recording_max_minutes") && resaved.optInt("assistant_followup_seconds") == 25,
            "re-save keeps only the user's own choices");

        System.out.println("config persistence checks passed");
    }

    private ConfigPersistenceCheck() {}
}
