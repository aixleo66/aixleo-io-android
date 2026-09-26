package dev.xr.rayneo.probe;

import org.json.*;
import java.util.*;

/** Bounded single-item write, with full read-before/read-after. Never sends bulk/delete. */
final class LabTodoSync {
    final String id,mode,title;
    final long eventId,createTime,status,nowSeconds;
    final long deadline;
    LabTodoQuery query;
    JSONObject payload;
    final LinkedHashMap<Long,JSONObject> before=new LinkedHashMap<>();
    final JSONArray after=new JSONArray();
    String phase="reading_before",issue="",effect="";
    boolean done,writeSent,writeAck,readSent;
    long verifyAt;
    LabTodoSync(String id,String mode,long eventId,String title,long createTime,long status,long wallSeconds,long now){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}")||eventId<=0)throw new IllegalArgumentException("Invalid todo identity");
        if(!mode.equals("rename")&&!mode.equals("push_local")&&!mode.equals("update_local"))throw new IllegalArgumentException("Unsupported todo operation");
        if(title==null||title.trim().isEmpty()||title.length()>300)throw new IllegalArgumentException("Invalid todo title");
        if((mode.equals("push_local")||mode.equals("update_local"))&&(createTime<=0||(status!=0&&status!=1)))throw new IllegalArgumentException("Invalid local todo");
        this.id=id;this.mode=mode;this.eventId=eventId;this.title=title.trim();this.createTime=createTime;this.status=status;nowSeconds=wallSeconds;
        deadline=now+45000;query=new LabTodoQuery(id+"-before",now);
    }
    String readId(){return "todo-sync-read-"+query.id;}
    String writeId(){return "todo-sync-write-"+id;}
    boolean owns(String value){return value.equals(readId())||value.equals(writeId());}
    boolean passed(){return done&&phase.equals("verified");}
    void fail(String reason){if(!done){issue=reason;phase=writeSent?"write_unconfirmed":"failed";done=true;}}
    static long integer(JSONObject item,String key)throws Exception{
        Object value=item.get(key);if(!(value instanceof Long)&&!(value instanceof Integer))throw new IllegalArgumentException("Non-integer "+key);
        return ((Number)value).longValue();
    }
    static JSONObject copyItem(JSONObject item)throws Exception{
        String[] names={"eventType","eventID","title","isImportant","status","createTime","lastModifiedTime"};
        if(item.length()!=names.length||integer(item,"eventType")!=1||integer(item,"eventID")<=0||!(item.get("title") instanceof String)
           ||integer(item,"createTime")<=0||integer(item,"lastModifiedTime")<0)throw new IllegalArgumentException("Unsupported todo shape");
        long state=integer(item,"status");if(state!=0&&state!=1)throw new IllegalArgumentException("Unsupported todo status");
        Object important=item.get("isImportant");if(important!=JSONObject.NULL&&!(important instanceof Boolean))throw new IllegalArgumentException("Unsupported priority");
        JSONObject out=new JSONObject();for(String key:names)out.put(key,item.get(key));return out;
    }
    static boolean same(JSONObject a,JSONObject b)throws Exception{
        if(a.length()!=b.length())return false;
        Iterator<String> keys=a.keys();while(keys.hasNext()){
            String k=keys.next();if(!b.has(k))return false;Object x=a.get(k),y=b.get(k);
            if(x instanceof Number&&y instanceof Number){if(integer(a,k)!=integer(b,k))return false;}
            else if(!x.equals(y))return false;
        }return true;
    }
    LinkedHashMap<Long,JSONObject> rows()throws Exception{
        LinkedHashMap<Long,JSONObject> rows=new LinkedHashMap<>();
        for(int b=0;b<query.replies.length();b++){
            JSONArray data=query.replies.getJSONObject(b).getJSONArray("dataList");
            for(int i=0;i<data.length();i++){JSONObject item=copyItem(data.getJSONObject(i));long key=integer(item,"eventID");if(rows.put(key,item)!=null)throw new IllegalArgumentException("Duplicate todo identity");}
        }return rows;
    }
    void advance(long now)throws Exception{
        if(done)return;if(now>=deadline){fail("sync_timeout");return;}
        if(phase.equals("reading_before")||phase.equals("reading_after")){
            query.tick(now);if(!query.done)return;if(!query.passed()){fail(query.issue);return;}
            LinkedHashMap<Long,JSONObject> items=rows();
            if(phase.equals("reading_before")){
                before.putAll(items);JSONObject old=before.get(eventId);
                if(mode.equals("rename")){
                    if(old==null){fail("target_not_found");return;}payload=copyItem(old);payload.put("title",title);
                }else if(mode.equals("update_local")){
                    if(old==null){fail("mapped_target_not_found");return;}
                    if(integer(old,"createTime")!=createTime){fail("mapped_target_identity_mismatch");return;}
                    payload=copyItem(old);payload.put("title",title).put("status",status);
                    if(old.optString("title").equals(title)&&integer(old,"status")==status){
                        for(JSONObject row:items.values())after.put(row);
                        effect="already_equal";phase="verified";done=true;return;
                    }
                }else{
                    if(status==1){fail("completed_local_not_in_pending_list");return;}
                    if(old!=null){fail("new_id_already_exists_no_overwrite");return;}
                    payload=new JSONObject().put("eventType",1).put("eventID",eventId).put("title",title).put("isImportant",false)
                        .put("status",status).put("createTime",createTime).put("lastModifiedTime",nowSeconds);
                }
                payload.put("lastModifiedTime",Math.max(nowSeconds,old==null?0:integer(old,"lastModifiedTime")+1));
                phase="writing";
            }else{
                for(JSONObject row:items.values())after.put(row);
                boolean completedRemoval=mode.equals("update_local")&&status==1&&writeAck&&before.containsKey(eventId)&&integer(before.get(eventId),"status")==0&&!items.containsKey(eventId);
                int expected=completedRemoval?before.size()-1:before.size()+(before.containsKey(eventId)?0:1);
                if(items.size()!=expected||(!completedRemoval&&(!items.containsKey(eventId)||!same(payload,items.get(eventId))))){fail("target_readback_mismatch");return;}
                for(Map.Entry<Long,JSONObject> e:before.entrySet())if(e.getKey()!=eventId&&(!items.containsKey(e.getKey())||!same(e.getValue(),items.get(e.getKey())))){fail("other_item_changed");return;}
                effect=completedRemoval?"removed_from_pending_list":"target_fields_verified";
                phase="verified";done=true;
            }
        }else if(phase.equals("waiting_readback")&&now>=verifyAt){query=new LabTodoQuery(id+"-after",now);readSent=false;phase="reading_after";}
    }
    void sent(String request,boolean success,long now)throws Exception{
        if(done)return;
        if(request.equals(writeId())&&phase.equals("writing")){
            if(!success){fail("write_send_failed");return;}writeAck=true;phase="waiting_readback";verifyAt=now+500;
        }else if(request.equals(readId()))query.sent(success,now);
        advance(now);
    }
    void reply(int type,JSONObject body,long now)throws Exception{
        if(done)return;if(phase.equals("reading_before")||phase.equals("reading_after")){query.reply(type,body,now);advance(now);}
    }
    JSONObject receipt()throws Exception{
        JSONArray original=new JSONArray();for(JSONObject row:before.values())original.put(row);
        return new JSONObject().put("command_id",id).put("operation",mode).put("phase",phase).put("issue",issue)
            .put("event_id",Long.toString(eventId)).put("write_submitted",writeSent).put("write_send_completed",writeAck)
            .put("readback_verified",passed()).put("verified_effect",effect).put("before",original).put("payload",payload==null?JSONObject.NULL:payload).put("after",after)
            .put("association","serial_current_device_session_no_wire_query_id").put("bulk_or_delete_sent",false);
    }
}
