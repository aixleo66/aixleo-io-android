package dev.xr.rayneo.probe;

import java.util.Arrays;

/** Stateful 48 kHz -> 16 kHz PCM16 FIR; 7 kHz cutoff, 63 taps, Hamming window. */
final class PcmDownsample {
    private final double[] taps = new double[63], history = new double[63];
    private int position, phase;
    PcmDownsample() {
        double sum = 0, cutoff = 7000.0 / 48000;
        for (int i = 0; i < taps.length; i++) {
            int x = i - 31;
            taps[i] = (x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x))
                * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / 62));
            sum += taps[i];
        }
        for (int i = 0; i < taps.length; i++) taps[i] /= sum;
    }
    byte[] process(byte[] pcm) {
        if ((pcm.length & 1) != 0) throw new IllegalArgumentException("PCM16 alignment");
        byte[] output = new byte[((pcm.length / 2 + phase) / 3) * 2]; int written = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            history[position] = (short)((pcm[i] & 255) | (pcm[i + 1] << 8));
            if (++phase == 3) {
                phase = 0; double sample = 0;
                for (int j = 0; j < taps.length; j++) sample += taps[j] * history[(position - j + 63) % 63];
                int value = Math.max(-32768, Math.min(32767, (int)Math.round(sample)));
                output[written++] = (byte)value; output[written++] = (byte)(value >> 8);
            }
            position = (position + 1) % 63;
        }
        return output;
    }
    void clear() { Arrays.fill(history, 0); position = phase = 0; }
}
