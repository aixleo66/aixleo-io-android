package dev.xr.rayneo.probe;

import org.json.*;

/** A bounded, normal-answer-channel preface used only before Lab knowledge queries. */
final class KnowledgeWaitingPreface {
    static final String TEXT="我正在查找相关资料。";
    private KnowledgeWaitingPreface(){}
    static boolean eligible(boolean readingTrial,String provider,NativeAnswerRound round){
        return readingTrial&&"knowledge".equals(provider)&&round!=null;
    }
    static String request(NativeAnswerRound round){return round.request("answer")+"-preface";}
    static JSONObject receipt(String command)throws Exception{
        return new JSONObject().put("command_id",command).put("text",TEXT).put("status","pending")
            .put("official_reference","normal_answer_preface_not_type10");
    }
    static JSONObject payload(NativeAnswerRound round,String query,long now)throws Exception{
        return round.payload(TEXT,query,false,now);
    }
    static void callback(JSONObject receipt,boolean success)throws Exception{
        if(receipt==null)return;
        if("pending".equals(receipt.optString("status")))
            receipt.put("status",success?"sent":"failed").put("send_completed",success);
        else receipt.put("late_callback",success?"sent":"failed");
    }
    static void timeout(JSONObject receipt)throws Exception{
        if(receipt!=null&&"pending".equals(receipt.optString("status")))
            receipt.put("status","callback_timeout").put("send_completed",false);
    }
}
