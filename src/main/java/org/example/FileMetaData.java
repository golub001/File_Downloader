package org.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public record FileMetaData(long contentLength, boolean acceptsRanges) {
    public static FileMetaData fetch(HttpClient httpClient, URI url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .method("HEAD",HttpRequest.BodyPublishers.noBody())
                .timeout(timeout)
                .build();
        HttpResponse<Void> response=httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=200) {
            throw new IOException("Head request failed "+ response.statusCode());
        }

        long contentLength=response.headers()
                .firstValueAsLong("Content-Length")
                .orElseThrow(()->new IOException("No Content-Length header present"));
        boolean acceptsRanges=response.headers()
                .firstValue("Accept-Ranges")
                .map("bytes"::equalsIgnoreCase)
                .orElse(false);
        return new FileMetaData(contentLength,acceptsRanges);
    }
}
