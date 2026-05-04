package org.example;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        if(metadata.acceptsRanges()){
            //downloadInParallel
        }
        else{
            //downloadAsStream
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
    }
}
