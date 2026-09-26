package dev.xr.rayneo.probe;
import java.io.*;
import java.nio.file.*;

public class RecordingMarkStoreCheck {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("mark-check").toFile();
        RecordingMarkStore store=new RecordingMarkStore(root);
        check(store.save(100000,6000,72000,null)==1);
        String first=Files.readString(new File(root,"mark-0001.json").toPath());
        check(first.contains("\"device_time_raw\":null"));
        check(first.contains("\"audio_seek_position_ms\":null"));
        check(store.save(103000,9000,80000,123L)==2);
        check(store.count()==1&&!new File(root,"mark-0002.json").exists());
        check(store.save(105000,11000,90000,123L)==1);
        check(Files.readString(new File(root,"mark-0002.json").toPath()).contains("\"device_time_raw\":123"));
        // Reopening the same folder cannot overwrite a prior mark or report success.
        try{new RecordingMarkStore(root).save(1,1,0,null);throw new AssertionError();}catch(IOException expected){}
        check(Files.readString(new File(root,"mark-0001.json").toPath()).equals(first));
        File invalid=new File(root,"absent/child");RecordingMarkStore failing=new RecordingMarkStore(invalid);
        try{failing.save(1,1,0,null);throw new AssertionError();}catch(IOException expected){}
        check(failing.count()==0);
        for(int n=2;n<60;n++)check(store.save(100000+n*5000,6000+n*5000,90000,null)==1);
        check(store.count()==60&&store.save(500000,400000,90000,null)==3);
        System.out.println("recording mark checks passed");
    }
}
