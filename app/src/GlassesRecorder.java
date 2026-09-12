package dev.xr.rayneo.probe;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One recording task. Raw bytes stay local; cloud services are not involved. */
final class GlassesRecorder {
    interface Host {void send(int type,JSONObject body,String id)throws Exception;void changed(JSONObject state);}
    private final Context context;private final Handler main;private final Host host;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128));
    private String id;private File folder;private RecordingFile receiver;private long started,ackAt;private volatile String phase="idle",error="";
    private volatile boolean receiving,completed;private boolean finalizing,closed;private volatile int bytes;private volatile String coverage="";private volatile long duration;private long lastFlush;
    GlassesRecorder(Context c,Handler h,Host host){context=c;main=h;this.host=host;}
    boolean busy(){return id!=null&&!phase.equals("saved")&&!phase.equals("failed");}
    boolean captures(){return receiving;}
    JSONObject state(){JSONObject j=new JSONObject();try{j.put("id",id==null?"":id).put("phase",phase).put("bytes",bytes).put("coverage",coverage).put("created_at_ms",started).put("confirmed_at_ms",ackAt).put("duration_ms",duration).put("completed_received",completed).put("error",error).put("audio_uploaded",false);}catch(Exception ignored){}return j;}
    private void publish(){host.changed(state());}
    private void submit(Runnable task){try{io.execute(task);}catch(RejectedExecutionException e){fail("接收队列已满，原件保留；本次文件不能标为完整");}}
    private void manifest()throws Exception{
        File tmp=new File(folder,"receipt.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(state().toString().getBytes("UTF-8"));out.getFD().sync();}
        if(!tmp.renameTo(new File(folder,"receipt.json")))throw new IOException("Manifest rename");
    }
    void start()throws Exception{
        if(busy()||closed)throw new IOException("Recorder busy");
        File root=new File(context.getFilesDir(),"recordings");if(!root.exists()&&!root.mkdirs())throw new IOException("Storage unavailable");
        if(root.getUsableSpace()<128L*1024*1024)throw new IOException("请至少留出 128 MB 手机空间");
        id=UUID.randomUUID().toString();folder=new File(root,id);if(!folder.mkdir())throw new IOException("Create recording directory");
        started=System.currentTimeMillis();ackAt=0;bytes=0;coverage="";duration=0;error="";completed=false;finalizing=false;phase="starting";receiving=true;publish();
        final String current=id;
        submit(()->{try{receiver=new RecordingFile(new File(folder,"source.rawopus"));manifest();main.post(()->{if(!current.equals(id)||closed||!receiving)return;if(!phase.equals("starting")){fail("开始录音已取消");return;}try{
            host.send(14,new JSONObject().put("isAppForeground",true),"rec-foreground-"+current);
            // Official Android startRecording: type=1, mode=1, sid in epoch seconds.
            // code belongs to the response, not an app-initiated start request.
            host.send(1,new JSONObject().put("uuid",current).put("sid",String.valueOf(started/1000)).put("action",1).put("type",1).put("mode",1),"rec-start-"+current);
            main.postDelayed(()->{if(current.equals(id)&&phase.equals("starting")){try{stop();}catch(Exception ignored){}fail("眼镜未确认开始；停止请求已尝试，原件保留");}},15000);
            main.postDelayed(()->{if(current.equals(id)&&busy()){try{stop();}catch(Exception e){fail("达到本轮 5 分钟上限，停止请求失败");}}},300000);
        }catch(Exception e){fail("录音开始命令失败");}});}catch(Exception e){main.post(()->fail("无法准备录音存储"));}});
    }
    void stop()throws Exception{
        if(!busy()||phase.equals("saving")||phase.equals("stopping"))return;phase="stopping";publish();
        host.send(4,new JSONObject().put("uuid",id).put("action",1).put("code",2),"rec-stop-"+id);
        String current=id;main.postDelayed(()->{if(current.equals(id)&&busy()&&!finalizing)fail("未收到完整结束回报；原始数据已保留");},30000);
    }
    void event(BusinessEnvelope wire)throws Exception{
        if(id==null)return;JSONObject j=new JSONObject(wire.json);if(!id.equals(j.optString("uuid")))return;
        if(phase.equals("saved")&&wire.type==3&&wire.dataBytes>0){fail("保存后收到迟到数据；文件完整性需复核");return;}
        if(!receiving)return;
        if(wire.type==1&&j.optInt("action")==2){if(j.optInt("code")!=1){fail("眼镜未允许开始录音");return;}if(phase.equals("starting")){phase="recording";ackAt=System.currentTimeMillis();publish();}}
        else if(wire.type==3&&wire.audio!=null){
            if(phase.equals("starting")){phase="recording";ackAt=System.currentTimeMillis();publish();}
            if(finalizing){fail("结束后仍有迟到音频，文件需复核");return;}
            long offset=j.getLong("offset");if(offset<0||offset>RecordingFile.LIMIT)throw new IOException("Offset bounds");
            byte[] block=wire.audio.clone();submit(()->{try{receiver.append((int)offset,block);bytes=receiver.received();coverage=receiver.coverage();long now=SystemClock.elapsedRealtime();if(now-lastFlush>=1000){receiver.sync();manifest();lastFlush=now;main.post(this::publish);}}catch(Exception e){main.post(()->fail("接收数据冲突或写盘失败；原件保留"));}finally{Arrays.fill(block,(byte)0);}});
        }else if(wire.type==4){if(j.optInt("action")==1)host.send(4,new JSONObject().put("uuid",id).put("action",2).put("code",j.optInt("code",2)),"rec-ack-"+id);phase="stopping";publish();}
        else if(wire.type==6&&j.optBoolean("completed")&&!completed){completed=true;phase="saving";publish();String current=id;
            main.postDelayed(()->{if(!current.equals(id)||!receiving)return;finalizing=true;submit(()->{try{receiver.seal(completed);receiver.close();manifest();long ms=RecordingDecoder.decode(new File(folder,"source.rawopus"),new File(folder,"recording.wav"));
                main.post(()->{if(!current.equals(id)||!receiving)return;duration=ms;phase="saved";receiving=false;submit(()->{try{manifest();main.post(this::publish);}catch(Exception e){main.post(()->fail("音频已保存，目录记录写入失败"));}});});
            }catch(Exception e){main.post(()->fail("文件未通过完整性或解码检查；原件保留"));}});},1000);
        }
    }
    void sendFailed(String command){if(id!=null&&command.startsWith("rec-")&&command.endsWith(id))fail("录音控制发送失败；未确认眼镜状态");}
    void fail(String reason){if(phase.equals("failed"))return;boolean wasReceiving=receiving;phase="failed";error=reason;receiving=false;publish();if(wasReceiving)try{host.send(4,new JSONObject().put("uuid",id).put("action",1).put("code",2),"rec-emergency-stop-"+id);}catch(Exception ignored){}try{io.execute(()->{try{if(receiver!=null)receiver.close();if(folder!=null)manifest();}catch(Exception ignored){}});}catch(Exception ignored){}}
    void close(){if(closed)return;closed=true;if(busy()){try{stop();}catch(Exception ignored){}fail("连接会话结束，已收到的原件保留");}io.shutdown();}
}
