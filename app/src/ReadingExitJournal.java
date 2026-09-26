package dev.xr.rayneo.probe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** App-private event chain for one answer page. It records control events, never answer text. */
final class ReadingExitJournal {
    private ReadingExitJournal() {}

    static synchronized void append(File root, String session, String command, String event,
            long elapsedMs, JSONObject details) throws Exception {
        if (!valid(session) || !valid(command) || !event.matches("[a-z0-9_]{1,48}"))
            throw new IllegalArgumentException("Invalid reading identity or event");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Reading trace directory");
        File target = new File(root, session + "--" + command + ".jsonl");
        JSONObject record = new JSONObject().put("schema_version", 1).put("session_id", session)
            .put("command_id", command).put("clock", "elapsed_realtime")
            .put("event", event).put("elapsed_ms", elapsedMs)
            .put("details", details == null ? new JSONObject() : details);
        byte[] bytes = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 || target.length() + bytes.length > 32768)
            throw new IOException("Reading trace size limit");
        try (FileOutputStream out = new FileOutputStream(target, true)) {
            out.write(bytes); out.getFD().sync();
        }
    }

    private static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }
}
