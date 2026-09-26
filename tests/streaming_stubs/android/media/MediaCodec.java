package android.media;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
/** Deterministic codec edge for the real stream loop, not a codec quality test. */
public final class MediaCodec {
    public static final int BUFFER_FLAG_END_OF_STREAM=4,BUFFER_FLAG_CODEC_CONFIG=2,INFO_OUTPUT_FORMAT_CHANGED=-2;
    public static boolean eosHasPcm;
    public static final class BufferInfo {public int size,flags,offset;}
    private final ArrayDeque<Integer> pending=new ArrayDeque<>();
    private boolean eosQueued;
    public static MediaCodec createDecoderByType(String type){return new MediaCodec();}
    public void configure(MediaFormat f,Object a,Object b,int c){}
    public void start(){}
    public int dequeueInputBuffer(long wait){if(eosQueued)throw new AssertionError("input after EOS");return 0;}
    public ByteBuffer getInputBuffer(int i){return ByteBuffer.allocate(4096);}
    public void queueInputBuffer(int i,int off,int size,long pts,int flags){
        if(eosQueued)throw new AssertionError("double EOS/input after EOS");
        if((flags&BUFFER_FLAG_END_OF_STREAM)!=0)eosQueued=true;
        pending.add(flags);
    }
    public int dequeueOutputBuffer(BufferInfo info,long wait){
        if(pending.isEmpty())return -1;
        info.flags=pending.remove();info.offset=0;
        info.size=(info.flags&BUFFER_FLAG_END_OF_STREAM)==0||eosHasPcm?6:0;
        return 0;
    }
    public ByteBuffer getOutputBuffer(int i){return ByteBuffer.wrap(new byte[]{1,0,2,0,3,0});}
    public MediaFormat getOutputFormat(){return new MediaFormat();}
    public void releaseOutputBuffer(int i,boolean b){}
    public void stop(){}
    public void release(){}
}
