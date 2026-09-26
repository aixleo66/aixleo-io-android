package org.java_websocket.client;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
public abstract class WebSocketClient {
    public static int appends,finishes;
    public static boolean sentFirstFinal,emitLatePartial;
    public WebSocketClient(URI uri,Draft_6455 draft,Map<String,String> headers,int timeout){}
    public abstract void onOpen(ServerHandshake h);
    public abstract void onMessage(String m);
    public abstract void onClose(int code,String reason,boolean remote);
    public abstract void onError(Exception e);
    public boolean connectBlocking(long n,TimeUnit u){onOpen(null);return true;}
    public void setConnectionLostTimeout(int n){}
    public void closeConnection(int code,String reason){}
    public WebSocketClient getConnection(){return this;}
    public boolean hasBufferedData(){return false;}
    public void send(String payload){
        // The shared JSON fixture only parses flat input. Read the outer event type,
        // not the nested server_vad type inside session.update.
        java.util.regex.Matcher kind=java.util.regex.Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        String type=kind.find()?kind.group(1):"";
        if(type.equals("session.update")){onMessage("{\"type\":\"session.updated\"}");return;}
        if(type.equals("input_audio_buffer.append")){
            if(finishes!=0)throw new AssertionError("append after finish");
            appends++;
            if(appends<=2){
                String id=appends==1?"a":"b",text=appends==1?"today afternoon":"go walking";
                onMessage(new JSONObject().put("type","input_audio_buffer.speech_started").put("item_id",id).put("audio_start_ms",appends*1000).toString());
                onMessage(new JSONObject().put("type","input_audio_buffer.speech_stopped").put("item_id",id).put("audio_end_ms",appends*1000+500).toString());
                onMessage(new JSONObject().put("type","conversation.item.input_audio_transcription.completed").put("item_id",id).put("transcript",text).toString());
                if(appends==1&&emitLatePartial)
                    onMessage(new JSONObject().put("type","conversation.item.input_audio_transcription.text").put("item_id",id).put("text","late partial").toString());
                if(appends==1)sentFirstFinal=true;
            }
        }else if(type.equals("session.finish")){
            if(++finishes!=1)throw new AssertionError("duplicate finish");
            int expected=4+(android.media.MediaCodec.eosHasPcm?1:0);
            if(appends!=expected)throw new AssertionError("finish before final PCM append: "+appends);
            onMessage("{\"type\":\"session.finished\"}");
        }
    }
}
