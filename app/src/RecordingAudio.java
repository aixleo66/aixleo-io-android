package dev.xr.rayneo.probe;

import java.io.File;

/** Which file of a recording folder is the playable audio. From 09-23 new recordings save
 * {@code recording.ogg} (Ogg Opus, see OggOpusWriter); older ones have {@code recording.wav}. */
final class RecordingAudio {
    private RecordingAudio() {}

    static File playable(File folder) {
        File ogg = new File(folder, "recording.ogg");
        if (ogg.isFile() && ogg.length() > 0) return ogg;
        File wav = new File(folder, "recording.wav");
        return wav.isFile() && wav.length() > 44 ? wav : null;
    }

    static boolean ogg(File file) { return file.getName().endsWith(".ogg"); }
    static String mime(File file) { return ogg(file) ? "audio/ogg" : "audio/wav"; }
    static String extension(File file) { return ogg(file) ? ".ogg" : ".wav"; }
    static String label(File file) { return ogg(file) ? "Opus" : "WAV"; }

    /** "" when nothing was missing; otherwise how much of the recording is filled-in silence, so a
     * mostly-missing recording is not shown as an ordinary saved one (review 09-23 round 5). */
    static String gapNote(int silencePackets, long durationMs) {
        if (silencePackets <= 0) return "";
        long ms = silencePackets * 20L;
        String amount = ms < 1000 ? "不足 1 秒" : (ms / 1000) + " 秒";
        boolean most = durationMs > 0 && ms * 2 >= durationMs;
        return (most ? " · 大部分缺失（" : " · 含 ") + amount + (most ? "已补静音）" : "缺失，已补静音");
    }
}
