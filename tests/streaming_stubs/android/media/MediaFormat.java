package android.media;
public final class MediaFormat {
    public static final String KEY_SAMPLE_RATE="sample-rate",KEY_CHANNEL_COUNT="channel-count",KEY_PCM_ENCODING="pcm-encoding";
    public boolean containsKey(String k){return false;}
    public int getInteger(String k){return k.equals(KEY_SAMPLE_RATE)?48000:1;}
}
