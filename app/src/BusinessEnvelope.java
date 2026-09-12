package dev.xr.rayneo.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded version-1 business envelope; never a BLE transport frame. */
public final class BusinessEnvelope {
    public final int type;
    public final String json;
    public final int dataBytes;
    public final byte[] audio;
    private BusinessEnvelope(int type, String json, int dataBytes, byte[] audio) { this.type = type; this.json = json; this.dataBytes = dataBytes; this.audio = audio; }
    private static void varint(ByteArrayOutputStream out, int n) {
        do { int b = n & 127; n >>>= 7; out.write(b | (n == 0 ? 0 : 128)); } while (n != 0);
    }
    public static byte[] encode(int type, String json) {
        return encode(type, json, false);
    }
    public static byte[] encode(int type, String json, boolean explicitEmptyData) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (type < 0 || body.length > 65536) throw new IllegalArgumentException("Envelope limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(8); out.write(1); out.write(16); varint(out, type);
        out.write(26); varint(out, body.length); out.write(body, 0, body.length);
        if (explicitEmptyData) { out.write(34); out.write(0); }
        return out.toByteArray();
    }
    private static long read(ByteBuffer in) {
        long n = 0;
        for (int i = 0; i < 10; i++) {
            if (!in.hasRemaining()) throw new IllegalArgumentException("Truncated varint");
            int b = in.get() & 255;
            if (i == 9 && b > 1) throw new IllegalArgumentException("Varint overflow");
            n |= (long)(b & 127) << (i * 7);
            if (b < 128) return n;
        }
        throw new IllegalArgumentException("Varint overflow");
    }
    public static BusinessEnvelope decode(byte[] bytes) throws Exception {
        return decode(bytes, false);
    }
    public static BusinessEnvelope decode(byte[] bytes, boolean captureAudio) throws Exception {
        return decode(bytes, captureAudio, 4096);
    }
    public static BusinessEnvelope decodeRecording(byte[] bytes, boolean capture) throws Exception {
        return decode(bytes, capture, 65536);
    }
    private static BusinessEnvelope decode(byte[] bytes, boolean captureAudio, int captureLimit) throws Exception {
        if (bytes.length > 131100) throw new IllegalArgumentException("Envelope limit");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int seen = 0, type = -1, dataBytes = 0, dataOffset = 0; long version = -1; String json = "{}";
        while (in.hasRemaining()) {
            long tag = read(in); long field = tag >>> 3; int wire = (int)(tag & 7);
            if (field == 0 || field > 0x1fffffff) throw new IllegalArgumentException("Invalid tag");
            if (field <= 4) {
                int mask = 1 << (int)field;
                if ((seen & mask) != 0) throw new IllegalArgumentException("Duplicate field");
                seen |= mask;
                if (wire != (field <= 2 ? 0 : 2)) throw new IllegalArgumentException("Wrong wire type");
            }
            if (wire == 0) {
                long value = read(in);
                if (field == 1) version = value;
                if (field == 2) {
                    if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Type limit");
                    type = (int)value;
                }
            } else {
                long size = wire == 2 ? read(in) : wire == 1 ? 8 : wire == 5 ? 4 : -1;
                if (size < 0 || size > in.remaining() || size > 65536) throw new IllegalArgumentException("Invalid field size");
                if (field == 3) {
                    ByteBuffer body = in.slice(); body.limit((int)size);
                    json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(body).toString();
                }
                if (field == 4) { dataBytes = (int)size; dataOffset = in.position(); }
                in.position(in.position() + (int)size);
            }
        }
        if (version != 1 || type < 0) throw new IllegalArgumentException("Unsupported envelope");
        byte[] audio = null;
        if (captureAudio && type == 3 && dataBytes > 0) {
            if (dataBytes > captureLimit) throw new IllegalArgumentException("Audio payload limit");
            audio = java.util.Arrays.copyOfRange(bytes, dataOffset, dataOffset + dataBytes);
        }
        return new BusinessEnvelope(type, json, dataBytes, audio);
    }
}
