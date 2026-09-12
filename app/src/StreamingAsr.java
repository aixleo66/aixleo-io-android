package dev.xr.rayneo.probe;

import android.media.*;
import android.os.SystemClock;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

/** One wake, one bounded cloud socket + decoder. Construct only after a hardware wake. */
final class StreamingAsr implements AutoCloseable {
    interface Listener {
        void event(String name, Object value);
        void text(String value, boolean isFinal);
        void endpoint();
        void completed(JSONObject asr);
        void failed(String reason, boolean uploaded);
    }
    private final JSONObject config;
    private final Listener listener;
    private final ArrayBlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(64);
    private volatile boolean cancelled, ended;
    private volatile WebSocketClient socket;
    private volatile String transportFailure;
    private final Thread worker;
    private volatile boolean uploaded;
    boolean uploadStarted() { return uploaded; }
    StreamingAsr(JSONObject config, Listener listener) {
        this.config = config; this.listener = listener;
        worker = new Thread(this::run, "glasses-stream-asr"); worker.setDaemon(true);
    }
    void start() { worker.start(); }
    boolean offer(byte[] opus) {
        if (cancelled || ended) return true;
        byte[] copy = opus.clone();
        if (packets.offer(copy)) return true;
        Arrays.fill(copy, (byte)0); transportFailure = "实时音频队列已满"; return false;
    }
    private void send(String type, String key, Object value) throws Exception {
        JSONObject event = new JSONObject().put("event_id", UUID.randomUUID().toString()).put("type", type);
        if (key != null) event.put(key, value);
        if (cancelled) throw new InterruptedException();
        socket.send(event.toString());
    }
    private void run() {
        MediaCodec decoder = null; PcmDownsample resampler = new PcmDownsample();
        long started = SystemClock.elapsedRealtime(), sentSamples = 0, pcmSamples = 0, endpointAt = 0;
        int count = 0, partials = 0; String finalText = null;
        try {
            URI uri = endpoint(config);
            Map<String,String> headers = new HashMap<>();
            String key = config.optString("dashscope_key");
            if (key.isEmpty()) throw new CloudClient.Failure("请先配置阿里云 Key");
            headers.put("Authorization", "Bearer " + key); headers.put("OpenAI-Beta", "realtime=v1");
            socket = new WebSocketClient(uri, new Draft_6455(Collections.emptyList(), 65536), headers, 10000) {
                public void onOpen(ServerHandshake handshake) {}
                public void onMessage(String message) {
                    if (!cancelled && (message.length() > 65536 || !events.offer(message))) transportFailure = "识别事件队列超过限制";
                }
                public void onClose(int code, String reason, boolean remote) { if (!cancelled) events.offer("{\"type\":\"transport.closed\"}"); }
                public void onError(Exception e) { if (!cancelled) transportFailure = "识别连接异常：" + e.getClass().getSimpleName(); }
            };
            // Library verifies certificate chain + HTTPS hostname. Do not override TLS parameters.
            socket.setConnectionLostTimeout(0); // Per-round deadline; no idle ping thread.
            if (cancelled) throw new InterruptedException();
            if (!socket.connectBlocking(10, TimeUnit.SECONDS))
                throw new CloudClient.Failure(transportFailure == null ? "识别连接未能建立" : transportFailure);
            if (cancelled) throw new InterruptedException();
            send("session.update", "session", new JSONObject().put("input_audio_format", "pcm").put("sample_rate", 16000)
                .put("input_audio_transcription", new JSONObject().put("language", "zh"))
                .put("turn_detection", new JSONObject().put("type", "server_vad").put("threshold", 0.0).put("silence_duration_ms", 700)));
            boolean ready = false, speechStarted = false;
            long readyAt = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!cancelled) {
                if (transportFailure != null) throw new CloudClient.Failure(transportFailure);
                long now = SystemClock.elapsedRealtime();
                if (ready && !speechStarted && now - readyAt > 8000) throw new CloudClient.Failure("未检测到说话，已停止本轮收音");
                if (now - started > 30000 || (!ended && now - started > 20000)) throw new CloudClient.Failure("本轮识别超时，已停止收音");
                String incoming;
                while ((incoming = events.poll()) != null) {
                    JSONObject event = new JSONObject(incoming); String type = event.optString("type");
                    if (type.equals("session.updated")) { ready = true; readyAt = now; listener.event("stream_ready", now - started); }
                    else if (type.equals("input_audio_buffer.speech_started")) { speechStarted = true; listener.event("stream_speech_started", true); }
                    else if (type.equals("conversation.item.input_audio_transcription.text")) {
                        if (finalText == null) { partials++; listener.text(event.optString("text") + event.optString("stash"), false); }
                    } else if (type.equals("input_audio_buffer.speech_stopped")) {
                        if (!ended) { ended = true; endpointAt = now; listener.endpoint(); send("session.finish", null, null); }
                    } else if (type.equals("conversation.item.input_audio_transcription.completed")) {
                        if (finalText == null) {
                            finalText = event.optString("transcript").trim(); listener.text(finalText, true);
                            if (!ended) { ended = true; endpointAt = now; listener.endpoint(); send("session.finish", null, null); }
                        }
                    } else if (type.equals("session.finished")) {
                        if (finalText == null || finalText.isEmpty()) throw new CloudClient.Failure("未识别到有效语音，请重新唤醒");
                        listener.completed(new JSONObject().put("provider", "dashscope").put("model", config.optString("dashscope_stream_model", "qwen3-asr-flash-realtime"))
                            .put("text", finalText).put("elapsed_ms", now - started).put("endpoint_ms", endpointAt - started)
                            .put("partial_events", partials).put("opus_packets", count).put("pcm_samples", pcmSamples)
                            .put("sample_rate", 16000).put("audio_sent_ms", pcmSamples * 1000 / 16000));
                        return;
                    } else if (type.equals("error") || type.endsWith("transcription.failed")) {
                        String code = event.optJSONObject("error") == null ? "unknown" : event.getJSONObject("error").optString("code", "unknown");
                        throw new CloudClient.Failure("实时识别服务拒绝请求：" + (code.matches("[A-Za-z0-9_.-]{1,80}") ? code : "unknown"));
                    } else if (type.equals("transport.closed")) throw new CloudClient.Failure("识别连接提前关闭");
                }
                if (ended || !ready) { Thread.sleep(10); continue; }
                if (decoder == null) {
                    decoder = MediaCodec.createDecoderByType("audio/opus"); decoder.configure(OpusAudio.format(), null, null, 0); decoder.start();
                }
                if (!packets.isEmpty()) {
                    int input = decoder.dequeueInputBuffer(1000);
                    if (input >= 0) {
                        byte[] packet = packets.poll();
                        try {
                            ByteBuffer buffer = decoder.getInputBuffer(input); buffer.clear(); buffer.put(packet);
                            decoder.queueInputBuffer(input, 0, packet.length, sentSamples * 1000000 / 48000, 0);
                            sentSamples += OpusAudio.samples48k(packet); count++;
                            if (sentSamples > 48000 * 20) throw new CloudClient.Failure("本轮音频达到20秒上限");
                        } finally { Arrays.fill(packet, (byte)0); }
                    }
                }
                int out = decoder.dequeueOutputBuffer(info, 1000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = decoder.getOutputFormat();
                    if (actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 48000 || actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1
                        || (actual.containsKey(MediaFormat.KEY_PCM_ENCODING) && actual.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT))
                        throw new CloudClient.Failure("实时解码格式不符");
                } else if (out >= 0) {
                    byte[] pcm = null, down = null;
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (info.size > 65536) throw new CloudClient.Failure("实时解码块超过限制");
                            ByteBuffer buffer = decoder.getOutputBuffer(out); buffer.position(info.offset); buffer.limit(info.offset + info.size);
                            pcm = new byte[info.size]; buffer.get(pcm); down = resampler.process(pcm);
                            if (!uploaded) { uploaded = true; listener.event("stream_upload_started", true); }
                            // A stalled network must not accumulate unbounded voice in the socket send queue.
                            if (socket.getConnection().hasBufferedData() && now - started > 10000 && packets.size() > 64)
                                throw new CloudClient.Failure("网络发送积压，已停止本轮");
                            send("input_audio_buffer.append", "audio", android.util.Base64.encodeToString(down, android.util.Base64.NO_WRAP));
                            pcmSamples += down.length / 2;
                        }
                    } finally {
                        if (pcm != null) Arrays.fill(pcm, (byte)0); if (down != null) Arrays.fill(down, (byte)0);
                        decoder.releaseOutputBuffer(out, false);
                    }
                }
            }
        } catch (Exception e) {
            if (!cancelled) listener.failed(e instanceof CloudClient.Failure ? e.getMessage() : e.getClass().getSimpleName(), uploaded);
        } finally {
            ended = true;
            if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) {} decoder.release(); }
            resampler.clear(); clearPackets();
            if (socket != null) socket.closeConnection(1000, "Round ended");
        }
    }
    static URI endpoint(JSONObject config) throws Exception {
        // Validate provider origin using the same existing allowlist; no third-party key destinations.
        String base = config.optString("dashscope_stream_url", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        URI value = new URI(base);
        if (!"wss".equals(value.getScheme()) || !"/api-ws/v1/realtime".equals(value.getPath()) || value.getRawQuery() != null || value.getRawFragment() != null)
            throw new CloudClient.Failure("实时识别地址须为官方 WSS realtime 接口");
        CloudClient.endpoint("https://" + value.getRawAuthority() + "/chat/completions", "dashscope");
        String model = config.optString("dashscope_stream_model", "qwen3-asr-flash-realtime");
        if (!model.matches("[A-Za-z0-9._-]{1,100}")) throw new CloudClient.Failure("实时识别模型名无效");
        return new URI(base + "?model=" + model);
    }
    private void clearPackets() { byte[] packet; while ((packet = packets.poll()) != null) Arrays.fill(packet, (byte)0); }
    public void close() {
        cancelled = true; ended = true; worker.interrupt(); clearPackets();
        WebSocketClient current = socket;
        if (current != null) current.closeConnection(1000, "Cancelled");
    }
}
