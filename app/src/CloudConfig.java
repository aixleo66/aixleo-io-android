package dev.xr.rayneo.probe;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Device-local editable configuration, encrypted by an Android Keystore key. */
final class CloudConfig {
    private static final String ALIAS = "rayneo-test-cloud-config";
    private static javax.crypto.SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (javax.crypto.SecretKey)store.getKey(ALIAS, null);
    }
    static byte[] read(InputStream input, int limit) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException("Input exceeds limit"); out.write(b, 0, n); }
            return out.toByteArray();
        }
    }
    static JSONObject defaults() throws Exception {
        return new JSONObject().put("deepseek_url", "https://api.deepseek.com/chat/completions")
            .put("deepseek_model", "deepseek-flash").put("deepseek_key", "")
            .put("dashscope_url", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .put("dashscope_model", "qwen3-asr-flash").put("dashscope_key", "").put("glasses_address", "")
            .put("streaming_asr", false).put("dashscope_stream_model", "qwen3-asr-flash-realtime")
            .put("dashscope_stream_url", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
            .put("dashscope_text_model", "qwen-plus")
            .put("knowledge_url", "").put("knowledge_token", "").put("assistant_provider", "deepseek")
            .put("assistant_auto_exit_seconds", 15).put("assistant_followup_seconds", 10)
            // Keep every default that SdkProbeActivity also names in an optInt fallback in
            // step with that fallback: load() copies these into any stored configuration, so
            // a value here silently wins and the fallback becomes dead code.
            .put("assistant_display_wait_seconds", 60)
            .put("assistant_followup_enabled", true)
            // Recording length cap. 5 minutes was hard-coded and never had a reason behind it;
            // the official app shows no such limit in its settings and offers trimming, which
            // implies long recordings are expected. Kept configurable rather than removed:
            // free space is only checked once, when the recording starts.
            .put("recording_max_minutes", 30);
    }
    static JSONObject load(Context context) throws Exception {
        File seed = new File(context.getFilesDir(), "cloud-import.json");
        if (seed.isFile()) {
            JSONObject imported = new JSONObject(new String(read(new FileInputStream(seed), 16384), "UTF-8"));
            CloudClient.validateConfig(imported); save(context, imported);
            if (!seed.delete()) throw new IOException("Could not remove import file");
        }
        File file = new File(context.getFilesDir(), "cloud-config.enc");
        if (!file.exists()) return mergeKnowledgeImport(context,defaults());
        byte[] data = read(new FileInputStream(file), 32768);
        if (data.length < 29) throw new IOException("Invalid configuration");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, java.util.Arrays.copyOf(data, 12)));
        JSONObject loaded = new JSONObject(new String(cipher.doFinal(data, 12, data.length - 12), "UTF-8"));
        // A value equal to a since-retired default was inherited, not chosen (09-23 config drift).
        // Done once per MIGRATION bump, then written back with the marker, so a value chosen
        // afterwards survives. The marker lives in the config itself (not a default, so it is kept).
        boolean migrate = loaded.optInt(ConfigPersistence.MARKER, 0) < ConfigPersistence.MIGRATION;
        if (migrate) { ConfigPersistence.dropRetiredDefaults(loaded); loaded.put(ConfigPersistence.MARKER, ConfigPersistence.MIGRATION); }
        JSONObject defaults = defaults();
        for (java.util.Iterator<String> names = defaults.keys(); names.hasNext();) {
            String name = names.next(); if (!loaded.has(name)) loaded.put(name, defaults.get(name));
        }
        if (migrate) {
            // A failed write leaves the marker unset and retries next load; never block reading.
            try { save(context, loaded); } catch (Exception unsaved) {}
        }
        return mergeKnowledgeImport(context,loaded);
    }
    private static JSONObject mergeKnowledgeImport(Context context,JSONObject current)throws Exception{
        File file=new File(context.getFilesDir(),"knowledge-import.json");
        if(!file.isFile())return current;
        JSONObject patch=new JSONObject(new String(read(new FileInputStream(file),4096),"UTF-8"));
        JSONObject merged=new JSONObject(current.toString());
        merged.put("knowledge_url",patch.getString("knowledge_url")).put("knowledge_token",patch.getString("knowledge_token"));
        save(context,merged);
        if(!file.delete())throw new IOException("Knowledge import cleanup failed");
        return merged;
    }
    static void save(Context context, JSONObject value) throws Exception {
        CloudClient.validateConfig(value);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        File tmp = new File(context.getFilesDir(), "cloud-config.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            // Only values that differ from today's defaults are stored, so a later default change
            // reaches every key the user never set (09-23 config drift; see ConfigPersistence).
            JSONObject stored = ConfigPersistence.sparse(value, defaults());
            // Anything this code writes needs no retired-default migration; only files written
            // before the marker existed do (otherwise a 60 picked today would be undone next load).
            stored.put(ConfigPersistence.MARKER, ConfigPersistence.MIGRATION);
            out.write(cipher.getIV()); out.write(cipher.doFinal(stored.toString().getBytes("UTF-8"))); out.getFD().sync();
        }
        if (!tmp.renameTo(new File(context.getFilesDir(), "cloud-config.enc"))) throw new IOException("Configuration save failed");
    }
}
