package dev.xr.rayneo.probe;

import java.io.*;
import java.nio.file.*;

/** Which file of a recording folder is played and exported, and how filled-in silence is shown. */
public final class RecordingAudioCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("recording-audio").toFile();
        check(RecordingAudio.playable(dir) == null, "empty folder has nothing to play");
        File wav = new File(dir, "recording.wav");
        Files.write(wav.toPath(), new byte[44]);
        check(RecordingAudio.playable(dir) == null, "44-byte failure placeholder is not playable");
        Files.write(wav.toPath(), new byte[45]);
        check(wav.equals(RecordingAudio.playable(dir)), "old recording: WAV still plays");
        check(RecordingAudio.mime(wav).equals("audio/wav") && RecordingAudio.extension(wav).equals(".wav") && RecordingAudio.label(wav).equals("WAV"), "WAV export type");
        File ogg = new File(dir, "recording.ogg");
        Files.write(ogg.toPath(), new byte[0]);
        check(wav.equals(RecordingAudio.playable(dir)), "empty ogg is ignored");
        Files.write(ogg.toPath(), new byte[1]);
        check(ogg.equals(RecordingAudio.playable(dir)), "ogg wins when both exist");
        check(RecordingAudio.mime(ogg).equals("audio/ogg") && RecordingAudio.extension(ogg).equals(".ogg") && RecordingAudio.label(ogg).equals("Opus"), "Ogg export type");
        check(RecordingAudio.gapNote(0, 60000).isEmpty(), "complete recording: no note");
        check(RecordingAudio.gapNote(3, 3599260).equals(" · 含 不足 1 秒缺失，已补静音"), "09-23 60-minute case: 3 packets");
        check(RecordingAudio.gapNote(250, 60000).equals(" · 含 5 秒缺失，已补静音"), "minority gap");
        check(RecordingAudio.gapNote(1500, 1800000).equals(" · 含 30 秒缺失，已补静音"), "30 s of 30 min");
        check(RecordingAudio.gapNote(45000, 1800000).equals(" · 大部分缺失（900 秒已补静音）"), "half missing is flagged as most");
        for (File f : dir.listFiles()) f.delete(); dir.delete();
        System.out.println("recording audio checks passed");
    }

    private RecordingAudioCheck() {}
}
