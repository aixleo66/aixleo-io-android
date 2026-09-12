package dev.xr.rayneo.probe;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.Arrays;

/** Explicit foreground recording of the phone microphone, bounded to eight seconds. */
final class PhoneRecording {
    volatile boolean cancelled, stop;
    interface Progress { void seconds(int seconds); }
    byte[] capture(Context context, Progress progress) throws Exception {
        final int rate = 16000, maximum = rate * 2 * 8;
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo phone = null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { phone = device; break; }
        if (phone == null) throw new CloudClient.Failure("未找到手机内置麦克风");
        int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new CloudClient.Failure("手机不支持本轮录音格式");
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, 8192));
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(maximum);
        byte[] buffer = new byte[2048];
        boolean started = false;
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED || !recorder.setPreferredDevice(phone))
                throw new CloudClient.Failure("无法选用手机内置麦克风");
            if (cancelled) throw new CloudClient.Failure("录音已取消，未上传");
            recorder.startRecording(); started = true;
            long start = SystemClock.elapsedRealtime(); int shown = -1;
            while (!cancelled && !stop && pcm.size() < maximum && SystemClock.elapsedRealtime() - start < 8500) {
                int count = recorder.read(buffer, 0, Math.min(buffer.length, maximum - pcm.size()), AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new CloudClient.Failure("手机录音读取失败，未上传");
                if (count > 0) {
                    AudioDeviceInfo routed = recorder.getRoutedDevice();
                    if (routed == null || routed.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC)
                        throw new CloudClient.Failure("录音未走手机内置麦克风，已停止且未上传");
                    pcm.write(buffer, 0, count);
                }
                int seconds = (int)((SystemClock.elapsedRealtime() - start) / 1000);
                if (seconds != shown) { shown = seconds; progress.seconds(seconds); }
                Thread.sleep(20);
            }
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CloudClient.Failure("录音已取消，未上传");
            if (pcm.size() < rate) throw new CloudClient.Failure("录音不足半秒，未上传");
            byte[] raw = pcm.toByteArray();
            try { return PcmWav.encode(raw, rate); } finally { Arrays.fill(raw, (byte)0); }
        } finally {
            if (started) try { recorder.stop(); } catch (RuntimeException ignored) {}
            recorder.release(); Arrays.fill(buffer, (byte)0); pcm.reset();
        }
    }
}
