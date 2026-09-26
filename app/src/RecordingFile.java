package dev.xr.rayneo.probe;
import java.io.*;
import java.util.*;

/** Single-worker offset receiver. Overlapping retransmits must match existing bytes. */
final class RecordingFile implements Closeable {
    /** Hard ceiling for one recording's raw Opus, in bytes.
     *
     * <p>Was 24 MiB, which paired with the old hard-coded five minute cap. At the measured rate
     * (302,400 B for 11 s = ~27.5 KB/s) that is about 15 minutes, so when the length cap became
     * configurable on 2026-09-22 a 60 minute recording would have failed at roughly a quarter of
     * the way through and left half a file behind -- worse than the old clean stop. Caught by
     * independent review before it shipped to anyone.
     *
     * <p>Raised rather than removed: keep an explicit storage bound and widen it only after
     * staged verification, not to delete it. 240 MiB covers the 120 minute maximum the setting now
     * allows, with margin. Bytes are streamed to disk through RandomAccessFile, so this is a policy
     * bound rather than a memory one -- but long recordings remain unverified either way. */
    static final int LIMIT=240*1024*1024;
    final File source;
    private final RandomAccessFile file;
    private final TreeMap<Integer,Integer> ranges=new TreeMap<>();
    private boolean failed, sealed;
    RecordingFile(File source) throws IOException {
        this.source=source;
        if(!source.createNewFile())throw new IOException("Original already exists");
        file=new RandomAccessFile(source,"rw");
    }
    void append(int offset,byte[] bytes) throws IOException {
        if(failed||sealed)throw new IOException("Receiver closed or invalid");
        if(bytes==null||bytes.length==0||bytes.length>65536||offset<0||offset>LIMIT-bytes.length)throw new IOException("Recording bounds");
        int end=offset+bytes.length;
        for(Map.Entry<Integer,Integer> range:ranges.entrySet()){
            int a=Math.max(offset,range.getKey()),b=Math.min(end,range.getValue());
            if(a<b){byte[] old=new byte[b-a];file.seek(a);file.readFully(old);
                for(int i=0;i<old.length;i++)if(old[i]!=bytes[a-offset+i]){failed=true;throw new IOException("Conflicting retransmit");}}
        }
        file.seek(offset);file.write(bytes);
        Map.Entry<Integer,Integer> before=ranges.floorEntry(offset);
        if(before!=null&&before.getValue()>=offset){offset=before.getKey();end=Math.max(end,before.getValue());ranges.remove(before.getKey());}
        Map.Entry<Integer,Integer> next;
        while((next=ranges.ceilingEntry(offset))!=null&&next.getKey()<=end){end=Math.max(end,next.getValue());ranges.remove(next.getKey());}
        ranges.put(offset,end);if(ranges.size()>4096){failed=true;throw new IOException("Too many gaps");}
    }
    int received(){int n=0;for(Map.Entry<Integer,Integer> r:ranges.entrySet())n+=r.getValue()-r.getKey();return n;}
    String coverage(){StringBuilder s=new StringBuilder();for(Map.Entry<Integer,Integer> r:ranges.entrySet()){if(s.length()>0)s.append(',');s.append(r.getKey()).append('-').append(r.getValue());}return s.toString();}
    void sync()throws IOException{file.getFD().sync();}
    /** A recording the glasses reported complete is sealed even with gaps: OggOpusWriter turns a
     * packet that never fully arrived into silence, as the official app does, instead of failing
     * the whole file for 720 missing bytes (09-23, a 60-minute recording lost that way). The raw
     * file keeps the gaps as they were; coverage and the silence count record them. */
    int seal(boolean completed) throws IOException {
        if(!completed||failed||ranges.isEmpty()||received()==0)throw new IOException("Incomplete recording");
        sealed=true;sync();return received();
    }
    NavigableMap<Integer,Integer> ranges(){return new TreeMap<>(ranges);}
    public void close()throws IOException{file.close();}
}
