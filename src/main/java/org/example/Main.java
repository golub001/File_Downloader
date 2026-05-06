package org.example;

import java.net.URI;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        Config config=Config.builder().build();
        try (FileDownloader downloader = new FileDownloader(config)) {
            Path result = downloader.download(
                    URI.create("http://localhost:8080/test.txt"),
                    Path.of("downloads", "test2.txt")
            );
            System.out.println("Done: " + result);
        }


    }
}