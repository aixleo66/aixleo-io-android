package dev.xr.rayneo.probe;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;

/** Decodes fixed 240-byte recording packets, stereo, without buffering the whole file. */
final class RecordingDecoder {
    static long decode(File raw,File wav)throws Exception{
        long length=raw.length();if(length<=0||length>RecordingFile.LIMIT||length%240!=0)throw new IOException("Raw packet alignment");
        if(!wav.createNewFile())throw new IOException("Derivative already exists");
        MediaFormat format=OpusAudio.format();format.setInteger(MediaFormat.KEY_CHANNEL_COUNT,2);
        ByteBuffer head=ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes("US-ASCII")).put((byte)1).put((byte)2).putShort((short)0).putInt(16000).putShort((short)0).put((byte)0).flip();format.setByteBuffer("csd-0",head);
        MediaCodec codec=MediaCodec.createDecoderByType("audio/opus");
        long frames=length/240,read=0,written=0,decoded=0;int skip=312*4;
        try(RandomAccessFile out=new RandomAccessFile(wav,"rw");DataInputStream in=new DataInputStream(new FileInputStream(raw))){
            out.write(new byte[44]);codec.configure(format,null,null,0);codec.start();
            boolean eos=false,done=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long deadline=SystemClock.elapsedRealtime()+120000;
            while(!done){
                if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>deadline)throw new IOException("Decoder timeout");
                if(!eos){int index=codec.dequeueInputBuffer(1000);if(index>=0){
                    if(read<frames){byte[] packet=new byte[240];in.readFully(packet);boolean any=false;for(byte b:packet)any|=b!=0;
                        if(!any||OpusAudio.samples48k(packet)!=960)throw new IOException("Unsupported recording packet");
                        ByteBuffer input=codec.getInputBuffer(index);input.clear();input.put(packet);java.util.Arrays.fill(packet,(byte)0);
                        codec.queueInputBuffer(index,0,240,read++*20000,0);
                    }else{codec.queueInputBuffer(index,0,0,read*20000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true;}
                }}
                int index=codec.dequeueOutputBuffer(info,1000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat actual=codec.getOutputFormat();
                    if(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=48000||actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=2||(actual.containsKey(MediaFormat.KEY_PCM_ENCODING)&&actual.getInteger(MediaFormat.KEY_PCM_ENCODING)!=AudioFormat.ENCODING_PCM_16BIT))throw new IOException("Unexpected recording PCM format");
                }else if(index>=0){try{
                    if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        if(info.size%4!=0||decoded+info.size>frames*960*4)throw new IOException("PCM duration overflow");decoded+=info.size;
                        ByteBuffer data=codec.getOutputBuffer(index);data.position(info.offset);data.limit(info.offset+info.size);
                        int trim=Math.min(skip,info.size);skip-=trim;data.position(data.position()+trim);byte[] block=new byte[data.remaining()];data.get(block);out.write(block);written+=block.length;java.util.Arrays.fill(block,(byte)0);
                    }done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                }finally{codec.releaseOutputBuffer(index,false);}}
            }
            if(decoded!=frames*960*4||written==0)throw new IOException("Decoded duration mismatch");
            ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);h.put("RIFF".getBytes("US-ASCII")).putInt((int)written+36).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)2).putInt(48000).putInt(192000).putShort((short)4).putShort((short)16).put("data".getBytes("US-ASCII")).putInt((int)written);
            out.seek(0);out.write(h.array());out.getFD().sync();return written*1000/192000;
        }finally{try{codec.stop();}catch(Exception ignored){}codec.release();}
    }
}
