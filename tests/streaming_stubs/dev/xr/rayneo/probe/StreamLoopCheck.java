package dev.xr.rayneo.probe;
import org.json.*;
import java.util.concurrent.*;
import org.java_websocket.client.WebSocketClient;
public final class StreamLoopCheck {
    public static void main(String[] args)throws Exception{
        android.media.MediaCodec.eosHasPcm=args[0].equals("pcm-eos");
        WebSocketClient.emitLatePartial=args[0].equals("late-partial");
        boolean grace=args[0].startsWith("grace"),noAck=args[0].equals("grace-no-ack");
        StreamingAsr[] holder=new StreamingAsr[1];
        CountDownLatch done=new CountDownLatch(1);String[] failure={null};JSONObject[] result={null};int[] endpoint={0},finals={0},partials={0};
        StreamingAsr stream=new StreamingAsr(new JSONObject().put("dashscope_key","fixture"),new StreamingAsr.Listener(){
            public void event(String n,Object v){}
            public void text(String v,boolean last){if(last)finals[0]++;else{partials[0]++;if(v.equals("late partial"))failure[0]="completed item was redrawn by late partial";}}
            public void endpoint(){
                if(!WebSocketClient.sentFirstFinal)failure[0]="no first sentence before cutoff";
                endpoint[0]++;
                if(grace&&!noAck)holder[0].acknowledgeCaptureStop(true);
            }
            public void completed(JSONObject value){result[0]=value;done.countDown();}
            public void failed(String reason,boolean uploaded){failure[0]=reason;done.countDown();}
        },700,grace?0:8000,grace?2000:0);
        holder[0]=stream;
        for(int n=0;n<4;n++)stream.offer(new byte[]{1,2,3});
        stream.start();
        if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("stream did not terminate");
        if(noAck){
            if(failure[0]==null||!failure[0].contains("confirmation timed out")||result[0]!=null||WebSocketClient.finishes!=0)
                throw new AssertionError("missing stop confirmation must fail without finish");
            stream.close();return;
        }
        if(failure[0]!=null)throw new AssertionError(failure[0]);
        if(result[0]==null||!result[0].optString("text").equals("today afternoon go walking"))throw new AssertionError("missing sentence");
        if(endpoint[0]!=1||finals[0]!=1||!result[0].optBoolean("decoder_eos"))throw new AssertionError("terminal contract");
        if(args[0].equals("late-partial")&&partials[0]!=0)throw new AssertionError("late partial must be suppressed");
        if(result[0].optInt("queued_packets_at_finish")!=0||result[0].optInt("opus_packets")!=4)throw new AssertionError("not drained");
        if(WebSocketClient.finishes!=1)throw new AssertionError("finish count");
        if(grace){
            if(!result[0].optString("endpoint_reason").equals("continuation_grace_expired")
                ||!result[0].optBoolean("capture_stop_send_confirmed_before_finish"))throw new AssertionError("grace stop contract");
            if(!result[0].getJSONArray("continuation_events").toString().contains("cancelled_by_speech"))throw new AssertionError("continuation must cancel first timer");
        }
        stream.close();
    }
}
final class CloudClient {
    static final class Failure extends Exception {Failure(String value){super(value);}}
    static java.net.URI endpoint(String url,String provider)throws Exception{return new java.net.URI(url);}
}
final class OpusAudio {
    static android.media.MediaFormat format(){return new android.media.MediaFormat();}
    static long samples48k(byte[] b){return 2880;}
}
