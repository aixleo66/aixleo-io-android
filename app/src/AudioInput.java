package dev.xr.rayneo.probe;

/** Reject incomplete RIFF audio before a billable cloud request. */
final class AudioInput {
    static long u32(byte[] b, int at) {
        return (b[at] & 255L) | ((b[at+1] & 255L) << 8) | ((b[at+2] & 255L) << 16) | ((b[at+3] & 255L) << 24);
    }
    static boolean tag(byte[] b, int at, String tag) {
        if (at + tag.length() > b.length) return false;
        for (int i = 0; i < tag.length(); i++) if ((b[at+i] & 255) != tag.charAt(i)) return false;
        return true;
    }
    static void checkWav(byte[] b) {
        if (b.length < 44 || !tag(b,0,"RIFF") || !tag(b,8,"WAVE") || u32(b,4) + 8 != b.length)
            throw new IllegalArgumentException("WAV 文件不完整或格式无效");
        boolean fmt = false, data = false; long byteRate = 0, dataSize = 0;
        for (int at = 12; at < b.length;) {
            if (at + 8 > b.length) throw new IllegalArgumentException("WAV 块头不完整");
            long size = u32(b,at+4), end = at + 8L + size;
            if (end > b.length) throw new IllegalArgumentException("WAV 音频被截断");
            if (tag(b,at,"fmt ")) {
                if (size < 16 || b[at+8] != 1 || b[at+9] != 0) throw new IllegalArgumentException("本轮 WAV 仅支持 PCM");
                fmt = true; byteRate = u32(b,at+16);
            }
            if (tag(b,at,"data")) { data = size > 0; dataSize += size; }
            at = (int)(end + (size & 1));
        }
        if (!fmt || !data || byteRate <= 0 || dataSize > byteRate * 60) throw new IllegalArgumentException("请使用 60 秒以内的完整 WAV 语音");
    }
}
