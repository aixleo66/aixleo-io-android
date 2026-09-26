package dev.xr.rayneo.probe;
import org.json.JSONObject;
import org.json.JSONArray;

public final class KnowledgeConfigCheck {
    public static void main(String[] args)throws Exception {
        JSONObject config=CloudConfig.defaults().put("assistant_provider","knowledge");
        if(!config.optBoolean("assistant_followup_enabled"))throw new AssertionError("Follow-up window must default on");
        CloudClient.validateConfig(config);
        for(int seconds:new int[]{10,15,120}){config.put("assistant_auto_exit_seconds",seconds);CloudClient.validateConfig(config);}
        for(int seconds:new int[]{9,121}){config.put("assistant_auto_exit_seconds",seconds);try{CloudClient.validateConfig(config);throw new AssertionError("Invalid auto exit accepted");}catch(CloudClient.Failure expected){}}
        config.put("assistant_auto_exit_seconds",15);
        for(int seconds:new int[]{10,15,120}){config.put("assistant_followup_seconds",seconds);CloudClient.validateConfig(config);}
        for(int seconds:new int[]{9,121}){config.put("assistant_followup_seconds",seconds);try{CloudClient.validateConfig(config);throw new AssertionError("Invalid follow-up window accepted");}catch(CloudClient.Failure expected){}}
        config.put("assistant_followup_seconds",10);
        config.put("assistant_followup_enabled","true");
        try{CloudClient.validateConfig(config);throw new AssertionError("Non-boolean follow-up setting accepted");}catch(CloudClient.Failure expected){}
        config.put("assistant_followup_enabled",true);
        for(boolean hasToken:new boolean[]{false,true}) {
            config.put("knowledge_token",hasToken?"fake-test-token":"");
            try {
                CloudClient.ask(config,"知识库检索测试",new CloudClient.Cancellation());
                throw new AssertionError("Missing knowledge endpoint must fail before network");
            } catch(CloudClient.Failure expected) {
                if(!expected.getMessage().equals("请先填写知识库地址和 Token"))throw expected;
            }
        }
        // Default must fail on its own missing key, not old global knowledge settings.
        try {
            CloudClient.ask(config,"一加一等于几",new CloudClient.Cancellation());
            throw new AssertionError("Missing default key must fail before network");
        } catch(CloudClient.Failure expected) {
            if(!expected.getMessage().contains("deepseek Key"))throw expected;
        }
        config.put("knowledge_url","http://invalid.example");
        try { CloudClient.validateConfig(config);throw new AssertionError("Malformed endpoint accepted"); }
        catch(CloudClient.Failure expected) {}
        JSONObject noMatch=KnowledgeClient.retrievalSummary(new JSONObject().put("status","no_match").put("sources",new JSONArray()));
        JSONObject answer=new JSONObject().put("text","原始回答").put("short_answer","短回答").put("lens_text","原始回答").put("sources",new JSONArray());
        KnowledgeClient.attachRetrieval(answer,noMatch);
        if(!answer.getString("text").equals("原始回答")||!answer.getString("lens_text").startsWith("知识库未命中")||answer.getLong("candidate_count")!=0
            ||!answer.getString("grounding_status").equals("not_cited")||answer.getLong("cited_source_count")!=0)
            throw new AssertionError("No-hit must qualify lens without overwriting original answer");
        JSONObject matched=KnowledgeClient.retrievalSummary(new JSONObject().put("status","matched").put("sources",new JSONArray().put(new JSONObject().put("path","doc.md"))));
        JSONObject uncited=new JSONObject().put("text","答案").put("short_answer","答案").put("lens_text","答案").put("sources",new JSONArray());
        KnowledgeClient.attachRetrieval(uncited,matched);
        if(!uncited.getString("retrieval_status").equals("matched")||!uncited.getString("grounding_status").equals("not_cited")
            ||uncited.getLong("cited_source_count")!=0||!uncited.getString("lens_text").equals("答案"))
            throw new AssertionError("Zero citations do not mean no retrieval hits");
        JSONObject cited=new JSONObject().put("text","答案").put("short_answer","答案").put("lens_text","答案")
            .put("sources",new JSONArray().put(new JSONObject().put("path","doc.md").put("title","资料")));
        KnowledgeClient.attachRetrieval(cited,matched);
        if(!cited.getString("retrieval_status").equals("matched")||!cited.getString("grounding_status").equals("cited")
            ||cited.getLong("cited_source_count")!=1)throw new AssertionError("Final citations stay separate from retrieval candidates");
        if(!KnowledgeClient.retrievalSummary(new JSONObject().put("status","no_match").put("sources",new JSONArray().put(new JSONObject()))).getString("retrieval_status").equals("unknown"))throw new AssertionError("Contradictory event is not proof of no hit");
        JSONObject unknown=new JSONObject().put("text","答案").put("short_answer","答案").put("lens_text","答案");
        KnowledgeClient.attachRetrieval(unknown,null);
        if(!unknown.getString("retrieval_status").equals("unknown")||unknown.has("candidate_count")
            ||!unknown.getString("grounding_status").equals("unknown")||unknown.has("cited_source_count")
            ||!unknown.getString("lens_text").startsWith("知识库依据状态未确认"))throw new AssertionError("Missing sources event stays visibly unknown");
        System.out.println("knowledge config checks passed");
    }
}
