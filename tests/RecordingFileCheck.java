package dev.xr.rayneo.probe;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class RecordingFileCheck {
    interface Action{void run()throws Exception;}
    static void rejected(Action a)throws Exception{try{a.run();}catch(IOException e){return;}throw new AssertionError("Expected rejection");}
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("recording-check").toFile();
        byte[] a=new byte[240],b=new byte[240];Arrays.fill(a,(byte)1);Arrays.fill(b,(byte)2);
        try(RecordingFile r=new RecordingFile(new File(dir,"ordered"))){r.append(240,b);rejected(()->r.seal(true));r.append(0,a);r.append(120,Arrays.copyOfRange(a,120,240));if(r.received()!=480)throw new AssertionError();rejected(()->r.seal(false));if(r.seal(true)!=480)throw new AssertionError();rejected(()->r.append(0,a));}
        try(RecordingFile r=new RecordingFile(new File(dir,"conflict"))){r.append(0,a);rejected(()->r.append(0,b));rejected(()->r.seal(true));}
        try(RecordingFile r=new RecordingFile(new File(dir,"bounds"))){rejected(()->r.append(-1,a));rejected(()->r.append(RecordingFile.LIMIT-100,a));r.append(0,new byte[239]);rejected(()->r.seal(true));}
        for(File f:dir.listFiles())f.delete();dir.delete();System.out.println("recording checks passed");
    }
}
