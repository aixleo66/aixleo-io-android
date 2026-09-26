package dev.xr.rayneo.probe;

import java.io.*;
import java.util.*;

/** Wraps a recording's raw Opus packets into an Ogg Opus file (RFC 7845) without decoding.
 *
 * <p>Follows the official app (1.0.4 string pool, {@code AudioConvertUtil}): the phone keeps the
 * glasses' Opus as-is and only adds the Ogg container, so a recording costs what the glasses sent
 * (~43 MB/hour measured 09-23) instead of a 48 kHz stereo WAV (~691 MB/hour), and saving no longer
 * depends on a decoder finishing in time. Android plays Ogg Opus natively from 5.0.
 *
 * <p>Official behaviours copied: a packet the phone never fully received is replaced by the Opus
 * silence packet {@code F8 FF FE} ({@code silenceFilledSubFrames}); an incomplete tail is dropped
 * ({@code tailBytes}); the header uses 2 channels, pre-skip 312 and input rate 16000, the same as
 * the official writer and our decoder's OpusHead. Pages are flushed every 50 packets.
 *
 * <p>Measured 09-23: every packet of a 30-minute recording is 240 bytes with TOC 0xBC (CELT
 * wideband, stereo, one 20 ms frame). The fixed packet size is the only framing the raw file has. */
final class OggOpusWriter {
    static final int PACKET = 240;
    static final int SAMPLES_PER_PACKET = 960; // 20 ms at 48 kHz, the Ogg Opus granule clock
    static final int PRE_SKIP = 312;
    static final int PACKETS_PER_PAGE = 50;
    static final byte[] SILENCE = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};
    private static final int SERIAL = 0x52415931; // fixed: one logical stream per file
    private static final int[] CRC = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            CRC[i] = r;
        }
    }

    static final class Result {
        final int packets, silenceFilled, tailBytes;
        final long durationMs, oggBytes;
        Result(int packets, int silenceFilled, int tailBytes, long oggBytes) {
            this.packets = packets; this.silenceFilled = silenceFilled; this.tailBytes = tailBytes;
            this.durationMs = packets * 20L; this.oggBytes = oggBytes;
        }
    }

    private OggOpusWriter() {}

    /** @param ranges received byte ranges of {@code raw}, {start -> end}, as RecordingFile keeps them.
     *  The file is written to {@code ogg} + ".tmp" first and renamed only when complete. */
    static Result wrap(File raw, NavigableMap<Integer, Integer> ranges, File ogg) throws IOException {
        if (ranges.isEmpty()) throw new IOException("No audio received");
        long end = ranges.lastEntry().getValue();
        if (end > raw.length()) throw new IOException("Coverage beyond raw file");
        int packets = (int) (end / PACKET), tail = (int) (end % PACKET);
        if (packets == 0) throw new IOException("No complete Opus packet");
        if (ogg.exists()) throw new IOException("Derivative already exists");
        File tmp = new File(ogg.getPath() + ".tmp");
        if (tmp.exists() && !tmp.delete()) throw new IOException("Stale temporary file");
        int silence = 0;
        try (RandomAccessFile in = new RandomAccessFile(raw, "r");
             FileOutputStream file = new FileOutputStream(tmp);
             OutputStream out = new BufferedOutputStream(file, 1 << 16)) {
            int[] seq = {0};
            page(out, 0x02, 0, seq, Collections.singletonList(head()));
            page(out, 0, 0, seq, Collections.singletonList(tags()));
            List<byte[]> batch = new ArrayList<>(PACKETS_PER_PAGE);
            byte[] buffer = new byte[PACKET];
            for (int i = 0; i < packets; i++) {
                int from = i * PACKET;
                if (covered(ranges, from, from + PACKET)) {
                    in.seek(from); in.readFully(buffer);
                    batch.add(buffer.clone());
                } else { batch.add(SILENCE); silence++; }
                boolean last = i == packets - 1;
                if (batch.size() == PACKETS_PER_PAGE || last) {
                    page(out, last ? 0x04 : 0, (long) (i + 1) * SAMPLES_PER_PACKET, seq, batch);
                    batch.clear();
                }
            }
            out.flush();
            file.getFD().sync(); // durable before the rename, as the raw file and the receipt are
        } catch (IOException | RuntimeException e) { tmp.delete(); throw e; }
        if (!tmp.renameTo(ogg)) { tmp.delete(); throw new IOException("Ogg rename"); }
        return new Result(packets, silence, tail, ogg.length());
    }

    /** True when [from, to) lies inside one received range. */
    static boolean covered(NavigableMap<Integer, Integer> ranges, int from, int to) {
        Map.Entry<Integer, Integer> r = ranges.floorEntry(from);
        return r != null && r.getValue() >= to;
    }

    static byte[] head() {
        byte[] h = new byte[19];
        System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, h, 0, 8);
        h[8] = 1; h[9] = 2; // version 1, 2 channels
        h[10] = (byte) PRE_SKIP; h[11] = (byte) (PRE_SKIP >> 8);
        int rate = 16000; h[12] = (byte) rate; h[13] = (byte) (rate >> 8); h[14] = (byte) (rate >> 16); h[15] = (byte) (rate >> 24);
        return h; // output gain 0, channel mapping family 0
    }

    static byte[] tags() {
        byte[] vendor = "aixleo-io".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] t = new byte[8 + 4 + vendor.length + 4];
        System.arraycopy("OpusTags".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, t, 0, 8);
        t[8] = (byte) vendor.length;
        System.arraycopy(vendor, 0, t, 12, vendor.length);
        return t; // zero user comments
    }

    /** One Ogg page holding whole packets (each under 255 bytes here, so one lacing value each). */
    private static void page(OutputStream out, int flags, long granule, int[] seq, List<byte[]> packets) throws IOException {
        int segments = 0;
        for (byte[] p : packets) segments += p.length / 255 + 1;
        if (segments > 255) throw new IOException("Too many segments");
        int body = 0;
        for (byte[] p : packets) body += p.length;
        byte[] page = new byte[27 + segments + body];
        page[0] = 'O'; page[1] = 'g'; page[2] = 'g'; page[3] = 'S';
        page[5] = (byte) flags;
        for (int i = 0; i < 8; i++) page[6 + i] = (byte) (granule >>> (8 * i));
        for (int i = 0; i < 4; i++) page[14 + i] = (byte) (SERIAL >>> (8 * i));
        for (int i = 0; i < 4; i++) page[18 + i] = (byte) (seq[0] >>> (8 * i));
        page[26] = (byte) segments;
        int at = 27;
        for (byte[] p : packets) {
            int left = p.length;
            while (left >= 255) { page[at++] = (byte) 255; left -= 255; }
            page[at++] = (byte) left;
        }
        for (byte[] p : packets) { System.arraycopy(p, 0, page, at, p.length); at += p.length; }
        int crc = 0;
        for (byte b : page) crc = (crc << 8) ^ CRC[((crc >>> 24) ^ b) & 0xFF];
        for (int i = 0; i < 4; i++) page[22 + i] = (byte) (crc >>> (8 * i));
        out.write(page);
        seq[0]++;
    }
}
