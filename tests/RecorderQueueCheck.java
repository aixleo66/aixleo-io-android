package dev.xr.rayneo.probe;
import android.content.Context;
import android.os.Handler;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Saturate the real recorder executor; only Android scheduling and codec are substituted. */
final class RecorderQueueCheck {
    static Object get(Object target,String name)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    static BusinessEnvelope block(String id,int offset,byte value)throws Exception{
        Constructor<BusinessEnvelope> c=BusinessEnvelope.class.getDeclaredConstructor(int.class,String.class,int.class,byte[].class);c.setAccessible(true);
        byte[] bytes=new byte[240];Arrays.fill(bytes,value);
        return c.newInstance(3,new JSONObject().put("uuid",id).put("offset",offset).toString(),240,bytes);
    }
    public static void main(String[] args)throws Exception{
        for(int iteration=1;iteration<=10;iteration++){
            File files=new File(args[1],"queue-"+iteration);files.mkdirs();Handler h=new Handler();List<Integer> sent=new ArrayList<>();
            GlassesRecorder r=new GlassesRecorder(new Context(files),h,new GlassesRecorder.Host(){
            public int recordingCapMinutes(){return 5;}
                public void send(int type,JSONObject body,String id){sent.add(type);}
                public void changed(JSONObject state){}
            });
            ThreadPoolExecutor io=(ThreadPoolExecutor)get(r,"io");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            RecordingFile receiver=null;
            try{
                r.start();RecorderLifecycleCheck.until(h,()->sent.contains(1));String id=r.state().getString("id");
                r.event(block(id,0,(byte)7));RecorderLifecycleCheck.until(h,()->r.state().optInt("bytes")==240);
                receiver=(RecordingFile)get(r,"receiver");File raw=new File(files,"recordings/"+id+"/source.rawopus");byte[] before=Files.readAllBytes(raw.toPath());
                io.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                check(entered.await(4,TimeUnit.SECONDS),"worker did not block");
                check(io.getQueue().remainingCapacity()==128,"unexpected queue capacity");
                for(int i=0;i<127;i++)io.execute(()->{});
                r.event(block(id,240,(byte)8));check(io.getQueue().size()==128,"boundary append not queued");
                check(r.state().optString("phase").equals("recording"),"capacity boundary failed prematurely");
                r.event(block(id,480,(byte)9));
                check(r.state().optString("phase").equals("failed")&&!r.captures(),"overflow did not fail closed");
                check(sent.contains(4),"missing emergency stop");
                check(io.isShutdown(),"failure did not initiate worker shutdown");
                try{r.start();throw new AssertionError("failed recorder reused");}catch(IOException expected){}
                release.countDown();check(io.awaitTermination(4,TimeUnit.SECONDS),"worker did not drain");r.close();h.runReady();
                check(r.state().optString("phase").equals("failed"),"late callback resurrected saved state");
                byte[] after=Files.readAllBytes(raw.toPath());check(after.length==480,"accepted blocks not preserved or rejected block written");
                check(Arrays.equals(before,Arrays.copyOf(after,240)),"original prefix overwritten");
                check(!new File(raw.getParentFile(),"recording.wav").exists()&&!new File(raw.getParentFile(),"recording.ogg").exists(),"overflow produced completed WAV");
                RandomAccessFile fd=(RandomAccessFile)get(receiver,"file");
                check(!fd.getFD().valid(),"file descriptor still open after full queue and close");
                System.out.println("queue-capacity/overflow/drain/close iteration="+iteration+" passed");
            }finally{
                release.countDown();r.close();io.awaitTermination(4,TimeUnit.SECONDS);
                if(receiver!=null)receiver.close();
            }
        }
    }
}
