package dev.xr.rayneo.probe;
import android.content.Context;
import android.os.Handler;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real recorder with deterministic scheduling and a codec test double. No radio/cloud. */
final class DeviceRecordingCheck {
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        File files=new File(args[1]);files.mkdirs();Handler h=new Handler();
        List<JSONObject> sent=new ArrayList<>();
        GlassesRecorder r=new GlassesRecorder(new Context(files),h,new GlassesRecorder.Host(){
            public int recordingCapMinutes(){return 5;}
            public void changed(JSONObject state){}
            public void send(int type,JSONObject body,String command)throws Exception{
                if(type==1){
                    check(new File(files,"recordings/"+body.getString("uuid")+"/source.rawopus").isFile());
                    check(body.optInt("action")==2&&!body.has("sid"));
                }
                sent.add(new JSONObject(body.toString()).put("wire_type",type));
            }
        });
        try{
            for(String bad:new String[]{"","../escape","C:\\escape","a/b","a.b",String.join("",Collections.nCopies(129,"x"))}){
                try{r.acceptDeviceStart(bad);throw new AssertionError("Unsafe ID accepted");}catch(IOException expected){}
            }
            check(sent.isEmpty()&&!r.busy());
            String id="device-menu-session_01";
            r.acceptDeviceStart(id);r.acceptDeviceStart(id);
            check(sent.isEmpty()); // ACK cannot precede asynchronous storage preparation.
            RecorderLifecycleCheck.until(h,()->!sent.isEmpty()&&sent.get(sent.size()-1).optInt("wire_type")==1);
            check(r.state().getString("id").equals(id)&&r.state().getString("source").equals("glasses_menu"));
            check(r.state().getString("phase").equals("starting")); // ACK sent is not audio confirmed.
            Constructor<BusinessEnvelope> ctor=BusinessEnvelope.class.getDeclaredConstructor(int.class,String.class,int.class,byte[].class);ctor.setAccessible(true);
            byte[] audio=new byte[240];Arrays.fill(audio,(byte)7);
            r.event(ctor.newInstance(3,new JSONObject().put("uuid",id).put("offset",0).toString(),audio.length,audio));
            RecorderLifecycleCheck.until(h,()->r.state().optInt("bytes")==240);
            r.acceptDeviceStart(id); // Retransmission re-ACKs but never truncates/restarts.
            check(r.state().getString("phase").equals("recording")&&r.state().optInt("bytes")==240);
            try{r.acceptDeviceStart("different-session");throw new AssertionError();}catch(IOException expected){}
            r.event(RecorderLifecycleCheck.event(4,"different-session",1,false));check(r.state().getString("phase").equals("recording"));
            r.event(ctor.newInstance(3,new JSONObject().put("uuid",id).put("offset",240).toString(),audio.length,audio));
            RecorderLifecycleCheck.until(h,()->r.state().optInt("bytes")==480);
            r.event(RecorderLifecycleCheck.event(4,id,1,false));check(r.state().getString("phase").equals("stopping"));
            int count=sent.size();r.acceptDeviceStart(id);check(sent.size()==count); // No new start ACK after stop.
            r.event(RecorderLifecycleCheck.event(6,id,0,true));
            check(r.state().getString("phase").equals("saving"));
            // Windows File.renameTo cannot replace the receipt like Android/Linux.
            // Real-device verification owns final sealing/MediaCodec/WAV assertions.
            File raw=new File(files,"recordings/"+id+"/source.rawopus");check(raw.length()==480);
            byte[] original=Files.readAllBytes(raw.toPath());
            GlassesRecorder afterRestart=new GlassesRecorder(new Context(files),h,new GlassesRecorder.Host(){
            public int recordingCapMinutes(){return 5;}
                public void changed(JSONObject state){}
                public void send(int type,JSONObject body,String command){throw new AssertionError("Replay sent a control");}
            });
            try{try{afterRestart.acceptDeviceStart(id);throw new AssertionError("Replay replaced file");}catch(IOException expected){}}
            finally{afterRestart.close();}
            check(Arrays.equals(original,Files.readAllBytes(raw.toPath()))&&r.state().getString("phase").equals("saving"));
            System.out.println("Device recording acceptance, duplicate, stop and preservation checks passed");
        }finally{
            r.close();Field f=GlassesRecorder.class.getDeclaredField("io");f.setAccessible(true);
            check(((ExecutorService)f.get(r)).awaitTermination(4,TimeUnit.SECONDS));
        }
    }
}
