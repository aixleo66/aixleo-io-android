package dev.xr.rayneo.probe;
import java.nio.file.*;
final class PcmWavCheck {
    public static void main(String[] args) throws Exception {
        byte[] pcm = new byte[16000 * 2 * 8];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte)(i % 251);
        byte[] wav = PcmWav.encode(pcm, 16000);
        AudioInput.checkWav(wav);
        Files.write(Paths.get(args[0]), wav);
        for (int size : new int[]{0, 1, 256002}) {
            try { PcmWav.encode(new byte[size], 16000); throw new AssertionError("Invalid PCM accepted"); }
            catch (IllegalArgumentException expected) {}
        }
        try { PcmWav.encode(new byte[32000], 48000); throw new AssertionError("Wrong rate accepted"); }
        catch (IllegalArgumentException expected) {}
    }
}
