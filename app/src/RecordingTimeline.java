package dev.xr.rayneo.probe;

import android.os.SystemClock;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local monotonic observations. Missing milestones are unknown, never zero-duration success. */
final class RecordingTimeline {
    private final long origin = SystemClock.elapsedRealtime();
    private final Map<String, Long> points = new LinkedHashMap<>();

    synchronized void mark(String name) {
        if (!points.containsKey(name)) points.put(name, SystemClock.elapsedRealtime() - origin);
    }

    synchronized void audioReceived() {
        mark("first_audio_received");
        points.put("last_audio_received", SystemClock.elapsedRealtime() - origin);
    }

    private void duration(JSONObject target, String name, String from, String to) throws Exception {
        Long start = points.get(from), end = points.get(to);
        if (start != null && end != null && end >= start) target.put(name, end - start);
    }

    synchronized JSONObject snapshot() {
        JSONObject result = new JSONObject(), offsets = new JSONObject(), durations = new JSONObject();
        try {
            for (Map.Entry<String, Long> point : points.entrySet()) offsets.put(point.getKey(), point.getValue());
            duration(durations, "start_to_confirmation", "start_requested", "start_confirmed");
            duration(durations, "stop_to_completed_report", "stop_requested", "completed_received");
            duration(durations, "completed_to_finalize_worker", "completed_received", "finalize_started");
            duration(durations, "raw_seal", "finalize_started", "raw_sealed");
            duration(durations, "decode", "decode_started", "decode_finished");
            duration(durations, "decode_to_saved_state", "decode_finished", "saved_state");
            duration(durations, "package", "package_started", "package_finished");
            duration(durations, "package_to_saved_state", "package_finished", "saved_state");
            duration(durations, "stop_to_saved_state", "stop_requested", "saved_state");
            result.put("schema_version", 1).put("clock", "elapsed_realtime")
                .put("offset_ms", offsets).put("duration_ms", durations);
        } catch (Exception ignored) { }
        return result;
    }
}
