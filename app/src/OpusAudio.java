package dev.xr.rayneo.probe;

import android.media.*;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.*;

/** Bounded raw Opus packets to mono PCM16; no microphone or file access. */
final class OpusAudio {
    static final class Decoded {
        final byte[] wav; final int rate, samples; final String codec;
        Decoded(byte[] wav, int rate, int samples, String codec) {
            this.wav = wav; this.rate = rate; this.samples = samples; this.codec = codec;
        }
    }
    // RFC 6716 section 3.1 TOC timing; MediaCodec validates the encoded frames.
    static int samples48k(byte[] packet) {
        if (packet.length < 1 || packet.length > 4096) throw new IllegalArgumentException("Opus packet size");
        int toc = packet[0] & 255, config = toc >>> 3, code = toc & 3;
        int[] silk = {480, 960, 1920, 2880}, celt = {120, 240, 480, 960};
        int perFrame = config < 12 ? silk[config % 4] : config < 16 ? ((config % 2 == 0) ? 480 : 960) : celt[config % 4];
        if (code == 3 && packet.length < 2) throw new IllegalArgumentException("Opus frame count missing");
        int count = code == 0 ? 1 : code == 3 ? packet[1] & 63 : 2;
        if (count == 0 || count * perFrame > 5760) throw new IllegalArgumentException("Opus duration limit");
        return count * perFrame;
    }
    static Decoded decode(List<byte[]> packets) throws Exception {
        if (packets.isEmpty() || packets.size() > 512) throw new IllegalArgumentException("Opus capture size");
        int total = 0;
        for (byte[] p : packets) total += samples48k(p);
        if (total > 48000 * 9) throw new IllegalArgumentException("Opus capture exceeds nine seconds");
        MediaFormat format = format();
        MediaCodec decoder = MediaCodec.createDecoderByType("audio/opus");
        byte[] pcm = null;
        try {
            String name = decoder.getName(); decoder.configure(format, null, null, 0); decoder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int index = 0, rate = 48000, channels = 1, encoding = AudioFormat.ENCODING_PCM_16BIT;
            long timestampSamples = 0, deadline = SystemClock.elapsedRealtime() + 10000;
            boolean sentEnd = false, gotEnd = false;
            while (!gotEnd) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("Opus decoder timeout");
                if (!sentEnd) {
                    int input = decoder.dequeueInputBuffer(1000);
                    if (input >= 0) {
                        if (index < packets.size()) {
                            byte[] packet = packets.get(index++);
                            ByteBuffer buffer = decoder.getInputBuffer(input); buffer.clear(); buffer.put(packet);
                            decoder.queueInputBuffer(input, 0, packet.length, timestampSamples * 1000000 / 48000, 0);
                            timestampSamples += samples48k(packet);
                        } else {
                            decoder.queueInputBuffer(input, 0, 0, timestampSamples * 1000000 / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sentEnd = true;
                        }
                    }
                }
                int out = decoder.dequeueOutputBuffer(info, 1000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = decoder.getOutputFormat();
                    rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = actual.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (rate != 48000 || channels != 1 || encoding != AudioFormat.ENCODING_PCM_16BIT)
                        throw new IllegalStateException("Unexpected Opus PCM format");
                } else if (out >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (output.size() + info.size > 48000 * 2 * 9) throw new IllegalStateException("PCM output limit");
                            ByteBuffer buffer = decoder.getOutputBuffer(out); buffer.position(info.offset); buffer.limit(info.offset + info.size);
                            byte[] block = new byte[info.size]; buffer.get(block); output.write(block); Arrays.fill(block, (byte)0);
                        }
                        gotEnd = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { decoder.releaseOutputBuffer(out, false); }
                }
            }
            pcm = output.toByteArray();
            if (pcm.length != total * 2) throw new IllegalStateException("Opus decoded duration mismatch");
            return new Decoded(PcmWav.encodeGlasses(pcm, rate), rate, pcm.length / 2, name);
        } finally {
            if (pcm != null) Arrays.fill(pcm, (byte)0);
            try { decoder.stop(); } catch (Exception ignored) {} decoder.release();
        }
    }
    static MediaFormat format() {
        MediaFormat format = MediaFormat.createAudioFormat("audio/opus", 48000, 1);
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put(new byte[]{'O','p','u','s','H','e','a','d'}).put((byte)1).put((byte)1)
            .putShort((short)0).putInt(16000).putShort((short)0).put((byte)0).flip();
        format.setByteBuffer("csd-0", head);
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).flip());
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000).flip());
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
        return format;
    }
    static void clear(List<byte[]> packets) { for (byte[] p : packets) Arrays.fill(p, (byte)0); packets.clear(); }
}
