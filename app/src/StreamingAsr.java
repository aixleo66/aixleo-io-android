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
        default void timing(String name,String detail,long elapsedRealtime) {}
        void text(String value, boolean isFinal);
        void endpoint();
        void completed(JSONObject asr);
        void failed(String reason, boolean uploaded);
    }
    private final JSONObject config;
    private final Listener listener;
    private final int requestedSilenceDurationMs;
    private final int captureWindowMs;
    private final int continuationGraceMs;
    private volatile boolean captureStopConfirmed;
    private final ArrayBlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(64);
    private volatile boolean cancelled, ended, inputStopped;
    private volatile int acceptedPackets, latePackets;
    private volatile WebSocketClient socket;
    private volatile String transportFailure;
    private final Thread worker;
    private volatile boolean uploaded;
    boolean uploadStarted() { return uploaded; }
    StreamingAsr(JSONObject config, Listener listener) {
        this(config,listener,700);
    }
    StreamingAsr(JSONObject config, Listener listener, int silenceDurationMs) {
        this(config,listener,silenceDurationMs,0);
    }
    StreamingAsr(JSONObject config, Listener listener, int silenceDurationMs, int captureWindowMs) {
        this(config,listener,silenceDurationMs,captureWindowMs,0);
    }
    StreamingAsr(JSONObject config, Listener listener, int silenceDurationMs, int captureWindowMs, int continuationGraceMs) {
        if(silenceDurationMs!=700&&silenceDurationMs!=1500)throw new IllegalArgumentException("Unsupported ASR silence duration");
        if(captureWindowMs!=0&&captureWindowMs!=8000)throw new IllegalArgumentException("Unsupported ASR capture window");
        if((continuationGraceMs!=0&&continuationGraceMs!=2000)||(continuationGraceMs!=0&&captureWindowMs!=0))
            throw new IllegalArgumentException("Unsupported ASR continuation policy");
        this.continuationGraceMs=continuationGraceMs;
        this.captureWindowMs=captureWindowMs;
        requestedSilenceDurationMs=silenceDurationMs;
        this.config = config; this.listener = listener;
        worker = new Thread(this::run, "glasses-stream-asr"); worker.setDaemon(true);
    }
    static JSONObject sessionSettings(int silenceDurationMs)throws Exception{
        if(silenceDurationMs!=700&&silenceDurationMs!=1500)throw new IllegalArgumentException("Unsupported ASR silence duration");
        return new JSONObject().put("input_audio_format","pcm").put("sample_rate",16000)
            .put("input_audio_transcription",new JSONObject().put("language","zh"))
            .put("turn_detection",new JSONObject().put("type","server_vad").put("threshold",0.0)
                .put("silence_duration_ms",silenceDurationMs));
    }
    void start() { worker.start(); }
    void acknowledgeCaptureStop(boolean success){
        if(continuationGraceMs==0||cancelled)return;
        if(success)captureStopConfirmed=true;
        else transportFailure="ASR capture stop send failed";
    }
    boolean offer(byte[] opus) {
        synchronized(packets){
            if (cancelled || ended || inputStopped) { if(inputStopped)latePackets++;return true; }
            byte[] copy = opus.clone();
            if (packets.offer(copy)) {acceptedPackets++;return true;}
            Arrays.fill(copy, (byte)0); transportFailure = "实时音频队列已满"; return false;
        }
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
        int count = 0, partials = 0; String finalText = null; String endpointReason="unknown";
        AsrSegments segments=captureWindowMs==0&&continuationGraceMs==0?null:new AsrSegments();
        Set<String> speaking=new HashSet<>();long quietSince=0;
        JSONArray graceEvents=new JSONArray();
        JSONObject echoed=new JSONObject();
        boolean sentEos=false,gotEos=false,finishSent=false;
        long finishAt=0,sessionFinishedAt=0;
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
            send("session.update", "session", sessionSettings(requestedSilenceDurationMs));
            boolean ready = false, speechStarted = false;
            long readyAt = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!cancelled) {
                if (transportFailure != null) throw new CloudClient.Failure(transportFailure);
                long now = SystemClock.elapsedRealtime();
                if (ready && !speechStarted && now - readyAt > 8000) { listener.timing("asr_no_input","",now); throw new CloudClient.Failure("未检测到说话，已停止本轮收音"); }
                if (now - started > 30000 || (!ended && now - started > 20000)) throw new CloudClient.Failure("本轮识别超时，已停止收音");
                String incoming;
                while ((incoming = events.poll()) != null) {
                    JSONObject event = new JSONObject(incoming); String type = event.optString("type");
                    boolean segmentPartialChanged=segments==null;
                    if(segments!=null&&!type.endsWith("transcription.failed"))
                        segmentPartialChanged=segments.accept(event,now-started,count,pcmSamples,packets.size());
                    if(continuationGraceMs!=0&&!inputStopped){
                        String item=event.optString("item_id");String transition=null;
                        if(type.equals("input_audio_buffer.speech_started")){
                            speaking.add(item);if(quietSince!=0)transition="cancelled_by_speech";quietSince=0;
                        }else if(type.equals("input_audio_buffer.speech_stopped")&&speaking.remove(item)&&speaking.isEmpty()){
                            quietSince=now;transition="started_after_sentence";
                        }
                        if(transition!=null&&graceEvents.length()<32)graceEvents.put(new JSONObject()
                            .put("transition",transition).put("item_id",item).put("elapsed_ms",now-started));
                    }
                    if (type.equals("session.updated")) {
                        if(!ready)readyAt=now;
                        ready = true;echoed=AsrSegments.echoedSettings(event);
                        listener.event("stream_session_settings",echoed);listener.timing("asr_ready","",now); listener.event("stream_ready", now - started);
                    }
                    else if (type.equals("input_audio_buffer.speech_started")) { speechStarted = true; listener.timing("asr_speech_started","",now); listener.event("stream_speech_started", true); }
                    else if (type.equals("conversation.item.input_audio_transcription.text")) {
                        if (finalText == null&&segmentPartialChanged) { partials++; listener.text(event.optString("text") + event.optString("stash"), false); }
                    } else if (type.equals("input_audio_buffer.speech_stopped")) {
                        if (segments==null&&!ended) { ended = true; endpointAt = now; endpointReason="vad_stopped"; listener.timing("asr_endpoint",endpointReason,now); listener.endpoint(); send("session.finish", null, null); }
                    } else if (type.equals("conversation.item.input_audio_transcription.completed")) {
                        if (segments==null&&finalText == null) {
                            finalText = event.optString("transcript").trim(); listener.timing("asr_final_text","",now); listener.text(finalText, true);
                            if (!ended) { ended = true; endpointAt = now; endpointReason="final_text_fallback"; listener.timing("asr_endpoint",endpointReason,now); listener.endpoint(); send("session.finish", null, null); }
                        }
                    } else if (type.equals("session.finished")) {
                        if(segments!=null){
                            if(!finishSent)throw new CloudClient.Failure("ASR session ended before bounded capture finished");
                            finalText=segments.finalText();listener.timing("asr_final_text","",now);listener.text(finalText,true);
                        }
                        sessionFinishedAt=now;
                        if (finalText == null || finalText.isEmpty()) throw new CloudClient.Failure("未识别到有效语音，请重新唤醒");
                        listener.timing("asr_completed","",now);
                        listener.completed(new JSONObject().put("provider", "dashscope").put("model", config.optString("dashscope_stream_model", "qwen3-asr-flash-realtime"))
                            .put("text", finalText).put("elapsed_ms", now - started).put("endpoint_ms", endpointAt - started)
                            .put("endpoint_reason",endpointReason).put("requested_silence_duration_ms",requestedSilenceDurationMs)
                            .put("service_settings_echo",echoed).put("capture_window_ms",captureWindowMs)
                            .put("continuation_grace_ms",continuationGraceMs).put("continuation_events",graceEvents)
                            .put("capture_stop_send_confirmed_before_finish",continuationGraceMs==0?JSONObject.NULL:captureStopConfirmed)
                            .put("capture_window_origin",captureWindowMs==0?"not_applicable":"service_ready")
                            .put("accepted_opus_packets",acceptedPackets).put("late_packets_after_cutoff",latePackets)
                            .put("queued_packets_at_finish",packets.size()).put("decoder_eos",gotEos)
                            .put("finish_sent_ms",finishAt==0?JSONObject.NULL:finishAt-started)
                            .put("session_finished_ms",sessionFinishedAt-started)
                            .put("segments",segments==null?JSONObject.NULL:segments.snapshot())
                            .put("segment_events",segments==null?JSONObject.NULL:segments.events())
                            .put("partial_events", partials).put("opus_packets", count).put("pcm_samples", pcmSamples)
                            .put("sample_rate", 16000).put("audio_sent_ms", pcmSamples * 1000 / 16000));
                        return;
                    } else if (type.equals("error") || type.endsWith("transcription.failed")) {
                        String code = event.optJSONObject("error") == null ? "unknown" : event.getJSONObject("error").optString("code", "unknown");
                        throw new CloudClient.Failure("实时识别服务拒绝请求：" + (code.matches("[A-Za-z0-9_.-]{1,80}") ? code : "unknown"));
                    } else if (type.equals("transport.closed")) throw new CloudClient.Failure("识别连接提前关闭");
                }
                boolean graceExpired=continuationGraceMs!=0&&quietSince!=0&&speaking.isEmpty()&&now-quietSince>=continuationGraceMs;
                if(segments!=null&&ready&&!inputStopped&&((captureWindowMs!=0&&now-readyAt>=captureWindowMs)||graceExpired)){
                    synchronized(packets){inputStopped=true;}
                    endpointAt=now;endpointReason=graceExpired?"continuation_grace_expired":"bounded_capture_window";
                    if(graceExpired&&graceEvents.length()<32)graceEvents.put(new JSONObject().put("transition","expired").put("elapsed_ms",now-started));
                    listener.timing("asr_endpoint",endpointReason,now);listener.endpoint();
                }
                if(segments!=null&&inputStopped&&!finishSent&&now-endpointAt>2500)
                    throw new CloudClient.Failure("ASR audio drain or capture stop confirmation timed out");
                if (ended || !ready) { Thread.sleep(10); continue; }
                if(segments!=null&&inputStopped&&decoder==null&&packets.isEmpty())gotEos=true;
                if(segments!=null&&gotEos&&!finishSent){
                    if(continuationGraceMs!=0&&!captureStopConfirmed){Thread.sleep(10);continue;}
                    send("session.finish",null,null);finishSent=true;finishAt=now;ended=true;
                    listener.timing("asr_finish_sent","",now);continue;
                }
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
                if(segments!=null&&inputStopped&&packets.isEmpty()&&!sentEos){
                    int input=decoder.dequeueInputBuffer(1000);
                    if(input>=0){decoder.queueInputBuffer(input,0,0,sentSamples*1000000/48000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);sentEos=true;}
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
                        if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)gotEos=true;
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
