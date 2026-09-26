package dev.xr.rayneo.probe;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Ogg Opus container written without decoding: page structure, CRC, granule, EOS, silence fill,
 * tail drop and no-overwrite. An independent decoder check (ffmpeg) runs from test_ogg_opus.py. */
public final class OggOpusWriterCheck {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    static final class Page { int flags, seq, segments; long granule; List<byte[]> packets = new ArrayList<>(); }

    static List<Page> parse(byte[] f) {
        List<Page> pages = new ArrayList<>(); int at = 0;
        while (at < f.length) {
            check(f[at] == 'O' && f[at + 1] == 'g' && f[at + 2] == 'g' && f[at + 3] == 'S', "capture pattern at " + at);
            Page p = new Page(); p.flags = f[at + 5] & 255;
            for (int i = 7; i >= 0; i--) p.granule = (p.granule << 8) | (f[at + 6 + i] & 255);
            for (int i = 3; i >= 0; i--) p.seq = (p.seq << 8) | (f[at + 18 + i] & 255);
            int stored = 0; for (int i = 3; i >= 0; i--) stored = (stored << 8) | (f[at + 22 + i] & 255);
            p.segments = f[at + 26] & 255;
            int body = 0, len = 0; List<Integer> lens = new ArrayList<>();
            for (int i = 0; i < p.segments; i++) { int v = f[at + 27 + i] & 255; body += v; len += v; if (v < 255) { lens.add(len); len = 0; } }
            int size = 27 + p.segments + body;
            byte[] copy = Arrays.copyOfRange(f, at, at + size); copy[22] = copy[23] = copy[24] = copy[25] = 0;
            check(crc(copy) == stored, "page CRC " + p.seq);
            int pos = at + 27 + p.segments;
            for (int l : lens) { p.packets.add(Arrays.copyOfRange(f, pos, pos + l)); pos += l; }
            pages.add(p); at += size;
        }
        return pages;
    }

    /** Bitwise reference CRC, independent of the writer's table. */
    static int crc(byte[] data) {
        int crc = 0;
        for (byte b : data) { crc ^= (b & 255) << 24; for (int j = 0; j < 8; j++) crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1; }
        return crc;
    }

    static boolean refused(File raw, TreeMap<Integer, Integer> ranges, File out) {
        try { OggOpusWriter.wrap(raw, ranges, out); return false; } catch (IOException e) { return true; }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("ogg-check").toFile();
        // 120 packets, packet i filled with byte i (TOC 0xBC as measured), plus a 100-byte tail.
        int n = 120; File raw = new File(dir, "source.rawopus");
        try (FileOutputStream out = new FileOutputStream(raw)) {
            for (int i = 0; i < n; i++) { byte[] p = new byte[240]; Arrays.fill(p, (byte) i); p[0] = (byte) 0xBC; out.write(p); }
            out.write(new byte[100]);
        }
        // Packet 7 missing entirely, packet 8 only partly received.
        TreeMap<Integer, Integer> ranges = new TreeMap<>();
        ranges.put(0, 7 * 240); ranges.put(9 * 240 - 60, n * 240 + 100);
        File ogg = new File(dir, "recording.ogg");
        OggOpusWriter.Result r = OggOpusWriter.wrap(raw, ranges, ogg);
        check(r.packets == n && r.silenceFilled == 2 && r.tailBytes == 100 && r.durationMs == n * 20L, "result counts");
        check(!new File(dir, "recording.ogg.tmp").exists() && r.oggBytes == ogg.length(), "renamed, size reported");
        List<Page> pages = parse(Files.readAllBytes(ogg.toPath()));
        check(pages.size() == 2 + 3, "head, tags and ceil(120/50) audio pages");
        check((pages.get(0).flags & 2) != 0 && pages.get(0).granule == 0 && pages.get(0).seq == 0, "BOS head page");
        byte[] head = pages.get(0).packets.get(0);
        check(new String(head, 0, 8, "US-ASCII").equals("OpusHead") && head[8] == 1 && head[9] == 2
            && ((head[10] & 255) | (head[11] & 255) << 8) == 312, "OpusHead v1, 2 channels, pre-skip 312");
        check(new String(pages.get(1).packets.get(0), 0, 8, "US-ASCII").equals("OpusTags") && pages.get(1).granule == 0, "OpusTags");
        List<byte[]> audio = new ArrayList<>();
        for (int i = 2; i < pages.size(); i++) {
            Page p = pages.get(i); audio.addAll(p.packets);
            check(p.seq == i, "sequence " + i);
            check(p.granule == (long) audio.size() * 960, "granule is cumulative 48 kHz samples");
            check(((p.flags & 4) != 0) == (i == pages.size() - 1), "EOS only on the last page");
        }
        check(audio.size() == n, "every packet present");
        for (int i = 0; i < n; i++) {
            if (i == 7 || i == 8) check(Arrays.equals(audio.get(i), OggOpusWriter.SILENCE), "silence at " + i);
            else check(audio.get(i).length == 240 && audio.get(i)[1] == (byte) i && audio.get(i)[0] == (byte) 0xBC, "packet " + i + " byte-identical");
        }
        check(refused(raw, ranges, ogg), "existing derivative is not overwritten");
        check(refused(raw, new TreeMap<>(), new File(dir, "x.ogg")) && !new File(dir, "x.ogg").exists()
            && !new File(dir, "x.ogg.tmp").exists(), "empty coverage refused, nothing left behind");
        TreeMap<Integer, Integer> tiny = new TreeMap<>(); tiny.put(0, 239);
        check(refused(raw, tiny, new File(dir, "y.ogg")) && !new File(dir, "y.ogg").exists(), "less than one packet refused");
        TreeMap<Integer, Integer> beyond = new TreeMap<>(); beyond.put(0, (int) raw.length() + 240);
        check(refused(raw, beyond, new File(dir, "z.ogg")), "coverage beyond the raw file refused");
        // Exactly 50 packets: the single audio page carries EOS.
        File raw50 = new File(dir, "fifty.rawopus"); Files.write(raw50.toPath(), new byte[50 * 240]);
        TreeMap<Integer, Integer> all = new TreeMap<>(); all.put(0, 50 * 240);
        File ogg50 = new File(dir, "fifty.ogg"); OggOpusWriter.wrap(raw50, all, ogg50);
        List<Page> p50 = parse(Files.readAllBytes(ogg50.toPath()));
        check(p50.size() == 3 && (p50.get(2).flags & 4) != 0 && p50.get(2).granule == 50 * 960, "page boundary EOS");
        for (File f : dir.listFiles()) f.delete(); dir.delete();
        System.out.println("ogg opus writer checks passed");
    }

    private OggOpusWriterCheck() {}
}
