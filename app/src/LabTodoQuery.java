package dev.xr.rayneo.probe;

import org.json.*;

/** One read request. No sync/delete command, retry or fallback is provided. */
final class LabTodoQuery {
    final String id;
    final long deadline;
    final JSONArray replies=new JSONArray();
    boolean ack,done,last,stored;
    int items,todoTotal=-1;
    String issue="";
    LabTodoQuery(String id,long now){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid query identity");
        this.id=id;deadline=now+15000;
    }
    static JSONObject request()throws Exception{
        return new JSONObject().put("queryType",0).put("eventType",1).put("lastSyncTime",0)
            .put("eventIDList",new JSONArray()).put("needFullData",true);
    }
    boolean owns(String request){return ("todo-query-"+id).equals(request);}
    void fail(String reason){if(!done){issue=reason;done=true;}}
    void sent(boolean success,long now){
        if(done)return;if(now>=deadline){tick(now);return;}
        if(!success){fail("send_failed");return;}ack=true;finishIfReady();
    }
    void reply(int type,JSONObject body,long now)throws Exception{
        if(done)return;if(now>=deadline){tick(now);return;}if(type!=16)return;
        if(replies.length()>=100){fail("reply_limit");return;}
        replies.put(body);
        JSONArray data=body.optJSONArray("dataList");
        if(data==null||!(body.opt("isLastBatch") instanceof Boolean)) {fail("unrecognized_response_shape");return;}
        Object total=body.opt("todoTotal");
        if(!(total instanceof Number)||((Number)total).longValue()<0||((Number)total).longValue()>10000){fail("unrecognized_todo_total");return;}
        int count=((Number)total).intValue();
        if(todoTotal>=0&&todoTotal!=count){fail("list_changed_during_query");return;}todoTotal=count;
        items+=data.length();if(items>10000){fail("item_limit");return;}
        last=body.optBoolean("isLastBatch");
        if(last&&items!=todoTotal){fail("incomplete_or_duplicate_items");return;}
        finishIfReady();
    }
    private void finishIfReady(){if(ack&&last&&issue.isEmpty())done=true;}
    void tick(long now){if(!done&&now>=deadline)fail(ack?"response_timeout":"send_ack_timeout");}
    boolean passed(){return done&&ack&&last&&issue.isEmpty();}
    JSONObject summary()throws Exception{
        return new JSONObject().put("command_id",id).put("phase",done?(passed()?"response_received":"failed"):"pending")
            .put("send_ack",ack).put("response_batches",replies.length()).put("items",items).put("todo_total",todoTotal)
            .put("is_last_batch",last).put("issue",issue).put("read_only",true).put("request_count",1)
            .put("association","current_device_session_time_window_not_wire_request_id");
    }
}
