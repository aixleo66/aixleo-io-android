package dev.xr.rayneo.probe;
import java.util.*;
import java.io.*;
public final class PcmDownsampleCheck {
    static byte[] tone(int hz) {
        byte[] pcm = new byte[96000];
        for (int i = 0; i < 48000; i++) {
            int value = (int)(12000 * Math.sin(2 * Math.PI * hz * i / 48000));
            pcm[2*i] = (byte)value; pcm[2*i+1] = (byte)(value >> 8);
        }
        return pcm;
    }
    static double rms(byte[] data) {
        double sum = 0; int count = 0;
        for (int i = 400; i < data.length; i += 2) { int v = (short)((data[i] & 255) | data[i+1] << 8); sum += v * (double)v; count++; }
        return Math.sqrt(sum / count);
    }
    public static void main(String[] args) throws Exception {
        byte[] input = tone(1000), whole = new PcmDownsample().process(input);
        if (whole.length != 32000 || rms(whole) < 8000) throw new AssertionError("Speech band/duration");
        if (rms(new PcmDownsample().process(tone(12000))) > 50) throw new AssertionError("Aliased high frequency");
        PcmDownsample split = new PcmDownsample(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < input.length; i += 202) out.write(split.process(Arrays.copyOfRange(input, i, Math.min(input.length, i+202))));
        if (!Arrays.equals(whole, out.toByteArray())) throw new AssertionError("Chunk boundary corruption");
        split.clear(); if (!Arrays.equals(whole, split.process(input))) throw new AssertionError("State retained after close");
        System.out.println("Resampling signal, chunk boundaries, reset passed");
    }
}
