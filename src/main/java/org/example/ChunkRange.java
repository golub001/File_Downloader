package org.example;

import java.util.ArrayList;
import java.util.List;

public record ChunkRange(
        long head,long tail
) {
    public ChunkRange{
        if(head<0) throw new IllegalArgumentException("head less than 0");
        if(tail<0) throw new IllegalArgumentException("tail less than 0");
        if(head>tail) throw new IllegalArgumentException("head > tail");
    }
    public long size(){
        return tail-head+1;
    }
    public String toRangeHeader(){
        return "bytes="+head+"-"+tail;
    }
    public static List<ChunkRange> split(long totalsize,int chunksize){
        List<ChunkRange> chunkRanges = new ArrayList<>();
        for(long start=0;start<totalsize;start+=chunksize){
            long end=Math.min(start+chunksize-1,totalsize-1);
            chunkRanges.add(new ChunkRange(start,end));
        }
        return chunkRanges;
    }
}
