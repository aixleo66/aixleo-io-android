package dev.xr.rayneo.probe;

import org.json.*;
import java.util.*;

/** Official-model todo sync (09-23 plan stage 1).
 *
 * <p>The observed official app synchronization shows the phone is the only source: on connect it queries
 * the glasses list (SCHEDULE_TODO type 15 -> 16) and then pushes its own whole pending list
 * (type 6, {@code total/isLastBatch/eventList}) over it; a delete is additionally sent as type 7
 * {@code eventIDList} and answered by type 14; the glasses themselves only report "completed"
 * (type 4). This class runs one such round: read, let the host reconcile (adopt items the phone
 * does not know, take completions made on the glasses), push the table, send pending deletes,
 * read back, and pass only when the glasses list equals what was pushed. Plain Java: checked off
 * device. */
final class TodoFullSync {
    interface Host { Plan reconcile(LinkedHashMap<Long,JSONObject> glasses)throws Exception; }
    static final class Plan {
        final List<JSONObject> items; final List<Long> deleted; final int adopted,completedOnGlasses;
        /** Event IDs the phone has as completed: the only items allowed to remain on the glasses
         * outside the table (with status 1). */
        final Set<Long> completedHere;
        Plan(List<JSONObject> items,List<Long> deleted,int adopted,int completedOnGlasses){this(items,deleted,adopted,completedOnGlasses,new HashSet<>());}
        Plan(List<JSONObject> items,List<Long> deleted,int adopted,int completedOnGlasses,Set<Long> completedHere){
            this.items=items;this.deleted=deleted;this.adopted=adopted;this.completedOnGlasses=completedOnGlasses;this.completedHere=completedHere;
        }
    }
    /** The official app pushed at most 13 in one batch on 09-23; larger single batches are untested. */
    static final int MAX_ITEMS=50;
    final String id;
    final long deadline;
    LabTodoQuery query;
    Plan plan;
    JSONObject table,deleteCommand,deleteReceipt;
    final LinkedHashMap<Long,JSONObject> before=new LinkedHashMap<>();
    final JSONArray after=new JSONArray();
    String phase="reading_before",issue="";
    boolean done,readSent,tableSent,tableAck,deleteSent,deleteAck,deletesCleared;
    long verifyAt,deleteWaitUntil;
    int keptCompleted;

    TodoFullSync(String id,long now){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid todo sync identity");
        this.id=id;deadline=now+60000;query=new LabTodoQuery(id+"-before",now);
    }
    String readId(){return "todo-full-read-"+query.id;}
    String tableId(){return "todo-full-table-"+id;}
    String deleteId(){return "todo-full-delete-"+id;}
    boolean owns(String value){return value.equals(readId())||value.equals(tableId())||value.equals(deleteId());}
    boolean passed(){return done&&phase.equals("verified");}
    void fail(String reason){if(!done){issue=reason;phase=tableSent?"write_unconfirmed":"failed";done=true;}}

