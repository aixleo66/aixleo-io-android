package android.media;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Deterministic platform edge only. It does not decode audio or implement a deadline.
 * The real RecordingDecoder owns the timeout/interruption decision and file writing.
 */
public final class MediaCodec {
    public static final int BUFFER_FLAG_CODEC_CONFIG=2, BUFFER_FLAG_END_OF_STREAM=4,
        INFO_TRY_AGAIN_LATER=-1, INFO_OUTPUT_FORMAT_CHANGED=-2;
    public static final class BufferInfo {public int offset,size,flags;public long presentationTimeUs;}
    public static volatile String mode;
    public static volatile MediaCodec last;
    public static volatile int creates;
    public static volatile CountDownLatch outputEntered;
    public static volatile AtomicBoolean allowOutputReturn;
    public int configured,started,stopped,released,inputPolls,outputPolls;
    public final List<Long> inputPollTimes=new ArrayList<>();
    public final List<Long> outputPollTimes=new ArrayList<>();

    public static void reset(String selected) {
        mode=selected;last=null;creates=0;SystemClock.now=0;
        outputEntered=new CountDownLatch(1);allowOutputReturn=new AtomicBoolean(false);
    }
    public static MediaCodec createDecoderByType(String mime) {
        if(!"audio/opus".equals(mime))throw new AssertionError("Unexpected mime");
        creates++;last=new MediaCodec();return last;
    }
    public String getName() {return "fixture-no-audio-decoding";}
    public void configure(MediaFormat format,Object surface,Object crypto,int flags) {
        configured++;
        if("configure-failure".equals(mode))throw new IllegalStateException("injected-configure-failure");
    }
    public void start() {started++;}
    public void stop() {stopped++;}
    public void release() {released++;}
    public int dequeueInputBuffer(long timeoutUs) {
        inputPolls++;inputPollTimes.add(SystemClock.now);return INFO_TRY_AGAIN_LATER;
    }
    public int dequeueOutputBuffer(BufferInfo info,long timeoutUs) {
        outputPolls++;outputPollTimes.add(SystemClock.now);
        if("deadline".equals(mode)) {
            // Establish the next real-loop check, including exactly the deadline.
            if(outputPolls==1)SystemClock.now=119999;
            else if(outputPolls==2)SystemClock.now=120000;
            else if(outputPolls==3)SystemClock.now=120001;
            else throw new AssertionError("Production decoder did not enforce its deadline");
        } else if("interrupt".equals(mode)) {
            outputEntered.countDown();long guard=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!allowOutputReturn.get()) {
                if(System.nanoTime()>guard)throw new AssertionError("Fixture controller did not release output poll");
                // Unlike await/sleep, park does not clear or throw on interruption.
                LockSupport.parkNanos(100000);
            }
        } else throw new AssertionError("Unexpected codec polling for mode "+mode);
        return INFO_TRY_AGAIN_LATER;
    }
    public ByteBuffer getInputBuffer(int index) {throw new AssertionError("No input buffer should be available");}
    public void queueInputBuffer(int index,int offset,int size,long pts,int flags) {throw new AssertionError("No input submission expected");}
    public MediaFormat getOutputFormat() {throw new AssertionError("No format event expected");}
    public ByteBuffer getOutputBuffer(int index) {throw new AssertionError("No decoded output expected");}
    public void releaseOutputBuffer(int index,boolean render) {throw new AssertionError("No output buffer allocated");}
}
