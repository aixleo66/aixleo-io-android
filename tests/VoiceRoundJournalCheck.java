package dev.xr.rayneo.probe;

import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.TimeUnit;

final class VoiceRoundJournalCheck {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        File root=new File(args[0]);
        VoiceRoundJournal journal=new VoiceRoundJournal(root);
        journal.begin("session-A","round-A",100);
        journal.trigger("round-A","hardware_type1");journal.trigger("round-A","manual_diagnostic");
        journal.capturePolicy("round-A","streaming_server_vad");
        journal.mark("round-A","wake_received",100);
        journal.mark("round-A","capture_requested",110);
        journal.mark("round-A","first_audio_received",130);
        journal.mark("round-A","first_audio_received",170);
        journal.endpoint("round-A","vad_stopped",300);
        journal.endpoint("round-A","final_text_fallback",310);
        journal.mark("round-A","asr_final_text",320);
        journal.finish("round-A","cancelled_by_device",330);
        journal.mark("round-A","answer_end_send_completed",340);
        journal.finish("round-A","answer_sent",350);
        journal.listenerRearmed(360);
        JSONObject first=journal.snapshot();
        check(first.getString("status").equals("cancelled_by_device"));
        check(first.getJSONObject("duration_ms").getLong("capture_to_first_audio")==20);
        check(!first.getJSONObject("duration_ms").has("answer_request"));
        check(!first.getJSONObject("offset_ms").has("answer_end_send_completed"));
        check(first.getString("endpoint_reason").equals("vad_stopped"));
        check(first.getString("lens_observation").equals("not_collected"));
        check(first.getString("trigger").equals("hardware_type1"));
        check(first.getString("capture_policy").equals("streaming_server_vad"));
        journal.begin("session-A","round-B",400);
        journal.trigger("round-B","manual_diagnostic");
        journal.capturePolicy("round-B","fixed_eight_seconds");
        journal.mark("round-A","answer_returned",420);
        journal.mark("round-B","asr_ready",430);
        journal.mark("round-B","asr_no_input",8430);
        journal.finish("round-B","failed",8431);
        JSONObject second=journal.snapshot();
        check(!second.getJSONObject("offset_ms").has("answer_returned"));
        check(!second.getJSONObject("offset_ms").has("wake_received"));
        check(!second.getJSONObject("offset_ms").has("listener_rearmed"));
        check(second.getString("trigger").equals("manual_diagnostic"));
        check(second.getString("capture_policy").equals("fixed_eight_seconds"));
        journal.begin("../escape","bad",8500);check(journal.snapshot().getString("round_id").equals("round-B"));
        journal.begin("session-A","round-C",9000);
        journal.mark("round-C","answer_request_started",9010);
        journal.mark("round-C","answer_returned",9100);
        journal.mark("round-C","answer_end_send_completed",9200);
        journal.finish("round-C","answer_sent",9201);
        journal.listenerRearmed(9210);
        check(journal.snapshot().getJSONObject("duration_ms").getLong("answer_request")==90);
        check(journal.snapshot().getJSONObject("duration_ms").getLong("answer_sent_to_listener_rearmed")==10);
        check(!journal.snapshot().getJSONObject("offset_ms").has("device_exit_received"));
        journal.close();check(journal.awaitWrites(5,TimeUnit.SECONDS));
        check(journal.snapshot().getString("storage_error").equals(""));
        journal.begin("session-A","round-D",9300);check(journal.snapshot().getString("round_id").equals("round-C"));
        File blocked=new File(root,"blocked");try(FileOutputStream out=new FileOutputStream(blocked)){out.write(1);}
        VoiceRoundJournal failure=new VoiceRoundJournal(blocked);
        failure.begin("session-A","round-X",0);failure.finish("round-X","failed",1);
        failure.close();check(failure.awaitWrites(5,TimeUnit.SECONDS));
        check(!failure.snapshot().getString("storage_error").isEmpty());
        System.out.println("Voice round timing and frozen persistence passed");
    }
}
