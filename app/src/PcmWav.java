package dev.xr.rayneo.probe;

import java.nio.*;
import java.nio.charset.StandardCharsets;

final class PcmWav {
    static byte[] encode(byte[] pcm, int rate) {
        if (rate != 16000 || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > rate * 2 * 8)
            throw new IllegalArgumentException("Invalid bounded mono PCM16 recording");
        return pack(pcm, rate);
    }
    static byte[] encodeGlasses(byte[] pcm, int rate) {
        if (rate != 48000 || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > rate * 2 * 9)
            throw new IllegalArgumentException("Invalid glasses PCM16 recording");
        return pack(pcm, rate);
    }
    private static byte[] pack(byte[] pcm, int rate) {
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1);
        out.putInt(rate).putInt(rate * 2).putShort((short)2).putShort((short)16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return out.array();
    }
}
