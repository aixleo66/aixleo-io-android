package android.media;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
/** Only the platform format edge is substituted. */
public final class MediaFormat {
    public static final String KEY_CHANNEL_COUNT="channel-count", KEY_SAMPLE_RATE="sample-rate",
        KEY_PCM_ENCODING="pcm-encoding", KEY_MAX_INPUT_SIZE="max-input-size";
    private final Map<String,Object> values=new HashMap<>();
    public static MediaFormat createAudioFormat(String mime,int rate,int channels) {
        MediaFormat f=new MediaFormat();f.values.put("mime",mime);
        f.setInteger(KEY_SAMPLE_RATE,rate);f.setInteger(KEY_CHANNEL_COUNT,channels);return f;
    }
    public void setInteger(String key,int value) {values.put(key,value);}
    public int getInteger(String key) {return (Integer)values.get(key);}
    public boolean containsKey(String key) {return values.containsKey(key);}
    public void setByteBuffer(String key,ByteBuffer value) {values.put(key,value);}
}
