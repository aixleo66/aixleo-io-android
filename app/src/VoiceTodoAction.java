package dev.xr.rayneo.probe;

import java.util.*;
import org.json.*;

/** Explicit local commands only. Does not infer reminders or touch the glasses task list. */
final class VoiceTodoAction {
    static final class Entry {
        final String id,title; final boolean completed;
        Entry(String id,String title,boolean completed){this.id=id;this.title=title;this.completed=completed;}
    }
    interface Store {
        Entry create(String command,String title)throws Exception;
        List<Entry> list()throws Exception;
        Entry complete(String id)throws Exception;
    }
    interface Check {void run()throws InterruptedException;}
    final String kind,title;
    private VoiceTodoAction(String kind,String title){this.kind=kind;this.title=title;}
    /** Explicit local commands only. Does not infer reminders or touch the glasses task list.
     *
     * <p>The operation prefix used to be four fixed strings, so "帮我加一个待办，明天下午三点开会"
     * matched none of them and went to the model as chat, which answered that it has no such
     * ability (observed 2026-09-20). The verb, the optional quantifier and the noun
     * are now normalized separately so the natural phrasings reach the same four operations.
     * Nothing is inferred from the title itself and questions are still refused. */
    static VoiceTodoAction parse(String text){
        String value=VoicePhrase.normalize(text);
        if(value.isEmpty())return null;
        String verbs="(?:添加|新增|加|记|记一条|记一下|创建|新建|建)";
        String done="(?:完成|做完|勾选|标记完成)";
        String show="(?:查看|查询|看看|列出)";
        String count="(?:一个|一条|一下|个|条)?";
        // Longest alternative first: Java alternation is leftmost-first, so a short branch ahead
        // of a long one cuts "待办事项" after "待办" and glues "事项" onto the title.
        String noun="(?:待办事项|代办事项|任务清单|待办|代办|任务|事项|清单|备忘)";
        // Normalize only the operation prefix, never the user task title.
        value=value.replaceFirst("^"+verbs+count+noun, "添加待办")
            .replaceFirst("^"+done+count+noun, "完成待办")
            .replaceFirst("^"+show+"(?:一下)?(?:我的)?"+noun, "查看待办")
            .replaceFirst("^我的"+noun+"有哪些", "查看待办");
        if(value.replaceAll("[？?]$", "").equals("查看待办"))return new VoiceTodoAction("list","");
        String[][] prefixes={{"添加待办","create"},{"完成待办","complete"}};
        for(String[] prefix:prefixes)if(value.startsWith(prefix[0])){
            String title=value.substring(prefix[0].length()).replaceFirst("^[\\s，,:：]+", "").trim();
            // A question about the command is never a write request.
            if(VoicePhrase.isQuestion(title))return null;
            // Trailing modal particles are not part of the task and break the exact match that
            // completion later performs on the stored title.
            title=VoicePhrase.trimParticles(title);
            return new VoiceTodoAction(title.isEmpty()||title.length()>300?"invalid":prefix[1],title);
        }
        return null;
    }
    JSONObject execute(Store store,String command,Check check)throws Exception{
        check.run();
        String text,id="",outcome="succeeded"; int count=-1;
        try{
            if(kind.equals("invalid")){outcome="rejected";text="请说添加待办或完成待办，再说一条完整标题，最多三百字。";}
            else if(kind.equals("create")){
                check.run();Entry item=store.create(command,title);id=item.id;
                text="已记入手机待办，不设提醒："+item.title;
            }else if(kind.equals("list")){
                List<Entry> pending=new ArrayList<>();for(Entry item:store.list())if(!item.completed)pending.add(item);
                count=pending.size();StringBuilder out=new StringBuilder("手机待办共"+count+"条未完成。");
                if(count>5)out.append("以下是前五条，完整列表见手机。");
                for(int i=0;i<Math.min(5,count);i++){
                    String name=pending.get(i).title;out.append("\n").append(i+1).append("、");
                    out.append(name.length()>60?name.substring(0,60)+"…（标题截短）":name);
                }
                text=out.toString();
            }else{
                List<Entry> matches=new ArrayList<>();for(Entry item:store.list())if(item.title.equals(title))matches.add(item);
                if(matches.size()!=1){outcome="rejected";text=matches.isEmpty()?"手机待办中没有完全同名的条目，未修改。请在手机核对完整标题。":"手机待办有多条同名内容，未修改。请在手机选择具体条目。";}
                else{
                    check.run();Entry item=store.complete(matches.get(0).id);id=item.id;
                    if(!item.completed)throw new IllegalStateException("completion not stored");
                    text="手机待办已完成："+item.title;
                }
            }
        }catch(InterruptedException e){throw e;}
        catch(Exception e){outcome="failed";text="手机待办操作未确认成功，请在手机查看实际结果。";}
        // This receipt describes the action independently of later display/cancellation.
        return new JSONObject().put("status","completed").put("provider","local_todo").put("text",text)
            .put("action",new JSONObject().put("kind",kind).put("status",outcome).put("task_id",id)
                .put("pending_count",count).put("storage","app_private_sqlite").put("glasses_synced",false));
    }
}
