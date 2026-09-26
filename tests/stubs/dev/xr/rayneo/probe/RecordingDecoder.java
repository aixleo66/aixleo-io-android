package dev.xr.rayneo.probe;
import java.io.*;
/** Decode scheduling test double: codec/audio quality is covered separately. */
final class RecordingDecoder {
    static boolean failDecode;
    static long decode(File source,File destination)throws Exception{
        android.os.SystemClock.now+=37;
        if(failDecode)throw new IOException("Fixture decode failure");
        try(FileOutputStream out=new FileOutputStream(destination)){out.write(new byte[]{1});}
        return 20;
    }
}
