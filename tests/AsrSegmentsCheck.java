package dev.xr.rayneo.probe;
import org.json.*;
public final class AsrSegmentsCheck {
    static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
    static JSONObject event(String type,String id){return new JSONObject().put("type",type).put("item_id",id);}
    static void accept(AsrSegments s,JSONObject e)throws Exception{s.accept(e,10,3,100,1);}
    public static void main(String[] args)throws Exception{
        AsrSegments s=new AsrSegments();
        accept(s,event("input_audio_buffer.speech_started","a").put("audio_start_ms",0));
        accept(s,event("conversation.item.input_audio_transcription.text","a").put("text","today").put("stash"," aft"));
        accept(s,event("conversation.item.input_audio_transcription.text","a").put("text","today afternoon"));
        accept(s,event("input_audio_buffer.speech_stopped","a").put("audio_end_ms",1000));
        accept(s,event("input_audio_buffer.speech_started","b").put("audio_start_ms",2000));
        JSONObject second=event("conversation.item.input_audio_transcription.completed","b").put("transcript","go walking");
        accept(s,second);accept(s,second);
        try{s.finalText();throw new AssertionError("partial cannot pass");}catch(IllegalStateException expected){}
        accept(s,event("conversation.item.input_audio_transcription.completed","a").put("transcript","today afternoon"));
        check(s.finalText().equals("today afternoon go walking"),"order and duplicate final");
        check(!s.accept(event("conversation.item.input_audio_transcription.text","a").put("text","late partial"),10,3,100,1),
            "late partial for completed item is rejected");
        check(s.finalText().equals("today afternoon go walking"),"late partial cannot overwrite final");
        check(s.snapshot().length()==2,"two items");
        check(s.events().getJSONObject(0).optLong("uploaded_pcm_samples")==100,"actual counters retained");
        JSONObject absent=AsrSegments.echoedSettings(new JSONObject());
        check(absent.getJSONObject("turn_detection").opt("silence_duration_ms")==JSONObject.NULL,"missing echo stays unknown");
        JSONObject echo=AsrSegments.echoedSettings(new JSONObject().put("session",new JSONObject()
            .put("secret","never-copy").put("sample_rate",16000).put("turn_detection",new JSONObject()
            .put("type","server_vad").put("silence_duration_ms",700).put("threshold",0.0))));
        check(!echo.has("secret")&&echo.getJSONObject("turn_detection").optInt("silence_duration_ms")==700,"whitelist actual echo");
        AsrSegments previous=new AsrSegments();
        accept(previous,new JSONObject().put("type","conversation.item.created").put("item",new JSONObject().put("id","b")).put("previous_item_id","a"));
        accept(previous,event("conversation.item.input_audio_transcription.completed","b").put("transcript","B"));
        accept(previous,event("conversation.item.input_audio_transcription.completed","a").put("transcript","A"));
        check(previous.finalText().equals("A B"),"previous item order");
    }
}
