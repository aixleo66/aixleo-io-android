package dev.xr.rayneo.probe;

import org.json.*;

/** One local action and its glasses verification remain inside the same voice round. */
final class VoiceTodoSyncRound {
    final String command,session;
    final JSONObject pipeline;
    final LabTodoSync sync;
    boolean handled,cancelled;
    VoiceTodoSyncRound(String command,String session,JSONObject pipeline,LabTodoSync sync){
        if(!command.equals(sync.id))throw new IllegalArgumentException("Different todo owner");
        this.command=command;this.session=session;this.pipeline=pipeline;this.sync=sync;
    }
    static boolean eligible(JSONObject answer)throws Exception{
        JSONObject a=answer.getJSONObject("action");
        return a.optString("status").equals("succeeded")&&!a.optString("task_id").isEmpty()
            &&(a.optString("kind").equals("create")||a.optString("kind").equals("complete"));
    }
    boolean current(String session,String command,boolean pending){
        return !cancelled&&this.session.equals(session)&&this.command.equals(command)&&pending;
    }
    void cancel(String reason){if(handled)return;cancelled=true;sync.fail(reason);}
    boolean finish(String session,String command,boolean pending)throws Exception{
        if(handled||!sync.done)return false;
        boolean display=current(session,command,pending);handled=true;
        if(!display)cancelled=true;
        annotate(pipeline,sync.passed(),sync.phase,sync.effect,"todo-sync/"+sync.id+".json",sync.issue);
        if(cancelled)pipeline.put("delivery",new JSONObject().put("status","cancelled").put("lens_verified",false));
        return display;
    }
    static void annotate(JSONObject pipeline,boolean verified,String status,String effect,String path,String issue)throws Exception{
        JSONObject answer=pipeline.getJSONObject("answer"),a=answer.getJSONObject("action");
        a.put("glasses_synced",verified).put("sync_status",status).put("verified_effect",effect)
            .put("sync_receipt_path",path).put("sync_issue",issue);
        boolean completed=a.optString("kind").equals("complete");
        String text;
        if(verified)text=completed?(effect.equals("removed_from_pending_list")?"手机待办已完成，眼镜待办列表也已移出。":"手机待办已完成，眼镜状态已核对。")
            :"已记入手机待办并同步到眼镜，不设提醒。";
        else if(status.equals("not_mapped"))text="手机待办已完成；该条尚未同步到眼镜。";
        else text=completed?"手机待办已完成，眼镜同步未确认，请在手机核对。":"手机待办已保存，眼镜同步未确认，请在手机核对。";
        answer.put("text",text);pipeline.put("text",text);
    }
}
