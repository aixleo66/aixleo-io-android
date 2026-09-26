package dev.xr.rayneo.probe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serial UI bridge for the already-running SDK Lab session. */
final class TodoGlassesClient implements AutoCloseable {
    interface Callback {
        void progress(String value);
        void complete(Snapshot value, String message);
        void failed(String message);
    }
    static final class Snapshot {
        final String session,address;
        final List<JSONObject> items;
        String effect="";
        Snapshot(String session,String address,List<JSONObject> items){this.session=session;this.address=address;this.items=items;}
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"todo-glasses-ui");t.setDaemon(true);return t;});
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private volatile boolean closed;

    TodoGlassesClient(Context context){this.context=context.getApplicationContext();}
    boolean busy(){return BUSY.get();}
    void refresh(Callback callback){run("lab-todo-query",null,null,null,callback);}
    /** Official-model sync (09-23 stage 1): read the glasses, adopt/complete, push the whole phone list, read back. */
    void fullSync(Callback callback){run("lab-todo-full-sync",new JSONObject(),null,null,callback);}
    void updateWeather(JSONObject source,Callback callback){run("lab-weather-update",source,null,null,callback);}
    void rename(Snapshot source,String eventId,String title,Callback callback){
        try{
            if(source==null)throw new IllegalArgumentException("请先刷新当前眼镜待办");
            run("lab-todo-sync",new JSONObject().put("operation","rename").put("event_id",eventId).put("title",title),source.session,source.address,callback);
        }
        catch(Exception e){postFailed(callback,"修改参数无效");}
    }
    void syncLocal(String localId,boolean update,Callback callback){
        try{
            JSONObject current=readSession();requireLive(current);
            run("lab-todo-sync",new JSONObject().put("operation",update?"update_local":"push_local").put("local_id",localId),current.getString("session_id"),current.getString("target_address"),callback);
        }catch(Exception e){postFailed(callback,userMessage(e));}
    }
    private void run(String kind,JSONObject todo,String expectedSession,String expectedAddress,Callback callback){
        if(closed){postFailed(callback,"页面已关闭");return;}
        if(!BUSY.compareAndSet(false,true)){postFailed(callback,"另一项眼镜操作正在进行");return;}
        WORKER.execute(()->{
            String session="",address="";boolean restoreOriginalStandby=false;String operationError=null,restoreError=null;Snapshot snapshot=null;
            try{
                JSONObject initial=readSession();requireLive(initial);
                session=initial.getString("session_id");address=initial.getString("target_address");
                if(expectedSession!=null&&(!expectedSession.equals(session)||!expectedAddress.equals(address)))throw new IOException("眼镜连接已更换，请刷新后重试");
                JSONObject standby=initial.optJSONObject("standby");
                ensureIdle(initial);
                if(standby!=null&&standby.optBoolean("enabled")){
                    if(!standby.optBoolean("auto_restore"))throw new IOException("当前待命模式无法无损恢复，请先退出待命");
                    restoreOriginalStandby=true;
                    postProgress(callback,"暂时停止语音待命，保持眼镜连接…");
                    waitCommand(session,address,"standby-off",null,15000,false);
                }
                postProgress(callback,kind.equals("lab-weather-update")?"正在发送当前位置天气…":kind.equals("lab-todo-query")?"正在读取眼镜待办…":kind.equals("lab-todo-full-sync")?"正在同步待办到眼镜…":"正在核对并单条同步…");
                JSONObject completed=waitCommand(session,address,kind,todo,kind.equals("lab-todo-full-sync")?70000:kind.equals("lab-todo-sync")?60000:25000,false);
                if(kind.equals("lab-weather-update")){
                    JSONObject weather=completed.getJSONObject("weather_update");
                    if(!weather.optBoolean("send_ack")||!"transport_sent".equals(weather.optString("phase")))throw new IOException("天气发送未确认");
                    snapshot=new Snapshot(session,address,new ArrayList<>());
                }else snapshot=kind.equals("lab-todo-query")?querySnapshot(completed,session,address):kind.equals("lab-todo-full-sync")?fullSnapshot(completed,session,address):syncSnapshot(completed,session,address);
            }catch(Exception e){operationError=userMessage(e);}
            finally{
                if(restoreOriginalStandby&&!session.isEmpty()){
                    try{
                        JSONObject current=readSession();
                        if(isLive(current)&&session.equals(current.optString("session_id"))&&address.equals(current.optString("target_address"))){
                            JSONObject standby=current.optJSONObject("standby");
                            if(standby==null||!standby.optBoolean("enabled")||!standby.optBoolean("ready")){
                                postProgress(callback,"正在恢复眼镜语音待命…");
                                waitCommand(session,address,"voice-standby",null,45000,true);
                            }
                        }else restoreError="连接会话已更换，未代替新会话修改待命";
                    }catch(Exception e){restoreError="语音待命恢复未确认："+userMessage(e);}
                }
                BUSY.set(false);
            }
            if(operationError==null&&snapshot!=null){
                String message=kind.equals("lab-weather-update")?"当前位置天气已发送，请在眼镜查看":kind.equals("lab-todo-query")?"已读取当前眼镜待办":kind.equals("lab-todo-full-sync")?"眼镜待办已与手机一致"+snapshot.effect:"眼镜待办已读回确认";
                if(snapshot.effect.equals("removed_from_pending_list"))message="已核对：该任务已从眼镜待办列表移出";
                else if(snapshot.effect.equals("already_equal"))message="眼镜内容已一致，本次未重复写入";
                if(restoreError!=null)message+="；"+restoreError;
                postComplete(callback,snapshot,message);
            }else{
                String message=operationError==null?"眼镜待办结果未确认":operationError;
                if(restoreError!=null)message+="；"+restoreError;
                postFailed(callback,message);
            }
        });
    }
    private JSONObject waitCommand(String session,String address,String kind,JSONObject todo,long timeout,boolean standbyReady)throws Exception{
        JSONObject current=readSession();requireLive(current);
        if(!session.equals(current.optString("session_id"))||!address.equals(current.optString("target_address")))throw new IOException("眼镜连接已更换");
        JSONObject prior=current.optJSONObject("last_command");
        if((prior!=null&&"pending".equals(prior.optString("status")))||new File(context.getFilesDir(),"session-command.json").exists())throw new IOException("上一项眼镜操作尚未完成");
        String id=UUID.randomUUID().toString();
        JSONObject request=new JSONObject().put("session_id",session).put("command_id",id).put("kind",kind);
        if(todo!=null)request.put(kind.equals("lab-weather-update")?"weather":"todo",todo);
        persistCommand(request,id);
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<deadline){
            JSONObject latest=readSession();
            if(!session.equals(latest.optString("session_id"))||!address.equals(latest.optString("target_address")))throw new IOException("眼镜连接已更换");
            JSONObject rejected=latest.optJSONObject("rejected_command");
            if(rejected!=null&&id.equals(rejected.optString("id"))&&"failed".equals(rejected.optString("status")))throw new IOException("眼镜拒绝本次操作："+rejected.optString("reason"));
            if(standbyReady){
                JSONObject standby=latest.optJSONObject("standby");
                if(standby!=null&&id.equals(standby.optString("control_id"))&&standby.optBoolean("ready"))return latest;
            }
            // A full sync given up because another command took over (stop / standby-off / record-stop)
            // no longer owns last_command: notice it here instead of waiting out the timeout.
            JSONObject full=latest.optJSONObject("lab_todo_full_sync");
            if(full!=null&&id.equals(full.optString("command_id"))&&Arrays.asList("failed","write_unconfirmed").contains(full.optString("phase")))
                throw new IOException(full.optString("issue").equals("too_many_items")?"手机未完成待办超过 50 条，暂不支持整表同步":"眼镜待办同步未完成（"+full.optString("issue")+"），请重试");
            JSONObject command=latest.optJSONObject("last_command");
            if(command!=null&&id.equals(command.optString("id"))&&!"pending".equals(command.optString("status"))){
                if(!"completed".equals(command.optString("status"))){
                    JSONObject sync=latest.optJSONObject("lab_todo_sync");
                    if(sync!=null&&id.equals(sync.optString("command_id"))){
                        if("mapped_target_not_found".equals(sync.optString("issue")))throw new IOException("眼镜列表中已无此项；完成后恢复同步暂未开放");
                        if("completed_local_not_in_pending_list".equals(sync.optString("issue")))throw new IOException("已完成的手机任务无需新增到眼镜待办");
                    }
                    throw new IOException("本次眼镜操作未确认，请刷新列表核对");
                }
                return latest;
            }
            if(!isLive(latest))throw new IOException("眼镜连接已结束");
            Thread.sleep(250);
        }
        throw new IOException("等待眼镜读回超时，未自动重试");
    }
    private Snapshot querySnapshot(JSONObject state,String session,String address)throws Exception{
        JSONObject summary=state.optJSONObject("lab_todo_query");
        if(summary==null||!"response_received".equals(summary.optString("phase")))throw new IOException("眼镜待办未完整返回");
        JSONObject receipt=readReceipt(summary.getString("receipt_path"));
        JSONObject command=state.optJSONObject("last_command");String id=summary.optString("command_id");
        if(command==null||!id.equals(command.optString("id"))||!id.equals(receipt.optString("command_id"))||!session.equals(receipt.optString("session_id"))||!address.equals(receipt.optString("target_address")))throw new IOException("待办收据不属于本次当前眼镜操作");
        JSONArray replies=receipt.getJSONArray("replies"),flat=new JSONArray();
        for(int page=0;page<replies.length();page++){
            JSONArray rows=replies.getJSONObject(page).getJSONArray("dataList");for(int i=0;i<rows.length();i++)flat.put(rows.getJSONObject(i));
        }
        if(flat.length()!=summary.optInt("items")||flat.length()!=summary.optInt("todo_total"))throw new IOException("眼镜待办数量不完整");
        return snapshot(session,address,flat);
    }
    private Snapshot fullSnapshot(JSONObject state,String session,String address)throws Exception{
        JSONObject summary=state.optJSONObject("lab_todo_full_sync");
        if(summary==null||!summary.optBoolean("verified")||!"verified".equals(summary.optString("phase")))throw new IOException("眼镜待办同步没有通过读回核对");
        JSONObject receipt=readReceipt(summary.getString("receipt_path"));
        JSONObject command=state.optJSONObject("last_command");String id=summary.optString("command_id");
        if(command==null||!id.equals(command.optString("id"))||!id.equals(receipt.optString("command_id"))||!session.equals(receipt.optString("session_id"))||!address.equals(receipt.optString("target_address"))||!receipt.optBoolean("readback_verified"))throw new IOException("同步收据不属于本次当前眼镜操作或未确认");
        Snapshot value=snapshot(session,address,receipt.getJSONArray("after"));
        int adopted=receipt.optInt("adopted"),completed=receipt.optInt("completed_on_glasses");
        value.effect=(adopted>0?"；收下眼镜原有 "+adopted+" 条":"")+(completed>0?"；眼镜上完成 "+completed+" 条":"");
        return value;
    }
    private Snapshot syncSnapshot(JSONObject state,String session,String address)throws Exception{
        JSONObject summary=state.optJSONObject("lab_todo_sync");
        if(summary==null||!summary.optBoolean("verified")||!"verified".equals(summary.optString("phase")))throw new IOException("眼镜写入没有通过读回核对");
        JSONObject receipt=readReceipt(summary.getString("receipt_path"));
        JSONObject command=state.optJSONObject("last_command");String id=summary.optString("command_id");
        if(command==null||!id.equals(command.optString("id"))||!id.equals(receipt.optString("command_id"))||!session.equals(receipt.optString("session_id"))||!address.equals(receipt.optString("target_address"))||!receipt.optBoolean("readback_verified"))throw new IOException("同步收据不属于本次当前眼镜操作或未确认");
        Snapshot value=snapshot(session,address,receipt.getJSONArray("after"));value.effect=receipt.optString("verified_effect");return value;
    }
    private Snapshot snapshot(String session,String address,JSONArray rows)throws Exception{
        List<JSONObject> items=new ArrayList<>();Set<Long> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject item=LabTodoSync.copyItem(rows.getJSONObject(i));long id=LabTodoSync.integer(item,"eventID");
            if(!ids.add(id))throw new IOException("眼镜返回重复待办");items.add(item);
        }
        return new Snapshot(session,address,items);
    }
    private JSONObject readReceipt(String relative)throws Exception{
        if(relative==null||!relative.matches("(?:lab-todo|todo-sync)/[A-Za-z0-9_-]{1,128}\\.json"))throw new IOException("待办收据路径无效");
        return readJson(new File(context.getFilesDir(),relative),1048576);
    }
    private JSONObject readSession()throws Exception{return readJson(new File(context.getFilesDir(),"result.json"),262144);}
    private JSONObject readJson(File file,int limit)throws Exception{
        if(!file.isFile()||file.length()>limit)throw new IOException("状态文件不可用");
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>limit)throw new IOException("状态文件过大");}}
        return new JSONObject(out.toString("UTF-8"));
    }
    private boolean isLive(JSONObject value){return value.optInt("pid")==android.os.Process.myPid()&&"sdk_session_ready".equals(value.optString("status"))&&value.optBoolean("auth_success_callback");}
    private void requireLive(JSONObject value)throws Exception{
        if(!"dev.xr.rayneo.sdklab".equals(context.getPackageName()))throw new IOException("眼镜待办同步目前仅在SDK Lab开放");
        if(!isLive(value)||!value.optBoolean("connection_ready"))throw new IOException("请先连接并完成眼镜配对");
    }
    /** Why a glasses operation would be refused right now, or null when it would not (or the session
     * cannot be read -- the real attempt reports that). One local file read; safe to poll. */
    String busyReason(){
        JSONObject session;try{session=readSession();}catch(Exception unreadable){return null;}
        try{ensureIdle(session);return null;}catch(Exception busy){String m=busy.getMessage();return m==null||m.isEmpty()?null:m;}
    }
    private void ensureIdle(JSONObject value)throws Exception{
        JSONObject command=value.optJSONObject("last_command"),recording=value.optJSONObject("recording"),voice=value.optJSONObject("voice_test"),reading=value.optJSONObject("lab_native_reading");
        if(command!=null&&"pending".equals(command.optString("status")))throw new IOException("上一项眼镜操作尚未完成");
        if(recording!=null&&Arrays.asList("connecting","starting","recording","stopping","saving").contains(recording.optString("phase")))throw new IOException("请先结束当前录音");
        if(voice!=null&&Arrays.asList("preparing","recording","transcribing","answering","sending_answer").contains(voice.optString("phase")))throw new IOException("请先结束当前语音问答");
        if(reading!=null&&"awaiting_user_exit".equals(reading.optString("state")))throw new IOException("请先退出眼镜当前阅读页");
    }
    private void persistCommand(JSONObject request,String id)throws Exception{
        File target=new File(context.getFilesDir(),"session-command.json"),temp=new File(context.getFilesDir(),"session-command.todo-"+id+".tmp");
        if(temp.exists()&&!temp.delete())throw new IOException("无法清理旧命令草稿");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(request.toString().getBytes("UTF-8"));out.getFD().sync();}
        if(target.exists()||!temp.renameTo(target)){temp.delete();throw new IOException("上一项眼镜操作尚未取走");}
    }
    private String userMessage(Exception e){String value=e.getMessage();return value==null||value.isEmpty()?"眼镜待办操作失败":value;}
    private void postProgress(Callback callback,String value){if(!closed)main.post(()->{if(!closed)callback.progress(value);});}
    private void postComplete(Callback callback,Snapshot value,String message){if(!closed)main.post(()->{if(!closed)callback.complete(value,message);});}
    private void postFailed(Callback callback,String message){if(!closed)main.post(()->{if(!closed)callback.failed(message);});}
    @Override public void close(){closed=true;}
}
