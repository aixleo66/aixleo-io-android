package dev.xr.rayneo.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.SystemClock;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Re-decodes this lab's saved recordings only; no Bluetooth or cloud operations. */
final class SdkLabDecodeBenchmark {
    private static volatile boolean running;
    static void select(Activity activity,Consumer<String> status){
        if(!"dev.xr.rayneo.sdklab".equals(activity.getPackageName())||running)return;
        List<File> choices=new ArrayList<>();List<String> labels=new ArrayList<>();
        File[] folders=new File(activity.getFilesDir(),"recordings").listFiles();
        if(folders!=null)for(File folder:folders)try{
            if(!folder.getName().matches("[A-Za-z0-9_-]{1,128}"))continue;
            JSONObject receipt=new JSONObject(new String(read(new File(folder,"receipt.json")),"UTF-8"));
            if(!"saved".equals(receipt.optString("phase"))||!receipt.optBoolean("completed_received"))continue;
            if(!new File(folder,"source.rawopus").isFile()||!new File(folder,"recording.wav").isFile())continue;
            choices.add(folder);labels.add(String.format(Locale.ROOT,"%.1f 秒 · %s",receipt.optLong("duration_ms")/1000.0,folder.getName()));
        }catch(Exception ignored){}
        if(choices.isEmpty()){status.accept("本调试包尚无完整录音可供验证。");return;}
        new AlertDialog.Builder(activity).setTitle("选择已保存录音：参考与候选解码对照")
            .setItems(labels.toArray(new String[0]),(dialog,index)->run(activity,choices.get(index),status))
            .setNegativeButton("取消",null).show();
    }
    private static byte[] read(File file)throws IOException{
        if(file.length()>65536)throw new IOException("Metadata too large");
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }return out.toByteArray();
    }
    private static String sha(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);
        }StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static void write(File folder,JSONObject result)throws Exception{
        File tmp=new File(folder,"result.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(result.toString(2).getBytes("UTF-8"));out.getFD().sync();
        }if(!tmp.renameTo(new File(folder,"result.json")))throw new IOException("Benchmark result rename");
    }
    private static synchronized void run(Activity activity,File source,Consumer<String> status){
        if(running)return;running=true;
        status.accept("正在用已保存原件进行解码对照，不录音、不连接眼镜、不访问云端。结果保存在本调试包。");
        File files=activity.getFilesDir();
        new Thread(()->{
            JSONObject result=new JSONObject();File folder=new File(new File(files,"sdk-lab-decode"),UUID.randomUUID().toString());
            try{
                if(!folder.mkdirs())throw new IOException("Cannot create fresh benchmark directory");
                File raw=new File(source,"source.rawopus"), originalWav=new File(source,"recording.wav");
                if(raw.length()<=0||raw.length()>RecordingFile.LIMIT||raw.length()%240!=0)throw new IOException("Invalid source bounds");
                long expected=raw.length()/240*960*4+44;
                if(files.getUsableSpace()<expected*2+32L*1024*1024)throw new IOException("Insufficient benchmark space");
                String sourceHash=sha(raw),originalHash=sha(originalWav);
                result.put("package","dev.xr.rayneo.sdklab").put("recording_id",source.getName())
                    .put("source_sha256",sourceHash).put("original_wav_sha256",originalHash).put("status","running")
                    .put("order","reference_then_candidate").put("results",new JSONArray());write(folder,result);
                for(int i=0;i<2;i++){
                    boolean candidate=i==1;File wav=new File(folder,candidate?"candidate.wav":"reference.wav");
                    long began=SystemClock.elapsedRealtime();long[] timing=new long[9];long duration=RecordingDecoder.decode(raw,wav,candidate,timing);
                    long elapsed=SystemClock.elapsedRealtime()-began;String hash=sha(wav);
                    String[] names={"input_dequeue","packet_read_validate","input_copy_queue","output_dequeue","output_copy_trim","pcm_write","output_clear_release","idle_sleep","fsync"};JSONObject times=new JSONObject();for(int t=0;t<names.length;t++)times.put(names[t]+"_ms",timing[t]/1000000.0);
                    result.getJSONArray("results").put(new JSONObject().put("candidate",candidate).put("elapsed_ms",elapsed)
                        .put("timing",times).put("duration_ms",duration).put("bytes",wav.length()).put("sha256",hash).put("matches_original",hash.equals(originalHash)));
                    write(folder,result);
                }
                boolean unchanged=sourceHash.equals(sha(raw))&&originalHash.equals(sha(originalWav));
                boolean match=unchanged;for(int i=0;i<2;i++)match&=result.getJSONArray("results").getJSONObject(i).getBoolean("matches_original");
                result.put("source_unchanged",unchanged).put("status",match?"passed":"mismatch");write(folder,result);
                activity.runOnUiThread(()->status.accept("解码对照结束："+result.optString("status")+"\nfiles/sdk-lab-decode/"+folder.getName()+"/result.json"));
            }catch(Exception e){
                try{result.put("status","failed").put("error",e.getClass().getSimpleName()+": "+e.getMessage());if(folder.isDirectory())write(folder,result);}catch(Exception ignored){}
                activity.runOnUiThread(()->status.accept("解码对照失败，原始录音保留。"));
            }finally{running=false;}
        },"sdk-lab-decode-benchmark").start();
    }
}
