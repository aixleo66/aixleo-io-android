package dev.xr.rayneo.probe;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One recording task. Raw bytes stay local; cloud services are not involved. */
final class GlassesRecorder {
    interface Host {void send(int type,JSONObject body,String id)throws Exception;void changed(JSONObject state);
        /** Automatic stop, in minutes. Asked of the host rather than read here: the recorder
         * has no business knowing where configuration lives, and pulling CloudConfig in would
         * drag CloudClient behind it into every offline test that compiles this class. */
        int recordingCapMinutes();}
    private final Context context;private final Handler main;private final Host host;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128)) {
        @Override protected void terminated() {
            // Cleanup cannot depend on another queue slot: overload may have used every slot.
            try { if(receiver!=null)receiver.close(); } catch(Exception ignored) { }
            try { if(folder!=null)manifest(); } catch(Exception ignored) { }
        }
    };
    private String id;private File folder;private RecordingFile receiver;private long started,ackAt;private volatile String phase="idle",error="";
    private volatile boolean receiving,completed;private boolean finalizing,closed;private volatile int bytes;private volatile String coverage="";private volatile long duration;private long lastFlush;
    private String stopDeadlineId;
    private boolean deviceInitiated, startSubmitted;
    private RecordingMarkStore marks;private long ackElapsed;private boolean markPending;
    private volatile String markStatus="not_requested";
    private volatile RecordingTimeline timeline;
    private volatile String savingStage="";
    private volatile int silenceFilled,tailBytes;private volatile String audioFile="";
    private boolean labMarks(){return "dev.xr.rayneo.sdklab".equals(context.getPackageName());}
    GlassesRecorder(Context c,Handler h,Host host){context=c;main=h;this.host=host;}
    boolean busy(){return id!=null&&!phase.equals("saved")&&!phase.equals("failed");}
    boolean captures(){return receiving;}
    JSONObject state(){JSONObject j=new JSONObject();try{j.put("id",id==null?"":id).put("source",deviceInitiated?"glasses_menu":"phone").put("phase",phase).put("bytes",bytes).put("coverage",coverage).put("created_at_ms",started).put("confirmed_at_ms",ackAt).put("duration_ms",duration).put("completed_received",completed).put("error",error).put("audio_uploaded",false).put("saving_stage",savingStage).put("audio_file",audioFile).put("silence_filled_packets",silenceFilled).put("discarded_tail_bytes",tailBytes);if(timeline!=null)j.put("timing",timeline.snapshot());}catch(Exception ignored){}if(labMarks())try{j.put("mark_count",marks==null?0:marks.count()).put("mark_status",markStatus);}catch(Exception ignored){}return j;}
    private void publish(){host.changed(state());}
    private void savingStage(String value){savingStage=value;main.post(this::publish);}
    private void submit(Runnable task){try{io.execute(task);}catch(RejectedExecutionException e){fail("接收队列已满，原件保留；本次文件不能标为完整");}}
    private void manifest()throws Exception{
        File tmp=new File(folder,"receipt.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(state().toString().getBytes("UTF-8"));out.getFD().sync();}
        if(!tmp.renameTo(new File(folder,"receipt.json")))throw new IOException("Manifest rename");
    }
    void start()throws Exception{
        start(null);
    }
    // A device-provided session is also a directory component. Reject unsafe IDs
    // and existing folders; a delayed/replayed start must never truncate a file.
    static boolean validDeviceId(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,128}");}
    void acceptDeviceStart(String deviceId)throws Exception{
        if(!validDeviceId(deviceId))throw new IOException("Invalid recording session");
        if(deviceInitiated&&deviceId.equals(id)&&receiving){
            if(startSubmitted&&(phase.equals("starting")||phase.equals("recording")))sendDeviceAcceptance();
            return;
        }
        start(deviceId);
    }
    private void sendDeviceAcceptance()throws Exception{
        host.send(1,new JSONObject().put("uuid",id).put("action",2).put("code",1),"rec-accept-"+id);
    }
    private void start(String deviceId)throws Exception{
        if(busy()||closed)throw new IOException("Recorder busy");silenceFilled=0;tailBytes=0;audioFile="";
        File root=new File(context.getFilesDir(),"recordings");if(!root.exists()&&!root.mkdirs())throw new IOException("Storage unavailable");
        int capMinutes=recordingCapMinutes();
        // Opus from the glasses runs about 27 KB/s (measured 2026-09-22: 302,400 B for an
        // 11 s clip). Ask for what the configured length would actually need, with the old
        // 128 MB as the floor -- a 60 minute cap against a 128 MB check would fill the disk
        // mid-recording, and free space is not re-checked once recording starts.
        long needed=Math.max(128L*1024*1024,(long)capMinutes*60L*30L*1024L);
        if(root.getUsableSpace()<needed)
            throw new IOException("手机可用空间不足，"+capMinutes+" 分钟录音约需 "+(needed/1024/1024)+" MB");
        String nextId=deviceId==null?UUID.randomUUID().toString():deviceId;
        File nextFolder=new File(root,nextId);if(!nextFolder.mkdir())throw new IOException("Recording directory already exists or unavailable");
        id=nextId;folder=nextFolder;marks=labMarks()?new RecordingMarkStore(folder):null;markPending=false;markStatus="not_requested";ackElapsed=0;deviceInitiated=deviceId!=null;startSubmitted=false;
        timeline=new RecordingTimeline();timeline.mark("start_requested");savingStage="";
        started=System.currentTimeMillis();ackAt=0;bytes=0;coverage="";duration=0;error="";completed=false;finalizing=false;stopDeadlineId=null;phase="starting";receiving=true;publish();
        final String current=id;
        submit(()->{try{receiver=new RecordingFile(new File(folder,"source.rawopus"));manifest();main.post(()->{if(!current.equals(id)||closed||!receiving)return;if(!phase.equals("starting")){fail("开始录音已取消");return;}try{
            host.send(14,new JSONObject().put("isAppForeground",true),"rec-foreground-"+current);
            // Official Android startRecording: type=1, mode=1, sid in epoch seconds.
            // code belongs to the response, not an app-initiated start request.
            // Device menu starts use its UUID and an acceptance ACK, not a second
            // phone start command. Storage has been opened before either send.
            startSubmitted=true;
            if(deviceInitiated)sendDeviceAcceptance();
            else host.send(1,new JSONObject().put("uuid",current).put("sid",String.valueOf(started/1000)).put("action",1).put("type",1).put("mode",1),"rec-start-"+current);
            main.postDelayed(()->{if(current.equals(id)&&phase.equals("starting")){try{stop();}catch(Exception ignored){}fail("眼镜未确认开始；停止请求已尝试，原件保留");}},15000);
            final int capForRound=capMinutes;
            main.postDelayed(()->{if(current.equals(id)&&busy()){try{stop();}
                catch(Exception e){fail("达到本轮 "+capForRound+" 分钟上限，停止请求失败");}}},capForRound*60000L);
        }catch(Exception e){fail("录音开始命令失败");}});}catch(Exception e){main.post(()->fail("无法准备录音存储"));}});
    }
    /** Clamped so a bad stored value cannot disable the stop altogether or wedge it at zero.
     *
     * <p>Was a hard-coded 5 minutes with no recorded rationale, and the cap was never
     * actually reached in testing, so behaviour past it is unverified in either direction. The
     * official app exposes no such setting and offers trimming, implying it expects long ones. */
    private int recordingCapMinutes(){
        try{ return Math.max(5,Math.min(120,host.recordingCapMinutes())); }
        catch(Exception unavailable){ return 30; }
    }
    void stop()throws Exception{
        if(!beginStopping())return;
        host.send(4,new JSONObject().put("uuid",id).put("action",1).put("code",2),"rec-stop-"+id);
    }
    private boolean beginStopping(){
        if(!busy()||completed||phase.equals("saving"))return false;
        boolean first=!phase.equals("stopping");if(first&&timeline!=null)timeline.mark("stop_requested");phase="stopping";
        // Arm before any transport call, including a device-initiated stop ACK.
        if(!id.equals(stopDeadlineId)){
            final String current=id;stopDeadlineId=current;
            main.postDelayed(()->{if(current.equals(id)&&busy()&&receiving&&!completed&&!finalizing)
                fail("未收到完整结束回报；原始数据已保留");},30000);
        }
        if(first)publish();return first;
    }
    void event(BusinessEnvelope wire)throws Exception{
        if(id==null)return;JSONObject j=new JSONObject(wire.json);if(!id.equals(j.optString("uuid")))return;
        if(phase.equals("saved")&&wire.type==3&&wire.dataBytes>0){fail("保存后收到迟到数据；文件完整性需复核");return;}
        if(!receiving)return;
        if(wire.type==1&&j.optInt("action")==2){if(j.optInt("code")!=1){fail("眼镜未允许开始录音");return;}if(phase.equals("starting")){phase="recording";ackAt=System.currentTimeMillis();ackElapsed=SystemClock.elapsedRealtime();timeline.mark("start_confirmed");publish();}}
        else if(wire.type==3&&wire.audio!=null){
            if(phase.equals("starting")){phase="recording";ackAt=System.currentTimeMillis();ackElapsed=SystemClock.elapsedRealtime();timeline.mark("start_confirmed");publish();}
            if(finalizing){fail("结束后仍有迟到音频，文件需复核");return;}
            long offset=j.getLong("offset");if(offset<0||offset>RecordingFile.LIMIT)throw new IOException("Offset bounds");
            if(timeline!=null)timeline.audioReceived();
            byte[] block=wire.audio.clone();submit(()->{try{receiver.append((int)offset,block);bytes=receiver.received();coverage=receiver.coverage();long now=SystemClock.elapsedRealtime();if(now-lastFlush>=1000){receiver.sync();manifest();lastFlush=now;main.post(this::publish);}}catch(Exception e){main.post(()->fail("接收数据冲突或写盘失败；原件保留"));}finally{Arrays.fill(block,(byte)0);}});
        }else if(wire.type==11&&j.optInt("action")==1&&labMarks()){mark(j);}
        else if(wire.type==4){beginStopping();if(j.optInt("action")==1)host.send(4,new JSONObject().put("uuid",id).put("action",2).put("code",j.optInt("code",2)),"rec-ack-"+id);}
        else if(wire.type==6&&j.optBoolean("completed")&&!completed){completed=true;timeline.mark("completed_received");savingStage="tail_wait";phase="saving";publish();String current=id;
            main.postDelayed(()->{if(!current.equals(id)||!receiving)return;finalizing=true;submit(()->{try{timeline.mark("finalize_started");savingStage("raw_validation");receiver.seal(completed);NavigableMap<Integer,Integer> ranges=receiver.ranges();receiver.close();timeline.mark("raw_sealed");savingStage("receipt_before_package");manifest();
                // 09-23: saving no longer decodes. Like the official app, the glasses' Opus is only wrapped
                // in Ogg (hundreds of ms for 30 min, ~same size); a gap becomes silence, not a failed file.
                timeline.mark("package_started");savingStage("packaging");OggOpusWriter.Result packed=OggOpusWriter.wrap(new File(folder,"source.rawopus"),ranges,new File(folder,"recording.ogg"));timeline.mark("package_finished");
                main.post(()->{if(!current.equals(id)||!receiving)return;duration=packed.durationMs;silenceFilled=packed.silenceFilled;tailBytes=packed.tailBytes;audioFile="recording.ogg";timeline.mark("saved_state");savingStage="complete";phase="saved";receiving=false;submit(()->{try{manifest();main.post(this::publish);}catch(Exception e){main.post(()->fail("音频已保存，目录记录写入失败"));}});});
            }catch(Exception e){main.post(()->fail("文件未通过完整性检查或封装失败；原件保留"));}});},1000);
        }
    }
    private void mark(JSONObject request){
        if(!phase.equals("recording")||completed||closed||ackElapsed==0){markStatus="rejected_inactive";markAck(id,3);return;}
        if(markPending){markStatus="rejected_pending";markAck(id,2);return;}
        final String current=id;final RecordingMarkStore store=marks;
        final long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime()-ackElapsed;
        final int receivedBytes=bytes;
        Object raw=request.opt("time");final Long rawTime=(raw instanceof Integer||raw instanceof Long)?((Number)raw).longValue():null;
        markPending=true;
        try{io.execute(()->{
            int code;
            try{code=store.save(wall,elapsed,receivedBytes,rawTime);if(code==1)manifest();}
            catch(Exception e){code=3;}
            final int result=code;
            main.post(()->{
                if(!current.equals(id))return;
                markPending=false;
                if(closed||!receiving||!phase.equals("recording")||completed){markStatus="ack_skipped_after_stop";publish();return;}
                markStatus=result==1?"persisted_ack_submitted":result==2?"rejected_cooldown":"persistence_or_limit_failure";
                markAck(current,result);publish();
            });
        });}catch(RejectedExecutionException e){markPending=false;markStatus="queue_rejected";markAck(current,3);publish();}
    }
    private void markAck(String current,int code){
        try{host.send(11,new JSONObject().put("uuid",current).put("action",2).put("code",code),"mark-ack-"+UUID.randomUUID()+"-"+current);}
        catch(Exception e){markStatus="ack_send_failed";publish();}
    }
    void sendFailed(String command){if(id!=null&&command.startsWith("mark-ack-")&&command.endsWith(id)){markStatus="ack_send_failed";publish();return;}if(id!=null&&command.startsWith("rec-")&&command.endsWith(id))fail("录音控制发送失败；未确认眼镜状态");}
    void fail(String reason){
        if(phase.equals("failed"))return;
        boolean wasReceiving=receiving;if(timeline!=null)timeline.mark("failed");phase="failed";error=reason;receiving=false;closed=true;publish();
        if(wasReceiving)try{host.send(4,new JSONObject().put("uuid",id).put("action",1).put("code",2),"rec-emergency-stop-"+id);}catch(Exception ignored){}
        // Drain accepted blocks, then terminated() closes storage and attempts the final failed receipt.
        // This recorder is terminal. A new recording gets a new recorder/worker in the Activity.
        io.shutdown();
    }
    void close(){if(closed)return;closed=true;if(busy()){try{stop();}catch(Exception ignored){}fail("连接会话结束，已收到的原件保留");}io.shutdown();}
}
