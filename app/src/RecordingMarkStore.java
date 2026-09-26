package dev.xr.rayneo.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Lab evidence sidecars. Host receipt times are NOT audio seek positions. */
final class RecordingMarkStore {
    private final File folder;
    private volatile int count;
    private long lastElapsed = -1;
    RecordingMarkStore(File folder) { this.folder = folder; }
    int count() { return count; }

    // Official 1.0.4 uses a five-second rejection window. This is an explicit
    // Lab policy, not event-ID deduplication or a verified firmware requirement.
    synchronized int save(long wallMs, long elapsedMs, int receivedBytes, Long deviceTime) throws IOException {
        if (elapsedMs < 0 || receivedBytes < 0) throw new IOException("Invalid mark observation");
        if (lastElapsed >= 0 && elapsedMs - lastElapsed < 5000) return 2;
        if (count >= 60) return 3;
        File target = new File(folder, String.format(java.util.Locale.ROOT, "mark-%04d.json", count + 1));
        File pending = new File(folder, target.getName() + ".tmp");
        if (target.exists() || !pending.createNewFile()) throw new IOException("Mark path already exists");
        String json = "{\"schema\":1,\"source\":\"glasses_type11_action1\",\"ordinal\":" + (count + 1)
            + ",\"host_received_at_ms\":" + wallMs + ",\"host_since_recording_confirmed_ms\":" + elapsedMs
            + ",\"received_audio_bytes_at_request\":" + receivedBytes
            + ",\"device_time_raw\":" + (deviceTime == null ? "null" : deviceTime.toString())
            + ",\"device_time_unit\":\"unknown\",\"audio_seek_position_ms\":null,\"ack_delivery\":\"not_established\"}";
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (target.exists() || !pending.renameTo(target)) throw new IOException("Mark publish failed");
        count++;
        lastElapsed = elapsedMs;
        return 1;
    }
}
