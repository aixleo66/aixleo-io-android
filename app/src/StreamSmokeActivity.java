package dev.xr.rayneo.probe;

import android.app.Activity;
import android.media.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** Internal synthetic Opus -> streaming ASR diagnostic; explicit upload confirmation, no microphone. */
public final class StreamSmokeActivity extends Activity {
    private StreamingAsr stream;
    private final Object streamLock = new Object();
    private volatile boolean done;
    private boolean started;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private final JSONArray events = new JSONArray();
    private TextView label;
    private void record(String name, Object value) {
        handler.post(() -> { try {
            if (isFinishing() || isDestroyed()) return;
            events.put(new JSONObject().put("event", name).put("value", value).put("at_ms", System.currentTimeMillis()));
            report.put("events", events);
            try (FileOutputStream out = openFileOutput("stream-smoke.json", MODE_PRIVATE)) { out.write(report.toString(2).getBytes("UTF-8")); }
            label.setText("预置语音流式测试：" + name);
        } catch (Exception ignored) {} });
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); label = new TextView(this); label.setText("预置语音 → Opus → 实时解码与识别；不使用麦克风"); setContentView(label);
        new android.app.AlertDialog.Builder(this)
            .setTitle("上传诊断音频？")
            .setMessage("将使用已配置的阿里云 Key 上传本机预置测试音频，可能消耗服务额度。不会开启麦克风。")
            .setPositiveButton("上传并测试", (dialog, which) -> startTest())
            .setNegativeButton("取消", (dialog, which) -> finish())
            .setOnCancelListener(dialog -> finish()).show();
    }
    private void startTest() {
        if (started || done || isFinishing() || isDestroyed()) return;
        started = true;
        new Thread(() -> {
            MediaCodec encoder = null; byte[] pcm = null;
            try {
                byte[] wav = CloudConfig.read(openFileInput("stream-synthetic.wav"), 1000000);
                // Our generated fixture is canonical mono 48 kHz PCM16, at most nine seconds.
                ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
                if (wav.length < 44 || header.getInt(0) != 0x46464952 || header.getInt(8) != 0x45564157
                    || header.getShort(20) != 1 || header.getShort(22) != 1 || header.getInt(24) != 48000
                    || header.getShort(34) != 16 || header.getInt(36) != 0x61746164 || header.getInt(40) != wav.length-44)
                    throw new IOException("Synthetic WAV format");
                pcm = Arrays.copyOfRange(wav, 44, wav.length); Arrays.fill(wav, (byte)0);
                synchronized (streamLock) {
                // Destruction may happen while the worker is reading/validating the fixture.
                if (done) return;
                stream = new StreamingAsr(CloudConfig.load(this), new StreamingAsr.Listener() {
                    public void event(String name, Object value) { record(name, value); }
                    public void text(String value, boolean finalText) { record(finalText ? "final" : "partial", value); }
                    public void endpoint() { record("endpoint", true); }
                    public void completed(JSONObject asr) { done = true; record("completed", asr); }
                    public void failed(String reason, boolean uploaded) { done = true; record("failed", reason); }
                });
                stream.start();
                }
                MediaFormat format = MediaFormat.createAudioFormat("audio/opus", 48000, 1);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
                encoder = MediaCodec.createEncoderByType("audio/opus"); encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int offset = 0; long start = SystemClock.elapsedRealtime(), queued = 0;
                while (!done && SystemClock.elapsedRealtime()-start < 22000) {
                    int input = encoder.dequeueInputBuffer(1000);
                    if (input >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(input); buffer.clear();
                        int size = Math.min(1920, pcm.length-offset);
                        if (size > 0) { buffer.put(pcm, offset, size); offset += size; }
                        else { size = 1920; buffer.put(new byte[size]); }
                        encoder.queueInputBuffer(input, 0, size, queued * 1000000 / 48000, 0); queued += size / 2;
                    }
                    int output = encoder.dequeueOutputBuffer(info, 1000);
                    if (output >= 0) {
                        try {
                            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                byte[] opus = new byte[info.size]; ByteBuffer buffer = encoder.getOutputBuffer(output);
                                buffer.position(info.offset); buffer.limit(info.offset+info.size); buffer.get(opus);
                                stream.offer(opus); Arrays.fill(opus, (byte)0);
                            }
                        } finally { encoder.releaseOutputBuffer(output, false); }
                    }
                    long delay = queued * 1000 / 48000 - (SystemClock.elapsedRealtime()-start);
                    if (delay > 0) Thread.sleep(Math.min(delay, 30));
                }
                if (!done) record("failed", "Synthetic test timeout");
            } catch (Exception e) { record("failed", e.getClass().getSimpleName()); }
            finally {
                synchronized (streamLock) { if (stream != null) stream.close(); }
                if (pcm != null) Arrays.fill(pcm, (byte)0);
                if (encoder != null) { try { encoder.stop(); } catch (Exception ignored) {} encoder.release(); }
            }
        }, "synthetic-stream-test").start();
    }
    @Override public void onDestroy() {
        synchronized (streamLock) { done = true; if (stream != null) stream.close(); }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
