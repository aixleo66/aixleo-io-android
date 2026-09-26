package dev.xr.rayneo.probe;

import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Diagnostic sidecar. Never owns audio, commands, or the UI state machine. */
final class VoiceRoundJournal implements AutoCloseable {
    private final File root;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-round-receipts"); t.setDaemon(true); return t;
    });
    private final Map<String, Long> points = new LinkedHashMap<>();
    private String session, round, status="active", endpointReason="unknown", capturePolicy="unknown", trigger="unknown";
    private long origin;
    private boolean terminal, closed;
    private volatile String storageError="";

    VoiceRoundJournal(File root) { this.root=root; }
    private static boolean valid(String id) { return id!=null&&id.matches("[A-Za-z0-9_-]{1,128}"); }

    synchronized void begin(String session, String round, long now) {
        if(closed||!valid(session)||!valid(round))return;
        if(round.equals(this.round)&&session.equals(this.session))return;
        if(this.round!=null&&!terminal)finish(this.round,"superseded",now);
        this.session=session;this.round=round;origin=now;status="active";terminal=false;
        endpointReason="unknown";capturePolicy="unknown";trigger="unknown";points.clear();points.put("round_started",0L);persist();
    }
    synchronized void mark(String id,String name,long now) {
        if(closed||terminal||round==null||!round.equals(id)||now<origin||points.containsKey(name))return;
        points.put(name,now-origin);persist();
    }
    synchronized void trigger(String id,String value) {
        if(closed||terminal||round==null||!round.equals(id)||!trigger.equals("unknown"))return;
        if(!value.equals("hardware_type1")&&!value.equals("continuation_type11")&&!value.equals("manual_diagnostic"))return;
        trigger=value;persist();
    }
    synchronized void capturePolicy(String id,String policy) {
        if(closed||terminal||round==null||!round.equals(id))return;
        if(!policy.equals("fixed_eight_seconds")&&!policy.equals("streaming_server_vad")&&!policy.equals("streaming_continuation_grace_2000ms"))return;
        capturePolicy=policy;persist();
    }
    synchronized void endpoint(String id,String reason,long now) {
        if(closed||terminal||round==null||!round.equals(id)||now<origin||points.containsKey("asr_endpoint"))return;
        endpointReason=reason;points.put("asr_endpoint",now-origin);persist();
    }
    synchronized void finish(String id,String outcome,long now) {
        if(closed||terminal||round==null||!round.equals(id))return;
        status=outcome;terminal=true;points.put("round_terminal",Math.max(0,now-origin));persist();
    }
    synchronized void listenerRearmed(long now) {
        // Explicit transition after a round; this is not evidence that its page exited.
        if(closed||round==null||now<origin||points.containsKey("listener_rearmed"))return;
        points.put("listener_rearmed",now-origin);persist();
    }
    private void duration(JSONObject out,String label,String first,String last)throws Exception {
        Long a=points.get(first),b=points.get(last);if(a!=null&&b!=null&&b>=a)out.put(label,b-a);
    }
    synchronized JSONObject snapshot() {
        JSONObject out=new JSONObject(),offsets=new JSONObject(),durations=new JSONObject();
        try {
            if(round==null)return out;
            for(Map.Entry<String,Long> e:points.entrySet())offsets.put(e.getKey(),e.getValue());
            duration(durations,"wake_to_capture_request","wake_received","capture_requested");
            duration(durations,"capture_to_first_audio","capture_requested","first_audio_received");
            duration(durations,"capture_to_asr_ready","capture_requested","asr_ready");
            duration(durations,"endpoint_to_final_text","asr_endpoint","asr_final_text");
            duration(durations,"answer_request","answer_request_started","answer_returned");
            duration(durations,"wake_to_answer_sent","wake_received","answer_end_send_completed");
            duration(durations,"answer_sent_to_listener_rearmed","answer_end_send_completed","listener_rearmed");
            out.put("schema_version",1).put("session_id",session).put("round_id",round)
                .put("status",status).put("terminal",terminal).put("clock","elapsed_realtime")
                .put("offset_ms",offsets).put("duration_ms",durations).put("endpoint_reason",endpointReason)
                .put("trigger",trigger).put("capture_policy",capturePolicy).put("lens_observation","not_collected").put("storage_error",storageError);
        } catch(Exception ignored) { }
        return out;
    }
    private void persist() {
        final String data=snapshot().toString(),name=session+"--"+round+".json";
        try { writer.execute(() -> {
            try {
                if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Directory unavailable");
                File tmp=new File(root,name+".tmp");
                try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data.getBytes("UTF-8"));out.getFD().sync();}
                if(!tmp.renameTo(new File(root,name)))throw new IOException("Receipt replacement failed");
            } catch(Exception e){storageError=e.getClass().getSimpleName();}
        }); } catch(RejectedExecutionException e){storageError="Writer unavailable";}
    }
    public synchronized void close(){if(closed)return;closed=true;writer.shutdown();}
    boolean awaitWrites(long timeout,TimeUnit unit)throws InterruptedException{return writer.awaitTermination(timeout,unit);}
}
