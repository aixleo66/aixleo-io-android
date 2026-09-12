package dev.xr.rayneo.probe;
import java.io.*;
import java.util.*;

/** Single-worker offset receiver. Overlapping retransmits must match existing bytes. */
final class RecordingFile implements Closeable {
    static final int LIMIT=24*1024*1024;
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
    int seal(boolean completed) throws IOException {
        if(!completed||failed||ranges.size()!=1||ranges.firstKey()!=0||received()==0||received()%240!=0||file.length()!=received())throw new IOException("Incomplete recording");
        sealed=true;sync();return received();
    }
    public void close()throws IOException{file.close();}
}
