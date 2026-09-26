package dev.xr.rayneo.probe;

import org.json.*;

/** SDK Lab single-round adapter. round=-1 is an explicit compatibility experiment. */
final class NativeAnswerRound {
    final String command,session,user,dialog;
    final int linkType;
    private boolean terminal;
    NativeAnswerRound(String command,String session,String user,String dialog,JSONObject wake){
        Object value=wake.opt("linkType");
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1||((Number)value).longValue()>3)
            throw new IllegalArgumentException("Wake linkType is not a confirmed 1.0.2 mapping");
        this.command=command;this.session=session;this.user=user;this.dialog=dialog;linkType=((Number)value).intValue();
    }
    boolean requiresFinish(){return linkType==3;}
    boolean same(String command,String session){return this.command.equals(command)&&this.session.equals(session);}
    boolean terminal(){return terminal;}
    boolean finishOnce(){if(terminal)return false;terminal=true;return true;}
    String request(String action){return "lab-native-"+action+"-"+command;}
    boolean owns(String id){return id.equals(request("stop"))||id.equals(request("finish"))||id.equals(request("exit"))
        ||id.startsWith(request("answer")+"-");}
    JSONObject payload(String text,String query,boolean last,long epochMs)throws Exception{
        if(text.isEmpty())throw new IllegalArgumentException("Empty native answer chunk");
        return new JSONObject().put("sub","workflow").put("vendor","deepseek").put("uuid",user).put("sid",dialog)
            .put("round",-1).put("timestamp",epochMs/1000).put("query",query).put("domain","chat").put("intent","chat")
            .put("payload",new JSONObject()).put("offline",false)
            .put("answer",new JSONObject().put("text",text).put("isFinal",last));
    }
    JSONObject metadata()throws Exception{
        return new JSONObject().put("command_id",command).put("session_id",session).put("uuid",user).put("sid",dialog)
            .put("raw_link_type",linkType).put("link_type",linkType==1?"ALI":linkType==2?"RAYNEO":"RAYCLAW")
            .put("round",-1).put("round_source","nlp_model_default_experimental")
            .put("identity_scope","self_owned_lab_activity_session").put("timestamp_unit","seconds")
            .put("finish_type12_required",requiresFinish()).put("lens_verified",false);
    }
}
