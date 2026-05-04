package org.example;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class FileDownloader implements AutoCloseable{
    private final HttpClient  client;
    private final Config config;
    private final ExecutorService executor;
    public FileDownloader(Config config ) {
        this.config=config;
        this.executor = Executors.newFixedThreadPool(config.threadCount());
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(config.connectTimeout())
                .executor(executor)
                .build();
    }
    public Path download(URI url, Path outputpath) throws IOException, InterruptedException {
        FileMetaData metadata= FileMetaData.fetch(client,url,config.requestTimeout());

        if (outputpath.getParent() != null) {
            Files.createDirectories(outputpath.getParent());
        }

        if (metadata.acceptsRanges() && metadata.contentLength() > 0) {
            downloadParallel(url, outputpath, metadata.contentLength());
        }
        return outputpath;
    }
    private void downloadParallel(URI url,Path outputpath,long totalsize) throws IOException,InterruptedException {
        List<ChunkRange> chunkranges=ChunkRange.split(totalsize, this.config.chunkSize());

        try(RandomAccessFile raf=new RandomAccessFile(outputpath.toFile(), "rw");
            FileChannel channel=raf.getChannel()){

            raf.setLength(totalsize);

            List<Future<Void>> futures=new ArrayList<>();
            for(ChunkRange range:chunkranges){
                futures.add(executor.submit(()->{
                    downloadChunk(url,range,channel);
                    return null;
                }));
            }
            try{
                for(Future<Void> future:futures){
                    future.get();
                }
            } catch (ExecutionException e) {
                futures.forEach(f -> f.cancel(true));
                Files.deleteIfExists(outputpath);
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                throw new IOException("Chunk download failed", cause);
            }
        }
    }
    private void downloadChunk(URI url,ChunkRange range,FileChannel channel) throws IOException, InterruptedException {
        HttpRequest request=HttpRequest.newBuilder(url)
                .header("Range",range.toRangeHeader())
                .GET()
                .timeout(config.requestTimeout())
                .build();
        HttpResponse<InputStream> response=this.client.send(request,HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 206) {
            throw new IOException("Expected 206 Partial Content, got " + response.statusCode()
                    + " for range " + range.toRangeHeader());
        }

        try(InputStream input=response.body()){
            long position=range.head();
            byte[] tmp=new byte[8192];
            ByteBuffer buffer=ByteBuffer.wrap(tmp);
            int read;
            while ((read = input.read(tmp)) != -1) {
                buffer.position(0).limit(read);
                while (buffer.hasRemaining()) {
                    position += channel.write(buffer, position);
                }
            }
        }
    }
    @Override
    public void close() {
        executor.shutdown();
    }
}
