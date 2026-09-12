package dev.xr.rayneo.probe;
import java.io.*;
/** Decode scheduling test double: codec/audio quality is covered separately. */
final class RecordingDecoder {
    static long decode(File source,File destination)throws Exception{
        try(FileOutputStream out=new FileOutputStream(destination)){out.write(new byte[]{1});}
        return 20;
    }
}
