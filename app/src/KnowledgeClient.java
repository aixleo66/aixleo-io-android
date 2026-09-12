package dev.xr.rayneo.probe;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.*;
import org.json.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

/** One explicit read-only question; no idle connection and no automatic prompt resubmission. */
final class KnowledgeClient {
    private static final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    static URI endpoint(String value)throws Exception{
        URI uri=new URI(value);
        if(!"wss".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null)
            throw new CloudClient.Failure("知识库地址须为 WSS，不要把 Token 放进地址");
        return uri;
    }
    static void validate(JSONObject c)throws Exception{
        String url=c.optString("knowledge_url"),token=c.optString("knowledge_token");
        if(!url.isEmpty())endpoint(url);
        if(token.length()>512||(!token.isEmpty()&&!token.matches("[!-~]+")))throw new CloudClient.Failure("知识库 Token 格式无效");
        if(c.optString("assistant_provider","deepseek").equals("knowledge")&&(url.isEmpty()||token.isEmpty()))
            throw new CloudClient.Failure("请先填写知识库地址和 Token");
    }
    static JSONObject ask(JSONObject config,String prompt,CloudClient.Cancellation cancel)throws Exception{
        cancel.check();validate(config);
        if(prompt.trim().isEmpty()||prompt.length()>2000)throw new CloudClient.Failure("问题须为 1–2000 字");
        if(config.optString("knowledge_token").isEmpty())throw new CloudClient.Failure("请先填写知识库 Token");
        if(!busy.compareAndSet(false,true))throw new CloudClient.Failure("已有知识库问题正在处理，请稍后再试");
        long start=android.os.SystemClock.elapsedRealtime(),deadline=start+150000;
        KnowledgeRunState state=new KnowledgeRunState();JSONObject answer=null,usage=null;
        boolean submitted=false;int reconnects=0;
        try{
            while(true){
                cancel.check();
                ArrayBlockingQueue<String> events=new ArrayBlockingQueue<>(128);
                final boolean[] overflow={false};
                WebSocketClient ws=new WebSocketClient(endpoint(config.getString("knowledge_url")),new Draft_6455(Collections.emptyList(),262144),Collections.emptyMap(),10000){
                    public void onOpen(ServerHandshake h){}
                    public void onMessage(String text){if(text.length()>262144||!events.offer(text)){synchronized(overflow){overflow[0]=true;}close();}}
                    public void onClose(int code,String reason,boolean remote){events.offer("{\"type\":\"transport.closed\",\"code\":"+code+"}");}
                    public void onError(Exception e){events.offer("{\"type\":\"transport.failed\"}");}
                };
                ws.setConnectionLostTimeout(0);
                try{
                    if(!ws.connectBlocking(10,TimeUnit.SECONDS))throw new CloudClient.Failure("知识库连接失败，请检查电脑服务与临时链接");
                    cancel.check();
                    JSONObject hello=new JSONObject().put("type","hello").put("token",config.getString("knowledge_token"));
                    if(state.runId!=null)hello.put("lastRunId",state.runId).put("lastSeq",state.seq);
                    ws.send(hello.toString());boolean synced=false;
                    long syncDeadline=android.os.SystemClock.elapsedRealtime()+10000;
                    while(true){
                        cancel.check();long now=android.os.SystemClock.elapsedRealtime();
                        if(now>deadline)throw new CloudClient.Failure("知识库回答超时，未自动重复提问");
                        if(!synced&&now>syncDeadline)throw new CloudClient.Failure("知识库鉴权超时");
                        synchronized(overflow){if(overflow[0])throw new CloudClient.Failure("知识库事件超过缓冲限制");}
                        String raw=events.poll(200,TimeUnit.MILLISECONDS);if(raw==null)continue;
                        JSONObject packet=new JSONObject(raw);String type=packet.optString("type");
                        if(type.equals("transport.closed")||type.equals("transport.failed")){
                            if(packet.optInt("code")==1008)throw new CloudClient.Failure("知识库鉴权失败，请检查 Token");
                            if(state.runId!=null&&reconnects++<1)break;
                            throw new CloudClient.Failure("知识库连接中断，未自动重复提问");
                        }
                        if(type.equals("sync")){
                            if(synced||!packet.optString("protocol").equals("rokid-harness.v1"))throw new CloudClient.Failure("知识库协议不匹配");
                            synced=true;
                            if(!submitted){ws.send(new JSONObject().put("type","prompt").put("prompt",prompt).put("capability","read").toString());submitted=true;}
                        }else if(type.equals("rejected")){
                            throw new CloudClient.Failure(packet.optString("code").equals("BUSY")?"电脑端正在处理其他问题，请稍后重试":"知识库拒绝了请求，未自动重试");
                        }else if(type.equals("protocolError"))throw new CloudClient.Failure("知识库协议请求失败");
                        else if(type.equals("event")&&synced){
                            JSONObject event=packet.getJSONObject("event");String kind=event.optString("type");
                            if(!state.event(packet.getString("runId"),packet.getLong("seq"),kind))continue;
                            if(kind.equals("error"))throw new CloudClient.Failure("电脑端知识库执行失败，请检查服务日志");
                            if(kind.equals("usage"))usage=event; // Structured usage only; never process narrative events as answers.
                            if(kind.equals("result"))answer=result(event);
                        }else if(type.equals("runEnd")&&synced&&state.completes(packet.optString("runId"),packet.optString("status"))){
                            if(answer==null)throw new CloudClient.Failure("知识库没有完整回答");
                            answer.put("run_id",state.runId).put("last_seq",state.seq).put("elapsed_ms",now-start).put("reconnects",reconnects);
                            if(usage!=null)answer.put("usage",usage);
                            return answer;
                        }
                    }
                }finally{ws.close();}
            }
        }catch(IllegalArgumentException|JSONException e){throw new CloudClient.Failure("知识库返回的数据不完整，未发送到眼镜");}
        finally{busy.set(false);}
    }
    private static JSONObject result(JSONObject event)throws Exception{
        String full=checked(event.getString("displayAnswer"),16000),shortAnswer=checked(event.getString("spokenAnswer"),2000);
        JSONArray sources=event.getJSONArray("sources"),safe=new JSONArray();
        if(sources.length()>50)throw new IllegalArgumentException();
        for(int i=0;i<sources.length();i++){
            JSONObject source=sources.getJSONObject(i);String path=source.getString("path"),title=source.getString("title");
            if(path.length()>1024||title.length()>300||path.startsWith("/")||path.startsWith("\\")||path.matches("^[A-Za-z]:.*"))throw new IllegalArgumentException();
            safe.put(new JSONObject().put("path",path).put("title",title));
        }
        return new JSONObject().put("status","completed").put("provider","knowledge").put("text",full)
            .put("short_answer",shortAnswer).put("sources",safe).put("lens_text",lensText(full,shortAnswer));
    }
    private static String lensText(String full,String shortAnswer){
        if(full.codePointCount(0,full.length())<=500)return full;
        String text=shortAnswer.codePointCount(0,shortAnswer.length())<=470?shortAnswer:shortAnswer.substring(0,shortAnswer.offsetByCodePoints(0,470))+"…";
        return text+"\n完整回答与来源见手机。";
    }
    private static String checked(String text,int limit){
        String value=text.trim();if(value.isEmpty()||value.codePointCount(0,value.length())>limit)throw new IllegalArgumentException();
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i))&&value.charAt(i)!='\n'&&value.charAt(i)!='\t')throw new IllegalArgumentException();
        return value;
    }
}
