package dev.xr.rayneo.probe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Bounded, app-private voice answer snapshots; never stores service configuration. */
final class VoiceAnswerArchive {
    private static final int KEEP = 20;
    static void save(File root, JSONObject value) throws Exception {
        String session=value.optString("session_id"), job=value.optString("job_id");
        if(!valid(session)||!valid(job))return;
        JSONObject safe=new JSONObject();
        for(String key:new String[]{"session_id","job_id","status","provider","asr","answer","text","error",
                "audio_source","asr_mode","audio_saved","audio_uploaded","upload_started","sample_rate","duration_ms",
                "lens_verified","delivery","query_state","query_started_elapsed_ms"})
            if(value.has(key))safe.put(key,value.opt(key));
        JSONObject delivery=value.optJSONObject("delivery");
        boolean terminal="failed".equals(value.optString("status"))||"cancelled".equals(value.optString("status"))
            ||(delivery!=null&&("completed".equals(delivery.optString("status"))||"failed".equals(delivery.optString("status"))));
        safe.put("archive_terminal",terminal).put("retention","latest_20_terminated_voice_rounds_plus_active");
        byte[] bytes=safe.toString(2).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144)throw new IOException("Voice answer receipt too large");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Voice answer directory");
        String stem=session+"--"+job;
        File pending=new File(root,stem+".pending.json"),target=new File(root,stem+(terminal?".json":".pending.json"));
        // Once terminal, late callbacks cannot rewrite that round.
        if(new File(root,stem+".json").exists())return;
        File tmp=new File(root,stem+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);out.getFD().sync();}
        java.nio.file.Files.move(tmp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if(terminal){
            if(pending.exists()&&!pending.delete())throw new IOException("Voice pending cleanup");
            File[] done=root.listFiles(f->f.isFile()&&f.getName().matches("[A-Za-z0-9_-]+--[A-Za-z0-9_-]+\\.json"));
            if(done==null)throw new IOException("Voice archive list");
            Arrays.sort(done,Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
            for(int i=0;i<done.length-KEEP;i++)if(!done[i].delete())throw new IOException("Voice archive retention");
        }
    }
    static JSONObject failed(JSONObject previous,String session,String job,String reason,boolean uploaded)throws Exception{
        JSONObject failure=new JSONObject();
        if(previous!=null&&job.equals(previous.optString("job_id"))&&session.equals(previous.optString("session_id")))
            for(String key:new String[]{"asr","answer","provider","text","sample_rate","duration_ms","delivery","query_state","query_started_elapsed_ms"})
                if(previous.has(key))failure.put(key,previous.opt(key));
        return failure.put("job_id",job).put("session_id",session).put("audio_source","glasses").put("asr_mode","streaming")
            .put("audio_saved",false).put("upload_started",uploaded).put("status","failed").put("error",reason).put("lens_verified",false);
    }
    private static boolean valid(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,128}");}
}
