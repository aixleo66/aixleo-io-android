package dev.xr.rayneo.probe;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class CloudPipelineCheck {
    interface Attempt { void run() throws Exception; }
    static void cancelled(Attempt run) throws Exception {
        try { run.run(); throw new AssertionError("Cancelled pipeline continued"); }
        catch (InterruptedException expected) {}
    }
    static void equal(int a,int b){if(a!=b)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args) throws Exception {
        AtomicInteger reads=new AtomicInteger(), uploads=new AtomicInteger(), models=new AtomicInteger();
        CloudClient.Cancellation before=new CloudClient.Cancellation();before.cancel();
        cancelled(()->CloudPipeline.transcribe(before,c->{reads.incrementAndGet();return new byte[4];},(a,c)->{uploads.incrementAndGet();return "text";}));
        equal(reads.get(),0);equal(uploads.get(),0);

        CloudClient.Cancellation reading=new CloudClient.Cancellation();byte[] owned=new byte[]{1,2,3};
        cancelled(()->CloudPipeline.transcribe(reading,c->{c.cancel();return owned;},(a,c)->{uploads.incrementAndGet();return "text";}));
        equal(uploads.get(),0);equal(owned[0],0);

        // Cancellation closes an attached blocked input; no upload follows its return.
        CloudClient.Cancellation blocked=new CloudClient.Cancellation();CountDownLatch entered=new CountDownLatch(1),closed=new CountDownLatch(1);
        InputStream input=new InputStream(){
            public int read(){throw new AssertionError("Use block read");}
            public int read(byte[] b,int off,int len)throws IOException{entered.countDown();try{if(!closed.await(2,TimeUnit.SECONDS))throw new IOException("Input was not closed");}catch(InterruptedException e){throw new IOException(e);}return -1;}
            public void close(){closed.countDown();}
        };
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<?> future=executor.submit(()->{try{cancelled(()->CloudPipeline.transcribe(blocked,c->CloudPipeline.read(input,16,c),(a,c)->{uploads.incrementAndGet();return "text";}));}catch(Exception e){throw new RuntimeException(e);}});
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Input never started");
            blocked.cancel();future.get(3,TimeUnit.SECONDS);equal(uploads.get(),0);
        }finally{executor.shutdownNow();}

        CloudClient.Cancellation afterAsr=new CloudClient.Cancellation();
        cancelled(()->CloudPipeline.voice(afterAsr,c->new byte[4],(a,c)->{uploads.incrementAndGet();c.cancel();return "transcript";},(t,c)->{models.incrementAndGet();return "answer";}));
        equal(uploads.get(),1);equal(models.get(),0);

        CloudClient.Cancellation success=new CloudClient.Cancellation();byte[] audio=new byte[]{8,9};
        CloudPipeline.VoiceResult<String> result=CloudPipeline.voice(success,c->{if(c!=success)throw new AssertionError();return audio;},
            (a,c)->{if(c!=success||a[0]!=8)throw new AssertionError();uploads.incrementAndGet();return "question";},
            (t,c)->{if(c!=success||!t.equals("question"))throw new AssertionError();models.incrementAndGet();return "answer";});
        if(!result.asr.equals("question")||!result.answer.equals("answer"))throw new AssertionError();
        equal(audio[0],0);equal(models.get(),1);

        CloudClient.Cancellation failed=new CloudClient.Cancellation();
        try{CloudPipeline.<String>voice(failed,c->new byte[2],(a,c)->{throw new IOException("ASR failed");},(t,c)->{models.incrementAndGet();return "bad";});throw new AssertionError();}catch(IOException expected){}
        equal(models.get(),1);
        CloudClient.Cancellation afterModel=new CloudClient.Cancellation();
        cancelled(()->CloudPipeline.voice(afterModel,c->new byte[2],(a,c)->"q",(t,c)->{c.cancel();return "must not render";}));
        try{CloudPipeline.read(new ByteArrayInputStream(new byte[17]),16,new CloudClient.Cancellation());throw new AssertionError();}catch(IOException expected){}
        System.out.println("Pipeline cancellation, input closure, stage boundaries and successful path passed");
    }
}
