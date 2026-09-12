package dev.xr.rayneo.probe;
import java.nio.file.*;
final class OpusAudioCheck {
    public static void main(String[] args) throws Exception {
        // RFC 6716 TOC: SILK 60ms, CELT 20ms, and three 20ms CELT frames.
        if (OpusAudio.samples48k(new byte[]{24}) != 2880) throw new AssertionError("SILK 60ms");
        if (OpusAudio.samples48k(new byte[]{(byte)0xf8,(byte)0xff,(byte)0xfe}) != 960) throw new AssertionError("CELT 20ms");
        if (OpusAudio.samples48k(new byte[]{(byte)0xfb,3}) != 2880) throw new AssertionError("Three frames");
        for (byte[] bad : new byte[][]{new byte[0], new byte[4097], {(byte)0xfb}, {(byte)0xfb,0}, {(byte)0xfb,7}}) {
            try { OpusAudio.samples48k(bad); throw new AssertionError("Invalid duration accepted"); }
            catch (IllegalArgumentException expected) {}
        }
        byte[] pcm = new byte[380160 * 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte)(i % 251);
        byte[] wav = PcmWav.encodeGlasses(pcm, 48000); AudioInput.checkWav(wav);
        Files.write(Paths.get(args[0]), wav);
        for (int size : new int[]{0, 1, 864002}) {
            try { PcmWav.encodeGlasses(new byte[size], 48000); throw new AssertionError("Invalid PCM accepted"); }
            catch (IllegalArgumentException expected) {}
        }
    }
}
