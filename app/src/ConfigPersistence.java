package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** What the stored configuration keeps, so that changing a default actually reaches users.
 *
 * <p>Measured 2026-09-23: the recording cap default moved 60 -> 30 on 09-22, yet a user who never
 * touched that setting recorded until exactly 60.0 minutes. {@code CloudConfig.load} only fills
 * <em>missing</em> keys from defaults, and every save wrote the whole merged object back -- so any
 * save made while 60 was the default froze 60 into the file for good.
 *
 * <p>Two rules fix that without guessing at user intent:
 * <ul>
 * <li>{@link #sparse}: a value equal to the current default is not stored. The key is then absent,
 *   and the next load fills whatever the default is <em>then</em>. Values the user chose that
 *   differ from the default are stored as before.</li>
 * <li>{@link #dropRetiredDefaults}: a stored value equal to a default we have since retired is
 *   treated as inherited, not chosen, and removed -- <b>once</b>, gated by {@link #MIGRATION}, so a
 *   user who later picks that value on purpose keeps it. Accepted risk: <em>any</em> user whose
 *   file predates the marker and holds exactly a retired value -- whether inherited or chosen --
 *   gets the current default once and has to set it again (recording cap 60 was the default for
 *   about 34 minutes on 09-22; the follow-up window 15 s for about a day, 09-19 to 09-20).</li>
 * </ul>
 * Consequence to keep in mind: changing any default now silently moves every user who never set
 * that key -- including endpoints and model names. That is the point for the cap; it must be a
 * deliberate choice for anything else. Default changes must preserve the sparse-storage and one-time migration rules above.
 * Plain org.json so it can be checked off-device. */
final class ConfigPersistence {
    /** Defaults that were once shipped and have since changed: key -> values that meant "default". */
    static final String[][] RETIRED = {
        {"recording_max_minutes", "60"},          // Retired default, 09-22
        {"assistant_followup_seconds", "15"},     // Retired default, 09-19 -> 09-20
    };
    /** Bump when a new row is added to RETIRED; the load path migrates once per bump. */
    static final int MIGRATION = 1;
    /** Config key recording which MIGRATION the stored file has been through. */
    static final String MARKER = "config_migration";

    private ConfigPersistence() {}

    /** A copy of {@code value} without the keys whose value equals the current default. */
    static JSONObject sparse(JSONObject value, JSONObject defaults) throws Exception {
        JSONObject out = new JSONObject(value.toString());
        for (java.util.Iterator<String> names = defaults.keys(); names.hasNext();) {
            String name = names.next();
            if (out.has(name) && same(out.opt(name), defaults.opt(name))) out.remove(name);
        }
        return out;
    }

    /** Removes stored values that equal a retired default; returns how many were removed. */
    static int dropRetiredDefaults(JSONObject loaded) {
        int removed = 0;
        for (String[] rule : RETIRED) {
            if (!loaded.has(rule[0])) continue;
            for (int i = 1; i < rule.length; i++) {
                if (String.valueOf(loaded.opt(rule[0])).equals(rule[i])) { loaded.remove(rule[0]); removed++; break; }
            }
        }
        return removed;
    }

    /** Numbers compare by value (30 vs 30L vs 30.0 are the same setting); everything else by string. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number)
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
