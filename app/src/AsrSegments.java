package dev.xr.rayneo.probe;

import java.util.*;
import org.json.*;

/** Per-socket ASR items; a sentence final is not a whole-round final. */
final class AsrSegments {
    private static final class Item {
        final String id; final int order;
        String previous="", text="";
        long start=-1, end=-1;
        boolean complete;
        Item(String id,int order){this.id=id;this.order=order;}
    }
    private final LinkedHashMap<String,Item> items=new LinkedHashMap<>();
    private final JSONArray events=new JSONArray();
    private Item item(String id){
        if(id.isEmpty()||id.length()>128)throw new IllegalArgumentException("Missing or invalid ASR item identity");
        Item value=items.get(id);
        if(value==null){
            if(items.size()>=16)throw new IllegalStateException("ASR segment limit");
            value=new Item(id,items.size());items.put(id,value);
        }
        return value;
    }
    boolean accept(JSONObject event,long elapsed,int decoded,long sent,int queued)throws Exception{
        String type=event.optString("type");
        if(!(type.equals("conversation.item.created")||type.equals("input_audio_buffer.speech_started")
            ||type.equals("input_audio_buffer.speech_stopped")||type.startsWith("conversation.item.input_audio_transcription.")))return false;
        JSONObject body=event.optJSONObject("item");
        Item value=item(type.equals("conversation.item.created")&&body!=null?body.optString("id"):event.optString("item_id"));
        boolean partialChanged=false;
        if(type.equals("conversation.item.created")){
            Object previous=event.opt("previous_item_id");
            if(previous instanceof String)value.previous=(String)previous;
        }else if(type.equals("input_audio_buffer.speech_started")){
            Object start=event.opt("audio_start_ms");if(start instanceof Number)value.start=((Number)start).longValue();
        }else if(type.equals("input_audio_buffer.speech_stopped")){
            Object end=event.opt("audio_end_ms");if(end instanceof Number)value.end=((Number)end).longValue();
        }else if(type.equals("conversation.item.input_audio_transcription.text")){
            if(!value.complete){
                String next=event.optString("text")+event.optString("stash");
                partialChanged=!value.text.equals(next);value.text=next;
            }
        }else if(type.equals("conversation.item.input_audio_transcription.completed")){
            String text=event.optString("transcript").trim();
            if(value.complete&&!value.text.equals(text))throw new IllegalStateException("Conflicting ASR item final");
            value.text=text;value.complete=true;
        }
        if(value.text.length()>8192)throw new IllegalStateException("ASR text limit");
        if(!type.endsWith(".text")&&events.length()<96)events.put(new JSONObject().put("type",type)
            .put("item_id",value.id).put("elapsed_ms",elapsed).put("audio_start_ms",value.start<0?JSONObject.NULL:value.start)
            .put("audio_end_ms",value.end<0?JSONObject.NULL:value.end).put("decoded_packets",decoded)
            .put("uploaded_pcm_samples",sent).put("queued_packets",queued));
        return partialChanged;
    }
    private void visit(Item item,List<Item> ordered,Set<String> visiting,Set<String> done){
        if(done.contains(item.id))return;
        if(!visiting.add(item.id))throw new IllegalStateException("ASR item ordering cycle");
        if(!item.previous.isEmpty()){
            Item previous=items.get(item.previous);
            if(previous==null)throw new IllegalStateException("Missing previous ASR item");
            visit(previous,ordered,visiting,done);
        }
        visiting.remove(item.id);done.add(item.id);ordered.add(item);
    }
    private List<Item> ordered(){
        List<Item> candidates=new ArrayList<>(items.values()),result=new ArrayList<>();
        candidates.sort(Comparator.comparingLong((Item x)->x.start<0?Long.MAX_VALUE:x.start).thenComparingInt(x->x.order));
        Set<String> visiting=new HashSet<>(),done=new HashSet<>();
        for(Item value:candidates)visit(value,result,visiting,done);
        return result;
    }
    String finalText(){
        StringBuilder text=new StringBuilder();
        for(Item value:ordered()){
            if(!value.complete)throw new IllegalStateException("ASR item not completed");
            if(!value.text.isEmpty()){if(text.length()>0)text.append(' ');text.append(value.text);}
        }
        if(text.length()==0)throw new IllegalStateException("No final ASR text");
        return text.toString();
    }
    JSONArray snapshot()throws Exception{
        JSONArray result=new JSONArray();
        for(Item value:ordered())result.put(new JSONObject().put("item_id",value.id).put("previous_item_id",value.previous)
            .put("text",value.text).put("completed",value.complete).put("audio_start_ms",value.start<0?JSONObject.NULL:value.start)
            .put("audio_end_ms",value.end<0?JSONObject.NULL:value.end));
        return result;
    }
    JSONArray events(){return events;}
    static JSONObject echoedSettings(JSONObject event)throws Exception{
        JSONObject source=event.optJSONObject("session"),out=new JSONObject();
        for(String key:new String[]{"model","input_audio_format","sample_rate"}){
            Object value=source==null?null:source.opt(key);
            out.put(key,value instanceof String||value instanceof Number?value:JSONObject.NULL);
        }
        JSONObject vad=source==null?null:source.optJSONObject("turn_detection"),selected=new JSONObject();
        for(String key:new String[]{"type","threshold","silence_duration_ms"}){
            Object value=vad==null?null:vad.opt(key);
            selected.put(key,value instanceof String||value instanceof Number?value:JSONObject.NULL);
        }
        return out.put("turn_detection",selected);
    }
}
