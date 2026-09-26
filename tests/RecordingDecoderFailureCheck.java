package dev.xr.rayneo.probe;

import android.media.MediaCodec;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs the production decoder; no receipt manipulation and no actual codec decoding. */
final class RecordingDecoderFailureCheck {
    static void check(boolean ok,String why) {if(!ok)throw new AssertionError(why);}
    static String sha(byte[] bytes)throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))
            s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    static final class Result {
        volatile Throwable error;
        volatile Long successDuration;
        volatile boolean interruptedAtExit;
    }
    static void decode(File raw,File wav,Result result) {
        try {result.successDuration=RecordingDecoder.decode(raw,wav,false);}
        catch(Throwable error) {result.error=error;}
        finally {result.interruptedAtExit=Thread.currentThread().isInterrupted();}
    }
    static String quote(String s) {
        StringBuilder out=new StringBuilder("\"");
        for(char c:s.toCharArray()) {
            if(c=='"'||c=='\\')out.append('\\').append(c);
            else if(c=='\n')out.append("\\n");else if(c=='\r')out.append("\\r");
            else if(c=='\t')out.append("\\t");
            else if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
        }
        return out.append('"').toString();
    }
    static String json(Map<String,Object> fields) {
        StringBuilder out=new StringBuilder("{");boolean first=true;
        for(Map.Entry<String,Object> e:fields.entrySet()) {
            if(!first)out.append(',');first=false;out.append(quote(e.getKey())).append(':');
            Object v=e.getValue();out.append(v==null?"null":v instanceof Boolean||v instanceof Number?v.toString():quote(v.toString()));
        }
        return out.append('}').toString();
    }
    static void run(String scenario,File base)throws Exception {
        check(Arrays.asList("deadline","interrupt","existing-wav","configure-failure").contains(scenario),"Unknown scenario");
        File folder=new File(base,scenario);check(folder.mkdirs(),"Use a fresh directory, do not overwrite old evidence");
        File raw=new File(folder,"source.rawopus"),wav=new File(folder,"recording.wav");
        byte[] input=new byte[240];Arrays.fill(input,(byte)7);input[0]=(byte)0xf8;
        Files.write(raw.toPath(),input);String before=sha(input);
        byte[] sentinel="KEEP-EXISTING-DERIVATIVE".getBytes(StandardCharsets.US_ASCII);
        if(scenario.equals("existing-wav"))Files.write(wav.toPath(),sentinel);
        MediaCodec.reset(scenario);Result result=new Result();
        check(!Thread.currentThread().isInterrupted(),"Test main thread unexpectedly interrupted");
        if(scenario.equals("interrupt")) {
            Thread worker=new Thread(()->decode(raw,wav,result),"decoder-interrupt-fixture");
            worker.setDaemon(true);worker.start();
            try {
                check(MediaCodec.outputEntered.await(2,TimeUnit.SECONDS),"Decoder did not reach the controlled platform poll");
                worker.interrupt();MediaCodec.allowOutputReturn.set(true);worker.join(2000);
                check(!worker.isAlive(),"Interrupted decoder did not terminate");
            } finally {
                MediaCodec.allowOutputReturn.set(true);
                if(worker.isAlive()){worker.interrupt();worker.join(1000);}
            }
            check(result.interruptedAtExit,"Platform fixture cleared the interrupted flag");
            check(SystemClock.now==0,"Interruption accidentally relied on advancing virtual time");
            check(!Thread.currentThread().isInterrupted(),"Fixture interrupted the test controller");
        } else decode(raw,wav,result);
        check(result.successDuration==null,"Failure case returned successful decode duration");
        check(result.error!=null,"No failure observed");
        check(sha(Files.readAllBytes(raw.toPath())).equals(before),"Decoder modified the raw source");
        MediaCodec codec=MediaCodec.last;
        if(scenario.equals("existing-wav")) {
            check(result.error instanceof IOException,"Existing WAV was not rejected as an IO failure");
            check(MediaCodec.creates==0&&codec==null,"Decoder allocated codec before rejecting an existing output");
            check(Arrays.equals(sentinel,Files.readAllBytes(wav.toPath())),"Existing derivative was overwritten");
        } else {
            check(MediaCodec.creates==1&&codec!=null,"Expected one allocated codec");
            check(codec.stopped==1&&codec.released==1,"Failure did not stop/release codec exactly once");
            byte[] leftover=Files.readAllBytes(wav.toPath());
            check(leftover.length==44,"Failure output is not the expected incomplete placeholder");
            for(byte b:leftover)check(b==0,"Failure produced a finalized header or unexpected PCM");
            if(scenario.equals("configure-failure")) {
                check(result.error instanceof IllegalStateException&&"injected-configure-failure".equals(result.error.getMessage()),"Wrong injected failure");
                check(codec.configured==1&&codec.started==0&&codec.inputPolls==0,"Configure failure reached later decode stages");
            } else {
                check(result.error instanceof IOException&&"Decoder timeout".equals(result.error.getMessage()),"Did not hit the production loop guard");
                check(codec.configured==1&&codec.started==1,"Decoder never started");
                if(scenario.equals("deadline")) {
                    check(SystemClock.now==120001,"Wrong timeout clock endpoint");
                    check(codec.inputPollTimes.equals(Arrays.asList(0L,119999L,120000L)),"Deadline was enforced too early or too late");
                    check(codec.outputPollTimes.equals(Arrays.asList(0L,119999L,120000L)),"Unexpected output polling boundary");
                } else check(codec.inputPolls==1&&codec.outputPolls==1,"Interrupt did not exit at the next real loop guard");
            }
        }
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("scenario",scenario);evidence.put("production_path","reference");
        evidence.put("codec_is_platform_stub",true);evidence.put("actual_audio_decoding_tested",false);
        evidence.put("error_class",result.error.getClass().getName());evidence.put("error_message",result.error.getMessage());
        evidence.put("returned_success",false);evidence.put("worker_interrupted_at_exit",result.interruptedAtExit);
        evidence.put("virtual_elapsed_ms",SystemClock.now);evidence.put("codec_created",MediaCodec.creates);
        evidence.put("codec_configure_calls",codec==null?0:codec.configured);evidence.put("codec_start_calls",codec==null?0:codec.started);
        evidence.put("codec_stop_calls",codec==null?0:codec.stopped);evidence.put("codec_release_calls",codec==null?0:codec.released);
        evidence.put("input_poll_times",codec==null?"[]":codec.inputPollTimes.toString());
        evidence.put("output_poll_times",codec==null?"[]":codec.outputPollTimes.toString());
        evidence.put("raw_sha256_before",before);evidence.put("raw_sha256_after",sha(Files.readAllBytes(raw.toPath())));
        evidence.put("wav_exists",wav.exists());evidence.put("wav_bytes",wav.length());
        evidence.put("wav_sha256",sha(Files.readAllBytes(wav.toPath())));
        evidence.put("existing_wav_preserved",scenario.equals("existing-wav"));
        String report=json(evidence);Files.write(new File(folder,"decoder-evidence.json").toPath(),report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);System.out.println("Production decoder failure "+scenario+" passed");
    }
    public static void main(String[] args)throws Exception {
        check(args.length>=2,"usage: scenario|all fresh-files-directory");File base=new File(args[1]);
        if(!base.exists())check(base.mkdirs(),"Could not create fixture directory");
        if(args[0].equals("all"))for(String s:new String[]{"deadline","interrupt","existing-wav","configure-failure"})run(s,base);
        else run(args[0],base);
    }
}
