package dev.xr.rayneo.probe;
import java.nio.file.*;
import java.util.Arrays;
public class AudioInputCheck {
    public static void main(String[] args) throws Exception {
        byte[] good = Files.readAllBytes(Paths.get(args[0])); AudioInput.checkWav(good);
        for (int length : new int[]{0, 20, 44, 4962, good.length-1}) {
            try { AudioInput.checkWav(Arrays.copyOf(good,length)); throw new AssertionError("Accepted truncated WAV"); }
            catch (IllegalArgumentException expected) {}
        }
        byte[] bad = good.clone(); bad[20]=3;
        try { AudioInput.checkWav(bad); throw new AssertionError("Accepted unsupported encoding"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("WAV integrity checks passed");
    }
}
