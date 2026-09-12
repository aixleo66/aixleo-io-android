package dev.xr.rayneo.probe;

import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Bounded, non-streaming calls to the two user-selected providers. Never logs credentials. */
final class CloudClient {
    static final class Cancellation {
        private volatile boolean cancelled;
        private HttpsURLConnection active;
        private Closeable activeInput;
        synchronized void attach(HttpsURLConnection value) throws InterruptedException { check(); active = value; }
        synchronized void detach(HttpsURLConnection value) { if (active == value) active = null; }
        synchronized void attachInput(Closeable value) throws InterruptedException { check(); activeInput = value; }
        synchronized void detachInput(Closeable value) { if (activeInput == value) activeInput = null; }
        void check() throws InterruptedException { if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("Cloud call cancelled"); }
        void cancel() {
            final HttpsURLConnection connection;
            final Closeable input;
            synchronized (this) { cancelled = true; connection = active; input = activeInput; }
            if (connection != null || input != null) {
                Thread close = new Thread(() -> {
                    try { if (input != null) input.close(); } catch (Exception ignored) {}
                    try { if (connection != null) connection.disconnect(); } catch (Exception ignored) {}
                }, "cloud-cancel");
                close.setDaemon(true); close.start();
            }
        }
    }
    static final class Failure extends Exception { Failure(String text) { super(text); } }
    static URL endpoint(String value, String provider) throws Exception {
        URL url = new URL(value);
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean allowed = provider.equals("deepseek") ? host.equals("api.deepseek.com")
            : host.equals("dashscope.aliyuncs.com") || host.equals("dashscope-intl.aliyuncs.com")
                || host.endsWith(".maas.aliyuncs.com");
        if (!allowed || !url.getProtocol().equals("https") || url.getUserInfo() != null
                || url.getQuery() != null || url.getRef() != null || (url.getPort() != -1 && url.getPort() != 443)
                || !url.getPath().endsWith("/chat/completions")) throw new Failure("接口须为该服务的官方 HTTPS chat/completions 地址");
        return url;
    }
    static void validateConfig(JSONObject config) throws Exception {
        KnowledgeClient.validate(config);
        if(!java.util.Arrays.asList("deepseek","knowledge").contains(config.optString("assistant_provider","deepseek")))throw new Failure("助手服务选项无效");
        for (String p : new String[]{"deepseek", "dashscope"}) {
            endpoint(config.getString(p + "_url"), p);
            String model = config.getString(p + "_model"), key = config.optString(p + "_key", "");
            if (!model.matches("[A-Za-z0-9._-]{1,100}")) throw new Failure("请填写有效模型名");
            if (key.length() > 512 || (!key.isEmpty() && !key.matches("[!-~]+"))) throw new Failure("Key 格式无效");
        }
    }
    static JSONObject ask(JSONObject config, String prompt) throws Exception {
        return ask(config, prompt, new Cancellation());
    }
    static JSONObject ask(JSONObject config, String prompt, Cancellation cancel) throws Exception {
        cancel.check();
        if(config.optString("assistant_provider","deepseek").equals("knowledge"))return KnowledgeClient.ask(config,prompt,cancel);
        if (prompt.trim().isEmpty() || prompt.length() > 2000) throw new Failure("问题须为 1–2000 字");
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system")
            .put("content", "你是眼镜上的中文助手。用不超过100个汉字的纯文本简洁回答，不用Markdown。没有工具执行能力，不声称已执行操作。只提供稳妥、可实际采用的建议；不建议把塑料袋套在头上、遮挡口鼻等可能导致窒息或受伤的应急做法。没有安全的替代办法时，建议等待、避险或求助。"))
            .put(new JSONObject().put("role", "user").put("content", prompt));
        return call(config, "deepseek", new JSONObject().put("messages", messages).put("max_tokens", 256)
            .put("thinking", new JSONObject().put("type", "disabled")), 500, cancel);
    }
    static JSONObject transcribe(JSONObject config, byte[] audio, String mime) throws Exception {
        return transcribe(config, audio, mime, new Cancellation());
    }
    static JSONObject transcribe(JSONObject config, byte[] audio, String mime, Cancellation cancel) throws Exception {
        cancel.check();
        if (audio.length < 44 || audio.length > 5 * 1024 * 1024) throw new Failure("音频须小于 5 MB");
        if (!(mime.equals("audio/wav") || mime.equals("audio/mpeg"))) throw new Failure("本轮支持 WAV / MP3");
        if (mime.equals("audio/wav")) {
            try { AudioInput.checkWav(audio); } catch (IllegalArgumentException e) { throw new Failure(e.getMessage()); }
        }
        String data = "data:" + mime + ";base64," + android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP);
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_audio")
            .put("input_audio", new JSONObject().put("data", data)));
        return call(config, "dashscope", new JSONObject().put("messages", new JSONArray()
            .put(new JSONObject().put("role", "user").put("content", content)))
            .put("asr_options", new JSONObject().put("enable_itn", false)), 4000, cancel);
    }
    private static JSONObject call(JSONObject config, String provider, JSONObject body, int limit, Cancellation cancel) throws Exception {
        cancel.check();
        String key = config.optString(provider + "_key");
        if (key.isEmpty()) throw new Failure("请先填写并保存 " + provider + " Key");
        body.put("model", config.getString(provider + "_model")).put("stream", false);
        HttpsURLConnection connection = (HttpsURLConnection)endpoint(config.getString(provider + "_url"), provider).openConnection();
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            cancel.attach(connection);
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(45000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] request = body.toString().getBytes("UTF-8"); connection.setFixedLengthStreamingMode(request.length);
            cancel.check();
            try (OutputStream out = connection.getOutputStream()) { cancel.check(); out.write(request); }
            int code = connection.getResponseCode();
            if (code != 200) throw new Failure(provider + " HTTP " + code + (code == 401 ? "：请检查 Key 与地域" : code == 402 ? "：请检查账户余额" : "：请求未通过，未自动重试"));
            JSONObject response = new JSONObject(new String(CloudConfig.read(connection.getInputStream(), 1048576), "UTF-8"));
            cancel.check();
            JSONObject choice = response.getJSONArray("choices").getJSONObject(0), message = choice.getJSONObject("message");
            if (!"stop".equals(choice.optString("finish_reason")) || message.has("tool_calls")) throw new Failure("输出未正常完成，不发送到眼镜");
            String text = message.getString("content").trim();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > limit) throw new Failure("输出为空或超过本轮长度限制");
            if (provider.equals("deepseek") && !AnswerPolicy.canDeliver(text)) throw new Failure("回答触发本地危险建议拦截，未显示或发送；请换个问法");
            for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i)) && text.charAt(i) != '\n') throw new Failure("输出含控制字符");
            JSONObject result = new JSONObject().put("status", "completed").put("provider", provider).put("text", text)
                .put("model", config.getString(provider + "_model")).put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
            JSONObject usage = response.optJSONObject("usage"), safe = new JSONObject();
            if (usage != null) for (String name : new String[]{"prompt_tokens", "completion_tokens", "total_tokens"})
                if (usage.opt(name) instanceof Number) safe.put(name, usage.get(name));
            return result.put("usage", safe);
        } finally { cancel.detach(connection); connection.disconnect(); }
    }
}
