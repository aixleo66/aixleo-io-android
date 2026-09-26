package dev.xr.rayneo.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** One passive hardware wake -> cloud ASR -> explicit stop/exit. No answer/display/config writes. */
final class LabAsrTrial {
    static final int NONE=0, START=1, STOP=2, EXIT=4, CLOSE=8;
    final String id, session;
    final long created;
    final int requestedSilenceDurationMs;
    final int captureWindowMs;
    final int continuationGraceMs;
    String phase="awaiting_wakeup", issue="", endpointReason="not_received";
    volatile boolean capturing, done;
    boolean woke, stopRequested, exitRequested, asrSuccess;
    Boolean startSent, stopSent, exitSent;
    int packets, bytes, ignoredPreWakeExits, ignoredWakeEvents;
    long deadline;
    JSONObject asr;
    private JSONObject wakeContext;
    final Map<String,Long> marks=new LinkedHashMap<>();

    LabAsrTrial(String id,String session,long now){
        this(id,session,now,700);
    }
    LabAsrTrial(String id,String session,long now,int silenceDurationMs){
        this(id,session,now,silenceDurationMs,0);
    }
    LabAsrTrial(String id,String session,long now,int silenceDurationMs,int captureWindowMs){
        this(id,session,now,silenceDurationMs,captureWindowMs,0);
    }
    LabAsrTrial(String id,String session,long now,int silenceDurationMs,int captureWindowMs,int continuationGraceMs){
        if(silenceDurationMs!=700&&silenceDurationMs!=1500)throw new IllegalArgumentException("Unsupported ASR silence duration");
        if(captureWindowMs!=0&&captureWindowMs!=8000)throw new IllegalArgumentException("Unsupported ASR capture window");
        if((continuationGraceMs!=0&&continuationGraceMs!=2000)||(continuationGraceMs!=0&&captureWindowMs!=0))
            throw new IllegalArgumentException("Unsupported ASR continuation policy");
        this.continuationGraceMs=continuationGraceMs;
        this.captureWindowMs=captureWindowMs;
        requestedSilenceDurationMs=silenceDurationMs;
        this.id=id;this.session=session;created=now;deadline=now+90000;
        mark("armed",now);
    }
    void mark(String name,long now){if(!done&&!marks.containsKey(name)&&marks.size()<40)marks.put(name,now-created);}
    boolean owns(String request){return request.equals(request(START))||request.equals(request(STOP))||request.equals(request(EXIT));}
    String request(int action){return "lab-asr-"+action+"-"+id;}
    void rememberWake(JSONObject body)throws Exception{
        if(!woke||wakeContext!=null)return;
        JSONObject fields=new JSONObject();
        for(String key:new String[]{"rc","mode","linkType"}){
            Object value=body==null?null:body.opt(key);
            String kind=body==null?"unparsed":!body.has(key)?"missing":value==JSONObject.NULL||value==null?"null"
                :value instanceof Integer||value instanceof Long?"integer":value instanceof Number?"number"
                :value instanceof String?"string":value instanceof Boolean?"boolean"
                :value instanceof JSONObject?"object":value instanceof org.json.JSONArray?"array":"unknown";
            fields.put(key,new JSONObject().put("kind",kind).put("value",kind.equals("integer")?value:JSONObject.NULL));
        }
        wakeContext=new JSONObject().put("parse_status",body==null?"invalid_json":"parsed")
            .put("wire_type",1).put("command_id",id).put("session_id",session).put("fields",fields)
            .put("link_type_mapping","not_applied");
    }
    int voice(int type,long now){
        if(done||phase.equals("cleanup"))return NONE;
        if(phase.equals("awaiting_wakeup")){
            if(type==8){ignoredPreWakeExits++;return NONE;}
            if(type!=1){if(type==11)ignoredWakeEvents++;return NONE;}
            woke=true;capturing=true;phase="recognizing";deadline=now+35000;
            mark("wake_received",now);mark("capture_requested",now);return START;
        }
        if(type==8)return finish("cancelled_by_device",false,null,now);
        if(type==1||type==11)ignoredWakeEvents++;
        return NONE;
    }
    void audio(int count,long now){
        if(done||!capturing)return;
        packets++;bytes+=count;mark("first_audio_received",now);
    }
    int endpoint(long now){
        if(done||phase.equals("cleanup")||!woke)return NONE;
        phase="finalizing";capturing=false;mark("asr_endpoint",now);
        if(stopRequested)return NONE;
        stopRequested=true;mark("stop_requested",now);return STOP;
    }
    int finish(String reason,boolean success,JSONObject value,long now){
        if(done||phase.equals("cleanup"))return NONE;
        if(!reason.isEmpty())issue=reason;
        asrSuccess=success;asr=value;capturing=false;phase="cleanup";deadline=now+5000;
        mark(success?"asr_completed":"asr_failed_or_cancelled",now);
        int actions=CLOSE;
        if(!woke){done=true;phase="finished";return actions;}
        if(!stopRequested){stopRequested=true;mark("stop_requested",now);actions|=STOP;}
        exitRequested=true;mark("exit_requested",now);actions|=EXIT;
        return actions;
    }
    int sent(String request,boolean success,long now){
        if(done||!owns(request))return NONE;
        if(request.equals(request(START))&&startSent==null){
            startSent=success;mark("start_send_callback",now);
            if(!success&&!phase.equals("cleanup"))return finish("start_send_failed",false,null,now);
        }else if(request.equals(request(STOP))&&stopRequested&&stopSent==null){
            stopSent=success;mark("stop_send_callback",now);
            if(!success&&issue.isEmpty())issue="stop_send_failed";
            if(!success&&!phase.equals("cleanup"))return finish(issue,false,null,now);
        }else if(request.equals(request(EXIT))&&exitRequested&&exitSent==null){
            exitSent=success;mark("exit_send_callback",now);
            if(!success&&issue.isEmpty())issue="exit_send_failed";
        }
        if(phase.equals("cleanup")&&startSent!=null&&stopSent!=null&&exitSent!=null){
            mark("terminal",now);done=true;phase="finished";
        }
        return NONE;
    }
    int tick(long now){
        if(done||now<deadline)return NONE;
        if(phase.equals("cleanup")){
            if(issue.isEmpty())issue="cleanup_callback_timeout";
            mark("terminal",now);done=true;phase="finished";return CLOSE;
        }
        return finish(woke?"recognition_timeout":"wakeup_timeout",false,null,now);
    }
    void interrupted(long now){
        if(done)return;
        if(issue.isEmpty())issue="session_ended_before_cleanup_confirmation";
        mark("terminal",now);capturing=false;done=true;phase="finished";
    }
    boolean passed(){return done&&asrSuccess&&issue.isEmpty()&&Boolean.TRUE.equals(startSent)
        &&Boolean.TRUE.equals(stopSent)&&Boolean.TRUE.equals(exitSent);}
    JSONObject snapshot()throws Exception{
        JSONObject times=new JSONObject();for(Map.Entry<String,Long> e:marks.entrySet())times.put(e.getKey(),e.getValue());
        return new JSONObject().put("command_id",id).put("session_id",session).put("phase",phase).put("done",done)
            .put("status",done?(passed()?"completed":"failed"):"pending").put("issue",issue)
            .put("wake_received",woke).put("capture_requested_active",capturing).put("asr_success",asrSuccess)
            .put("wake_context",wakeContext==null?JSONObject.NULL:wakeContext)
            .put("start_send_completed",startSent==null?JSONObject.NULL:startSent)
            .put("stop_send_completed",stopSent==null?JSONObject.NULL:stopSent)
            .put("exit_send_completed",exitSent==null?JSONObject.NULL:exitSent)
            .put("packets",packets).put("bytes",bytes).put("ignored_pre_wake_exits",ignoredPreWakeExits)
            .put("ignored_wake_events",ignoredWakeEvents).put("offset_ms",times)
            .put("asr",asr==null?JSONObject.NULL:asr).put("audio_saved",false).put("answer_requested",false)
            .put("display_requested",false).put("lens_exit_observation","not_collected")
            .put("requested_silence_duration_ms",requestedSilenceDurationMs)
            .put("capture_window_ms",captureWindowMs).put("capture_window_origin",captureWindowMs==0?"not_applicable":"service_ready")
            .put("continuation_grace_ms",continuationGraceMs)
            .put("capture_policy",continuationGraceMs!=0?"multi_segment_continuation_grace_2000ms":captureWindowMs==0?"streaming_server_vad_"+requestedSilenceDurationMs+"ms":"bounded_multi_segment_8000ms")
            .put("endpoint_reason",endpointReason);
    }
}
