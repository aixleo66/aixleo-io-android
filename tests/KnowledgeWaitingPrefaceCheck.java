package dev.xr.rayneo.probe;

import org.json.*;

public final class KnowledgeWaitingPrefaceCheck {
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        NativeAnswerRound round=new NativeAnswerRound("command","session","user","dialog",new JSONObject().put("linkType",3));
        check(KnowledgeWaitingPreface.eligible(true,"knowledge",round),"Lab knowledge query is eligible");
        check(!KnowledgeWaitingPreface.eligible(false,"knowledge",round),"ordinary mode is unchanged");
        check(!KnowledgeWaitingPreface.eligible(true,"deepseek",round),"default provider is unchanged");
        check(!KnowledgeWaitingPreface.eligible(true,"knowledge",null),"missing native round is unchanged");
        String request=KnowledgeWaitingPreface.request(round);
        check(request.equals("lab-native-answer-command-preface")&&round.owns(request),"preface callback belongs to current round");
        JSONObject payload=KnowledgeWaitingPreface.payload(round,"知识库检索，测试",123456L);
        check(payload.getJSONObject("answer").getString("text").equals(KnowledgeWaitingPreface.TEXT),"fixed short preface");
        check(!payload.getJSONObject("answer").optBoolean("isFinal"),"preface never closes answer stream");
        check(payload.getString("sid").equals("dialog")&&payload.getString("uuid").equals("user"),"identity is preserved");
        JSONObject receipt=KnowledgeWaitingPreface.receipt("command");
        KnowledgeWaitingPreface.callback(receipt,true);
        check(receipt.optBoolean("send_completed")&&receipt.optString("status").equals("sent"),"successful send is explicit");
        receipt=KnowledgeWaitingPreface.receipt("command");KnowledgeWaitingPreface.callback(receipt,false);
        check(!receipt.optBoolean("send_completed")&&receipt.optString("status").equals("failed"),"failed send is explicit");
        receipt=KnowledgeWaitingPreface.receipt("command");KnowledgeWaitingPreface.timeout(receipt);
        check(receipt.optString("status").equals("callback_timeout")&&!receipt.optBoolean("send_completed"),"missing callback is explicit");
        KnowledgeWaitingPreface.callback(receipt,true);
        check(receipt.optString("status").equals("callback_timeout")&&receipt.optString("late_callback").equals("sent"),"late callback cannot rewrite timeout");
        System.out.println("knowledge waiting preface checks passed");
    }
}
