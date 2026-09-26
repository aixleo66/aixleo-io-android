package dev.xr.rayneo.probe;
import org.json.JSONObject;

public final class LabAsrTrialCheck {
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    static LabAsrTrial wake(String id){
        LabAsrTrial t=new LabAsrTrial(id,"session",0);
        check(t.voice(1,10)==LabAsrTrial.START,"start once");
        t.sent(t.request(LabAsrTrial.START),true,20);return t;
    }
    public static void main(String[] args)throws Exception{
        LabAsrTrial t=new LabAsrTrial("normal","session",0);
        check(t.voice(8,1)==0&&t.ignoredPreWakeExits==1&&!t.capturing,"pre-wake exit");
        check(t.voice(11,2)==0&&!t.woke,"type11 not a new diagnostic wake");
        check(t.voice(1,10)==1&&t.capturing,"hardware wake");
        t.rememberWake(new JSONObject().put("rc",1).put("mode",JSONObject.NULL).put("linkType",3).put("ignored_secret","not retained"));
        t.rememberWake(new JSONObject().put("linkType",1));
        JSONObject wake=t.snapshot().getJSONObject("wake_context");
        check(wake.getJSONObject("fields").length()==3&&!wake.toString().contains("ignored_secret"),"wake whitelist");
        check(wake.getJSONObject("fields").getJSONObject("mode").optString("kind").equals("null"),"explicit null");
        check(wake.getJSONObject("fields").getJSONObject("linkType").optInt("value")==3,"first wake identity immutable");
        check(t.voice(1,11)==0&&t.voice(11,12)==0,"duplicate/continuation ignored");
        t.sent(t.request(1),true,13);t.audio(240,20);
        check(t.endpoint(100)==2&&!t.capturing,"endpoint stops microphone");
        check(t.endpoint(101)==0,"one stop");t.audio(240,102);check(t.packets==1,"late audio excluded");
        t.sent(t.request(2),true,105);
        check(t.finish("",true,new JSONObject().put("text","complete"),110)==12,"close and exit only");
        check(!t.done&&!t.passed(),"ASR success not exit confirmation");
        t.sent(t.request(4),true,115);
        check(t.done&&t.passed(),"all requested actions confirmed");
        check(t.snapshot().optString("lens_exit_observation").equals("not_collected"),"not lens pass");
        check(t.finish("late failure",false,null,120)==0&&t.passed(),"terminal immutable");

        LabAsrTrial stopFail=wake("stop-fail");stopFail.endpoint(30);
        check(stopFail.sent(stopFail.request(2),false,35)==12,"failed stop still closes and exits");
        stopFail.sent(stopFail.request(4),true,40);
        check(stopFail.done&&!stopFail.passed()&&stopFail.issue.equals("stop_send_failed"),"stop failure retained");
        LabAsrTrial exitFail=wake("exit-fail");exitFail.finish("",true,new JSONObject(),30);
        exitFail.sent(exitFail.request(2),true,31);exitFail.sent(exitFail.request(4),false,32);
        check(exitFail.asrSuccess&&!exitFail.passed()&&exitFail.issue.equals("exit_send_failed"),"ASR and exit separate");

        LabAsrTrial cancel=wake("cancel");
        check(cancel.voice(8,30)==14,"device cancel stops exits closes");
        check(cancel.finish("",true,new JSONObject(),31)==0&&!cancel.asrSuccess,"late success cannot replace cancellation");
        cancel.sent(cancel.request(2),true,32);cancel.sent(cancel.request(4),true,33);
        check(cancel.done&&!cancel.passed(),"cancel terminal failed");
        LabAsrTrial waiting=new LabAsrTrial("waiting","session",0);
        waiting.rememberWake(new JSONObject().put("linkType",1));
        check(waiting.snapshot().opt("wake_context")==JSONObject.NULL,"no context before accepted wake");
        check(waiting.tick(89999)==0&&waiting.tick(90000)==8&&!waiting.woke&&waiting.done,"wait timeout no device writes");
        LabAsrTrial noInput=wake("no-input");
        check(noInput.finish("no_input",false,null,8000)==14,"no input cleanup");
        noInput.tick(13000);check(noInput.done&&!noInput.passed()&&noInput.issue.equals("no_input"),"failure kept past cleanup timeout");
        LabAsrTrial timeout=wake("timeout");check(timeout.tick(35010)==14,"recognition deadline");
        timeout.interrupted(35011);check(timeout.done&&!timeout.passed(),"session shutdown terminal");

        LabAsrTrial startFail=new LabAsrTrial("start-fail","session",0);startFail.voice(1,1);
        check(startFail.sent(startFail.request(1),false,2)==14,"start error cleanup");
        LabAsrTrial fresh=wake("new");fresh.sent(t.request(2),false,50);
        check(fresh.stopSent==null&&fresh.issue.isEmpty(),"old callback ownership");
        check(new LabAsrTrial("default","session",0).snapshot().optInt("requested_silence_duration_ms")==700,"default receipt");
        for(int silence:new int[]{700,1500}){
            LabAsrTrial configured=new LabAsrTrial("configured","session",0,silence);
            check(configured.snapshot().optInt("requested_silence_duration_ms")==silence,"armed parameter");
            configured.voice(1,1);configured.sent(configured.request(1),true,2);configured.endpoint(3);
            configured.sent(configured.request(2),true,4);configured.finish("",true,new JSONObject(),5);
            configured.sent(configured.request(4),true,6);
            check(configured.passed()&&configured.snapshot().optInt("requested_silence_duration_ms")==silence,"success parameter");
            LabAsrTrial cancelled=new LabAsrTrial("cancelled","session",0,silence);
            cancelled.finish("cancelled_by_host",false,null,1);
            check(cancelled.done&&!cancelled.passed()&&cancelled.snapshot().optInt("requested_silence_duration_ms")==silence,"cancel parameter");
            LabAsrTrial timed=new LabAsrTrial("timed","session",0,silence);timed.tick(90000);
            check(timed.done&&timed.snapshot().optInt("requested_silence_duration_ms")==silence,"timeout parameter");
        }
        LabAsrTrial multi=new LabAsrTrial("multi","session",0,700,8000);
        check(multi.snapshot().optInt("capture_window_ms")==8000
            &&multi.snapshot().optString("capture_window_origin").equals("service_ready"),"bounded window receipt");
        multi.voice(1,1);multi.sent(multi.request(1),true,2);
        check(multi.voice(8,3)==14,"multi cancel cleanup");
        check(multi.finish("",true,new JSONObject(),4)==0&&!multi.asrSuccess,"multi late final cannot override cancel");
        try{new LabAsrTrial("invalid-window","session",0,700,1);throw new AssertionError("invalid window allowed");}
        catch(IllegalArgumentException expected){}
        LabAsrTrial grace=new LabAsrTrial("grace","session",0,700,0,2000);
        check(grace.snapshot().optInt("continuation_grace_ms")==2000
            &&grace.snapshot().optString("capture_policy").equals("multi_segment_continuation_grace_2000ms"),"grace receipt");
        grace.voice(1,1);grace.rememberWake(new JSONObject().put("mode","1").put("linkType",1.5));
        JSONObject fields=grace.snapshot().getJSONObject("wake_context").getJSONObject("fields");
        check(fields.getJSONObject("rc").optString("kind").equals("missing"),"missing is not zero");
        check(fields.getJSONObject("mode").optString("kind").equals("string"),"string is not integer");
        check(fields.getJSONObject("linkType").optString("kind").equals("number"),"fraction is not integer");
        try{new LabAsrTrial("invalid","session",0,0);throw new AssertionError("invalid duration allowed");}
        catch(IllegalArgumentException expected){}
        System.out.println("ASR lifecycle checks passed");
    }
}
