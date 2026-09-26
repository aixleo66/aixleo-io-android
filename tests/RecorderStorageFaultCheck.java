package dev.xr.rayneo.probe;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/**
 * Exercises actual file failures through the production recorder.
 * Production GlassesRecorder + RecordingFile; Android scheduling/codec are the existing stubs.
 * Real faults: directory obstructs receipt.tmp, or an already-open RandomAccessFile is closed.
 * Neither case claims to simulate ENOSPC, fsync failure, Android rename, or a physical device.
 */
final class RecorderStorageFaultCheck {
    static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    static Object get(Object target,String name)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static String sha(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format("%02x",b&255));return s.toString();
    }
    static BusinessEnvelope block(String id,int offset,byte value)throws Exception {
        Constructor<BusinessEnvelope> c=BusinessEnvelope.class.getDeclaredConstructor(int.class,String.class,int.class,byte[].class);
        c.setAccessible(true);byte[] bytes=new byte[240];Arrays.fill(bytes,value);
        return c.newInstance(3,new JSONObject().put("uuid",id).put("offset",offset).toString(),240,bytes);
    }
    static void until(Handler handler,BooleanSupplier ready,String failure)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!ready.getAsBoolean()) {
            handler.runReady();
            if(System.nanoTime()>deadline)throw new AssertionError(failure);
            Thread.sleep(1);
        }
    }
    static String fileKind(File f) {return f.isDirectory()?"directory":f.isFile()?"file":f.exists()?"other":"absent";}
    static JSONObject readJson(File f)throws Exception {
        return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
    }
    static void run(String scenario,File base)throws Exception {
        boolean receiptFault=scenario.equals("receipt-tmp-directory");
        check(receiptFault||scenario.equals("closed-receiver"),"unknown scenario");
        File files=new File(base,scenario);
        check(files.mkdirs(),"scenario requires a new empty directory: "+files);
        // lastFlush starts at zero. Keep the first append below the periodic-flush threshold,
        // so Windows rename-over-existing cannot cause a different fault before injection.
        SystemClock.now=0;
        Handler h=new Handler();
        List<String> phases=Collections.synchronizedList(new ArrayList<>());
        List<String> sent=Collections.synchronizedList(new ArrayList<>());
        GlassesRecorder r=new GlassesRecorder(new Context(files),h,new GlassesRecorder.Host() {
            public int recordingCapMinutes(){return 5;}
            public void send(int type,JSONObject body,String id) {sent.add(type+":"+id);}
            public void changed(JSONObject state) {phases.add(state.optString("phase"));}
        });
        ThreadPoolExecutor io=(ThreadPoolExecutor)get(r,"io");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        RecordingFile receiver=null;
        try {
            r.start();
            String id=r.state().getString("id");
            until(h,()->sent.contains("1:rec-start-"+id),"start was not submitted before injection: "+r.state());
            check(r.state().optString("phase").equals("starting"),"startup failed before target fault");
            r.event(block(id,0,(byte)7));
            until(h,()->r.state().optInt("bytes")==240,"first block not received before target fault: "+r.state());
            // Barrier follows the first append on the *real* executor, and holds it while
            // inspecting/injecting. No synthetic receipt, state-machine copy or deleted receipt.
            io.execute(()-> {entered.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            check(entered.await(4,TimeUnit.SECONDS),"worker barrier not reached");
            check(r.state().optString("phase").equals("recording")&&r.captures(),"not recording at injection boundary");
            receiver=(RecordingFile)get(r,"receiver");
            RandomAccessFile fd=(RandomAccessFile)get(receiver,"file");
            check(fd.getFD().valid(),"raw handle not open before injection");
            File folder=new File(files,"recordings/"+id);
            File raw=new File(folder,"source.rawopus"),receipt=new File(folder,"receipt.json"),tmp=new File(folder,"receipt.tmp");
            byte[] prefix=Files.readAllBytes(raw.toPath());
            check(prefix.length==240,"first-block raw length");
            for(byte b:prefix)check(b==7,"first-block content");
            check(receipt.isFile()&&!tmp.exists(),"startup receipt publication incomplete before target fault");
            byte[] receiptBefore=Files.readAllBytes(receipt.toPath());
            JSONObject before=readJson(receipt);
            check(!before.optString("phase").equals("saved"),"pre-injection receipt must not be saved");
            if(receiptFault) {
                check(tmp.mkdir(),"could not inject receipt.tmp directory collision");
                // Second append now performs real sync and attempts FileOutputStream(tmp).
                // The directory obstruction fails at opening tmp, before any rename operation.
                SystemClock.now=1001;
            } else {
                receiver.close();
                check(!fd.getFD().valid(),"closed-receiver injection did not close real handle");
                check(SystemClock.now==0,"unexpected pre-injection clock advance");
            }
            r.event(block(id,240,(byte)8));
            check(io.getQueue().size()==1,"expected exactly the injected second append behind barrier");
            release.countDown();
            until(h,()->r.state().optString("phase").equals("failed"),"target storage fault did not become failed: "+r.state());
            check(io.isShutdown(),"storage failure did not initiate cleanup");
            check(io.awaitTermination(4,TimeUnit.SECONDS),"storage failure did not terminate worker");
            h.runReady();
            check(r.state().optString("phase").equals("failed")&&!r.busy()&&!r.captures(),"failed capture resurrected");
            check(sent.contains("4:rec-emergency-stop-"+id),"missing emergency stop attempt");
            check(!phases.contains("saved"),"storage fault published successful saved state");
            check(!new File(folder,"recording.wav").exists()&&!new File(folder,"recording.ogg").exists(),"storage fault produced a completed WAV");
            check(!fd.getFD().valid(),"raw descriptor remained open after cleanup");
            byte[] after=Files.readAllBytes(raw.toPath());
            check(Arrays.equals(prefix,Arrays.copyOf(after,prefix.length)),"existing original prefix was changed");
            if(receiptFault) {
                check(after.length==480,"receipt failure must retain the accepted second block");
                for(int i=240;i<480;i++)check(after[i]==8,"accepted second-block content changed");
                check(tmp.isDirectory(),"receipt fault fixture was removed or bypassed");
                check(Arrays.equals(receiptBefore,Files.readAllBytes(receipt.toPath())),"obstructed receipt unexpectedly replaced");
            } else {
                check(Arrays.equals(prefix,after),"closed raw handle wrote or truncated source");
            }
            JSONObject disk=readJson(receipt);
            check(!disk.optString("phase").equals("saved"),"persistent receipt falsely claims saved");
            // Windows renameTo(existing target) may leave the old receipt while termination
            // leaves failed JSON in receipt.tmp; Android/Linux may replace successfully.
            // This is observed, NOT used as the injected fault or normalized by deleting files.
            JSONObject evidence=new JSONObject()
                .put("scenario",scenario).put("runtime",r.state())
                .put("published_phases",new JSONArray(phases)).put("sent",new JSONArray(sent))
                .put("raw_before_bytes",prefix.length).put("raw_after_bytes",after.length)
                .put("raw_before_sha256",sha(prefix)).put("raw_after_sha256",sha(after))
                .put("prefix_preserved",true).put("raw_fd_valid",fd.getFD().valid())
                .put("worker_terminated",io.isTerminated()).put("wav_exists",false)
                .put("receipt_before",before).put("receipt_after",disk)
                .put("receipt_before_sha256",sha(receiptBefore))
                .put("receipt_after_sha256",sha(Files.readAllBytes(receipt.toPath())))
                .put("persistent_failure_receipt",disk.optString("phase").equals("failed"))
                .put("receipt_tmp_kind",fileKind(tmp));
            if(tmp.isFile())evidence.put("receipt_tmp",readJson(tmp));
            Files.write(new File(files,"storage-fault-evidence.json").toPath(),evidence.toString().getBytes(StandardCharsets.UTF_8));
            // Advance through pending recorder deadlines: failed must remain terminal.
            h.advance(300001);
            check(r.state().optString("phase").equals("failed")&&!phases.contains("saved"),"late deadline resurrected capture");
            System.out.println(evidence.toString());
            System.out.println("Recorder storage fault "+scenario+" passed; persistent_failed_receipt="+disk.optString("phase").equals("failed"));
        } finally {
            release.countDown();r.close();
            boolean terminated=io.awaitTermination(4,TimeUnit.SECONDS);
            if(receiver!=null)receiver.close();
            check(terminated,"fixture cleanup left a recorder worker running");
        }
    }
    public static void main(String[] args)throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        check(args.length>=2,"usage: scenario|all new-files-directory");
        File base=new File(args[1]);if(!base.exists())check(base.mkdirs(),"could not create test directory");
        if(args[0].equals("all")||args[0].isEmpty()) {
            run("receipt-tmp-directory",base);run("closed-receiver",base);
        } else run(args[0],base);
    }
}