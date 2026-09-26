package dev.xr.rayneo.probe;
import java.io.*;
import java.nio.file.*;
import org.json.*;
public final class VoiceAnswerArchiveCheck {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static JSONObject round(String job,String provider)throws Exception{return new JSONObject().put("session_id","session-A").put("job_id",job).put("provider",provider).put("status","pending").put("asr",new JSONObject().put("text","测试问题"));}
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);
        JSONObject first=round("round-first","knowledge").put("knowledge_token","never-save");
        VoiceAnswerArchive.save(root,first);
        check(!Files.readString(new File(root,"session-A--round-first.pending.json").toPath()).contains("never-save"));
        JSONObject failure=VoiceAnswerArchive.failed(first,"session-A","round-first","服务失败",true);
        check(failure.getJSONObject("asr").getString("text").equals("测试问题"));check(failure.getString("provider").equals("knowledge"));
        VoiceAnswerArchive.save(root,failure);File done=new File(root,"session-A--round-first.json");String frozen=Files.readString(done.toPath());
        VoiceAnswerArchive.save(root,round("round-first","deepseek").put("status","failed").put("error","late"));check(frozen.equals(Files.readString(done.toPath())));
        check(!new File(root,"session-A--round-first.pending.json").exists());
        JSONObject other=VoiceAnswerArchive.failed(first,"session-B","round-first","new session",false);check(!other.has("asr")&&!other.has("provider"));
        other=VoiceAnswerArchive.failed(first,"session-A","other","new round",false);check(!other.has("asr"));
        done.setLastModified(1000);
        VoiceAnswerArchive.save(root,round("active","knowledge"));
        for(int i=0;i<21;i++){
            String id="r"+i;JSONObject v=round(id,i%2==0?"deepseek":"knowledge").put("status","completed").put("text","answer-"+i)
                .put("delivery",new JSONObject().put("status","completed").put("lens_verified",false));
            VoiceAnswerArchive.save(root,v);new File(root,"session-A--"+id+".json").setLastModified(2000+i);
        }
        check(!done.exists());check(!new File(root,"session-A--r0.json").exists());check(new File(root,"session-A--active.pending.json").exists());
        check(root.listFiles(f->f.getName().endsWith(".json")&&!f.getName().endsWith(".pending.json")).length==20);
        VoiceAnswerArchive.save(root,round("../escape","knowledge"));check(!new File(root.getParentFile(),"escape.json").exists());
        System.out.println("voice answer archive checks passed");
    }
}