    /** One todo as the official app sends it: createTime in ms, lastModifiedTime in seconds. */
    static JSONObject item(long eventId,String title,boolean important,long createTimeMs,long modifiedSeconds)throws Exception{
        if(eventId<=0||createTimeMs<=0)throw new IllegalArgumentException("Invalid todo identity");
        return new JSONObject().put("eventType",1).put("eventID",eventId).put("createTime",createTimeMs)
            .put("title",checkedTitle(title)).put("isImportant",important).put("status",0).put("lastModifiedTime",Math.max(0,modifiedSeconds));
    }
    /** Same rule as TodoStore.checkedTitle (kept here so this class compiles off device). */
    static String checkedTitle(String value){
        String title=value==null?"":value.trim();
        if(title.isEmpty()||title.length()>300)throw new IllegalArgumentException("Invalid todo title");
        return title;
    }
    static JSONObject tableOf(List<JSONObject> items)throws Exception{
        JSONArray list=new JSONArray();Set<Long> ids=new HashSet<>();
        for(JSONObject value:items){
            JSONObject copy=LabTodoSync.copyItem(value);
            if(!ids.add(LabTodoSync.integer(copy,"eventID")))throw new IllegalArgumentException("Duplicate todo identity");
            if(LabTodoSync.integer(copy,"status")!=0)throw new IllegalArgumentException("Completed todo in pending table");
            list.put(copy);
        }
        return new JSONObject().put("total",items.size()).put("isLastBatch",true).put("eventList",list);
    }
    static JSONObject deleteOf(List<Long> ids)throws Exception{
        JSONArray list=new JSONArray();for(long value:ids){if(value<=0)throw new IllegalArgumentException("Invalid todo identity");list.put(value);}
        return new JSONObject().put("eventType",1).put("todoTotal",ids.size()).put("eventIDList",list);
    }
    private LinkedHashMap<Long,JSONObject> rows()throws Exception{
        LinkedHashMap<Long,JSONObject> rows=new LinkedHashMap<>();
        for(int b=0;b<query.replies.length();b++){
            JSONArray data=query.replies.getJSONObject(b).getJSONArray("dataList");
            for(int i=0;i<data.length();i++){
                JSONObject item=LabTodoSync.copyItem(data.getJSONObject(i));
                if(rows.put(LabTodoSync.integer(item,"eventID"),item)!=null)throw new IllegalArgumentException("Duplicate todo identity");
            }
        }
        return rows;
    }
    void advance(Host host,long now)throws Exception{
        if(done)return;if(now>=deadline){fail("sync_timeout");return;}
        if(phase.equals("reading_before")||phase.equals("reading_after")){
            query.tick(now);if(!query.done)return;if(!query.passed()){fail(query.issue);return;}
            LinkedHashMap<Long,JSONObject> items=rows();
            if(phase.equals("reading_before")){
                before.putAll(items);
                plan=host.reconcile(new LinkedHashMap<>(items));
                if(plan.items.size()>MAX_ITEMS){fail("too_many_items");return;}
                table=tableOf(plan.items);deleteCommand=plan.deleted.isEmpty()?null:deleteOf(plan.deleted);
                phase="writing_table";
            }else{
                for(JSONObject row:items.values())after.put(row);
                // 09-23 20:03 on device: an item completed on the glasses stays in their list with
                // status 1 after a table without it (one completed on the phone and still pending
                // on the glasses is dropped). Those are the glasses' own record, not a mismatch.
                Set<Long> pushed=new HashSet<>();for(JSONObject sent:plan.items)pushed.add(LabTodoSync.integer(sent,"eventID"));
                // Missing first, extras second: the issue does not depend on the reply's row order.
                for(long wanted:pushed)if(!items.containsKey(wanted)){fail("readback_count_mismatch");return;}
                // An extra row passes only when it is completed on the glasses, completed on the
                // phone, and not deleted on the phone this round (review 09-23 .71: a deleted item
                // left on the glasses, or an unknown completed one, is a failed round).
                Set<Long> gone=new HashSet<>(plan.deleted);int kept=0;
                for(Map.Entry<Long,JSONObject> e:items.entrySet()){
                    if(pushed.contains(e.getKey()))continue;
                    boolean ownCompleted=LabTodoSync.integer(e.getValue(),"status")==1&&plan.completedHere.contains(e.getKey())&&!gone.contains(e.getKey());
                    if(!ownCompleted){fail(gone.contains(e.getKey())?"readback_deleted_still_present":"readback_extra_item");return;}
                    kept++;
                }
                for(JSONObject sent:plan.items){
                    JSONObject got=items.get(LabTodoSync.integer(sent,"eventID"));
                    if(got==null||!sameContent(sent,got)){fail("readback_item_mismatch");return;}
                }
                keptCompleted=kept;
                phase="verified";done=true;
            }
        }else if(phase.equals("waiting_delete_receipt")&&(deleteReceipt!=null||now>=deleteWaitUntil)){
            phase="waiting_readback";verifyAt=now+500;
        }else if(phase.equals("waiting_readback")&&now>=verifyAt){
            query=new LabTodoQuery(id+"-after",now);readSent=false;phase="reading_after";
        }
    }
    /** Title, importance, status and identity must match. lastModifiedTime is not compared: the
     * glasses keep their own clock for it (a completion on the glasses reported it in ms). */
    static boolean sameContent(JSONObject sent,JSONObject got)throws Exception{
        return LabTodoSync.integer(sent,"eventID")==LabTodoSync.integer(got,"eventID")
            &&LabTodoSync.integer(sent,"status")==LabTodoSync.integer(got,"status")
            &&LabTodoSync.integer(sent,"createTime")==LabTodoSync.integer(got,"createTime")
            &&sent.getString("title").equals(got.getString("title"))
            &&Objects.equals(sent.opt("isImportant"),got.opt("isImportant"));
    }
    void sent(Host host,String request,boolean success,long now)throws Exception{
        if(done)return;
        if(request.equals(tableId())&&phase.equals("writing_table")){
            if(!success){fail("table_send_failed");return;}
            tableAck=true;
            if(deleteCommand!=null)phase="writing_delete";else{phase="waiting_readback";verifyAt=now+800;}
        }else if(request.equals(deleteId())&&phase.equals("writing_delete")){
            if(!success){fail("delete_send_failed");return;}
            // The table already dropped the items; the official receipt then says "todo not found".
            // It is recorded, not required: a missing receipt does not fail the round.
            deleteAck=true;phase="waiting_delete_receipt";deleteWaitUntil=now+3000;
        }else if(request.equals(readId()))query.sent(success,now);
        advance(host,now);
    }
    void reply(Host host,int type,JSONObject body,long now)throws Exception{
        if(done)return;
        if(type==14&&phase.equals("waiting_delete_receipt"))deleteReceipt=body;
        else if(type==16&&(phase.equals("reading_before")||phase.equals("reading_after")))query.reply(type,body,now);
        advance(host,now);
    }
    JSONObject receipt()throws Exception{
        JSONArray original=new JSONArray();for(JSONObject row:before.values())original.put(row);
        return new JSONObject().put("command_id",id).put("operation","full_table").put("phase",phase).put("issue",issue)
            .put("table_send_completed",tableAck).put("delete_send_completed",deleteAck)
            .put("readback_verified",passed()).put("before",original)
            .put("table",table==null?JSONObject.NULL:table).put("delete",deleteCommand==null?JSONObject.NULL:deleteCommand)
            .put("delete_receipt",deleteReceipt==null?JSONObject.NULL:deleteReceipt).put("after",after)
            .put("adopted",plan==null?0:plan.adopted).put("completed_on_glasses",plan==null?0:plan.completedOnGlasses)
            .put("kept_completed_on_glasses",keptCompleted)
            .put("model","official_phone_is_source_full_table_type6");
    }
}
