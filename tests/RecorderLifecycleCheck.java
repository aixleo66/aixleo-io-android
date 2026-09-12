package dev.xr.rayneo.probe;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import android.content.Context;
import android.os.*;
import org.json.*;

final class RecorderLifecycleCheck {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static void until(Handler h,BooleanSupplier ready)throws Exception{
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!ready.getAsBoolean()){h.runReady();if(System.nanoTime()>limit)throw new AssertionError("Worker timeout");Thread.sleep(1);}
    }
    static BusinessEnvelope event(int type,String id,int action,boolean completed)throws Exception{
        return BusinessEnvelope.decode(BusinessEnvelope.encode(type,new JSONObject().put("uuid",id).put("action",action).put("code",1).put("completed",completed).toString()));
    }
    static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    public static void main(String[] args)throws Exception{
        File files=new File(args[1]);files.mkdirs();Handler handler=new Handler();
        List<Integer> sent=new ArrayList<>();boolean[] rejectAck={false};
        GlassesRecorder recorder=new GlassesRecorder(new Context(files),handler,new GlassesRecorder.Host(){
            public void changed(JSONObject state){}
            public void send(int type,JSONObject body,String id)throws Exception{if(rejectAck[0]&&id.startsWith("rec-ack"))throw new IOException("Test ACK failure");sent.add(type);}
        });
        try{
            recorder.start();until(handler,()->sent.contains(1));
            String id=recorder.state().getString("id");recorder.event(event(1,id,2,false));
            Constructor<BusinessEnvelope> ctor=BusinessEnvelope.class.getDeclaredConstructor(int.class,String.class,int.class,byte[].class);ctor.setAccessible(true);
            byte[] packet=new byte[240];Arrays.fill(packet,(byte)7);
            recorder.event(ctor.newInstance(3,new JSONObject().put("uuid",id).put("offset",0).toString(),240,packet));
            until(handler,()->recorder.state().optInt("bytes")==240);
            if(args[0].equals("ack-failure"))rejectAck[0]=true;
            try{recorder.event(event(4,id,1,false));if(rejectAck[0])throw new AssertionError();}catch(IOException e){if(!rejectAck[0])throw e;}
            check(recorder.state().optString("phase").equals("stopping"));
            if(args[0].equals("completion")){
                List<Runnable> beforeCompletion=handler.callbacks();
                Runnable stopDeadline=beforeCompletion.get(beforeCompletion.size()-1);
                recorder.event(event(6,id,0,true));recorder.event(event(4,id,2,false));
                check(recorder.state().optString("phase").equals("saving"));
                stopDeadline.run();check(recorder.busy()&&recorder.state().optString("phase").equals("saving"));
                // This host test covers the state transition, not Android file replacement/MediaCodec.
                check(!new File(files,"recordings/"+id+"/recording.wav").exists());
            }else if(args[0].equals("stale")){
                set(recorder,"id","replacement");set(recorder,"phase","recording");
                handler.advance(30000);check(recorder.state().optString("phase").equals("recording"));
            }else{
                handler.advance(20000);
                if(args[0].equals("repeat")){recorder.event(event(4,id,2,false));recorder.stop();}
                handler.advance(9999);check(recorder.busy());
                handler.advance(1);check(!recorder.busy()&&!recorder.captures()&&recorder.state().optString("phase").equals("failed"));
                File original=new File(files,"recordings/"+id+"/source.rawopus");
                check(original.isFile()&&original.length()==240&&!new File(original.getParentFile(),"recording.wav").exists());
                handler.advance(300000);check(!recorder.busy());
            }
            System.out.println("Recorder lifecycle "+args[0]+" passed");
        }finally{
            recorder.close();Field f=GlassesRecorder.class.getDeclaredField("io");f.setAccessible(true);
            check(((ExecutorService)f.get(recorder)).awaitTermination(4,TimeUnit.SECONDS));
        }
    }
}
