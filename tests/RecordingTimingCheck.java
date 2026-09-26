package dev.xr.rayneo.probe;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

final class RecordingTimingCheck {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static void until(Handler h,BooleanSupplier ready)throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!ready.getAsBoolean()){h.runReady();if(System.nanoTime()>limit)throw new AssertionError("Worker timeout");Thread.sleep(1);}
    }
    static BusinessEnvelope event(int type,String id,int action,boolean completed)throws Exception {
        return BusinessEnvelope.decode(BusinessEnvelope.encode(type,new JSONObject().put("uuid",id).put("action",action).put("code",1).put("completed",completed).toString()));
    }
    public static void main(String[] args)throws Exception {
        // First-event identity and missing data are part of the telemetry contract.
        SystemClock.now=100;
        RecordingTimeline timeline=new RecordingTimeline();timeline.mark("start_requested");
        SystemClock.now=120;timeline.mark("start_confirmed");
        SystemClock.now=130;timeline.mark("start_confirmed");
        check(timeline.snapshot().getJSONObject("duration_ms").getLong("start_to_confirmation")==20);
        check(!timeline.snapshot().getJSONObject("duration_ms").has("decode"));
        check(RecordingProgress.savingTitle("packaging").contains("封装为可播放"));
        check(RecordingProgress.savingTitle("decoding").contains("生成可播放"));
        check(RecordingProgress.savingTitle("legacy").equals("正在校验与保存"));
        File files=new File(args[1]);files.mkdirs();Handler handler=new Handler();boolean[] submitted={false};
        GlassesRecorder recorder=new GlassesRecorder(new Context(files),handler,new GlassesRecorder.Host(){
            public int recordingCapMinutes(){return 5;}
            public void send(int type,JSONObject body,String id){if(type==1)submitted[0]=true;}
            public void changed(JSONObject state){}
        });
        try {
            recorder.start();until(handler,()->submitted[0]);
            String id=recorder.state().getString("id");SystemClock.now+=20;
            recorder.event(event(1,id,2,false));
            Constructor<BusinessEnvelope> ctor=BusinessEnvelope.class.getDeclaredConstructor(int.class,String.class,int.class,byte[].class);ctor.setAccessible(true);
            int offset=args[0].equals("gap-filled")?240:0;
            recorder.event(ctor.newInstance(3,new JSONObject().put("uuid",id).put("offset",offset).toString(),240,new byte[240]));
            until(handler,()->recorder.state().optInt("bytes")==240);
            SystemClock.now+=100;recorder.stop();
            SystemClock.now+=40;recorder.stop(); // A repeated stop must not erase the first timestamp.
            SystemClock.now+=60;recorder.event(event(6,id,0,true));
            JSONObject waiting=recorder.state();
            check(recorder.busy()&&waiting.getString("phase").equals("saving"));
            check(waiting.getString("saving_stage").equals("tail_wait"));
            check(waiting.getJSONObject("timing").getJSONObject("duration_ms").getLong("stop_to_completed_report")==100);
            check(!waiting.getJSONObject("timing").getJSONObject("offset_ms").has("raw_sealed"));
            File folder=new File(files,"recordings/"+id);
            if(args[0].equals("package-failure"))check(new File(folder,"recording.ogg").createNewFile());
            handler.advance(999);check(recorder.busy());
            handler.advance(1);until(handler,()->!recorder.busy());
            JSONObject state=recorder.state(), points=state.getJSONObject("timing").getJSONObject("offset_ms");
            check(new File(folder,"source.rawopus").isFile());
            check(!new File(folder,"recording.wav").exists()); // saving never decodes any more
            if(args[0].equals("success")||args[0].equals("gap-filled")) {
                check(state.getString("phase").equals("saved"));
                check(points.has("raw_sealed")&&points.has("package_finished")&&points.has("saved_state"));
                check(!points.has("decode_started"));
                check(state.getJSONObject("timing").getJSONObject("duration_ms").getLong("completed_to_finalize_worker")==1000);
                File ogg=new File(folder,"recording.ogg");check(ogg.isFile()&&ogg.length()>0);
                check(state.getString("audio_file").equals("recording.ogg"));
                boolean gap=args[0].equals("gap-filled");
                check(state.getInt("silence_filled_packets")==(gap?1:0));
                check(state.getLong("duration_ms")==(gap?40:20));
            } else {
                check(state.getString("phase").equals("failed")&&points.has("failed"));
                check(!points.has("saved_state")&&!points.has("package_finished"));
                check(points.has("raw_sealed")&&points.has("package_started"));
                check(new File(folder,"recording.ogg").length()==0); // the pre-existing file was not overwritten
            }
            recorder.close();Field worker=GlassesRecorder.class.getDeclaredField("io");worker.setAccessible(true);
            check(((ExecutorService)worker.get(recorder)).awaitTermination(4,TimeUnit.SECONDS));
            // Python's JSON parser checks persisted nested content; the host JSONObject stub only parses flat inputs.
            System.out.println("Recording timing "+args[0]+" passed");
        } finally {recorder.close();}
    }
}
