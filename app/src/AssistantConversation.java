package dev.xr.rayneo.probe;

import java.util.*;
import org.json.*;

/** Bounded, memory-only context for one visible assistant conversation. */
final class AssistantConversation {
    private final int maxTurns;
    private final int maxChars;
    private final ArrayList<String[]> turns = new ArrayList<>();
    private String id = UUID.randomUUID().toString();
    private int chars;

    AssistantConversation() { this(6, 4000); }
    AssistantConversation(int maxTurns, int maxChars) {
        if (maxTurns < 1 || maxChars < 1) throw new IllegalArgumentException("Conversation bounds");
        this.maxTurns = maxTurns; this.maxChars = maxChars;
    }
    synchronized void start() { turns.clear(); chars = 0; id = UUID.randomUUID().toString(); }
    synchronized void clear() { turns.clear(); chars = 0; }
    synchronized void commit(String user, String assistant) {
        user = clean(user); assistant = clean(assistant);
        if (user.isEmpty() || assistant.isEmpty()) return;
        int size = user.length() + assistant.length();
        if (size > maxChars) return;
        turns.add(new String[]{user, assistant}); chars += size;
        while (turns.size() > maxTurns || chars > maxChars) {
            String[] removed = turns.remove(0); chars -= removed[0].length() + removed[1].length();
        }
    }
    synchronized JSONArray history() throws Exception {
        JSONArray out = new JSONArray();
        for (String[] turn : turns) out.put(message("user", turn[0])).put(message("assistant", turn[1]));
        return out;
    }
    synchronized JSONObject metadata() throws Exception {
        return new JSONObject().put("conversation_id", id).put("storage", "memory_only")
            .put("turns", turns.size()).put("chars", chars).put("max_turns", maxTurns).put("max_chars", maxChars);
    }
    static JSONArray requestMessages(String system, JSONArray history, String prompt) throws Exception {
        JSONArray out = new JSONArray().put(message("system", clean(system)));
        int count = 0, size = 0;
        if (history != null) for (int i = 0; i < history.length() && count < 12; i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String role = item.optString("role"), content = clean(item.optString("content"));
            if (!(role.equals("user") || role.equals("assistant")) || content.isEmpty()) continue;
            if (size + content.length() > 4000) break;
            out.put(message(role, content)); count++; size += content.length();
        }
        return out.put(message("user", clean(prompt)));
    }
    private static JSONObject message(String role, String content) throws Exception { return new JSONObject().put("role", role).put("content", content); }
    private static String clean(String text) { return text == null ? "" : text.trim(); }
}
