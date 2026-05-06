package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileDownloaderTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI baseUri;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(8);
        server.setExecutor(serverExecutor);
        server.start();
        baseUri = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void testDownloadSmallFile() throws Exception {
        byte[] data = randomBytes(500);
        serveFileWithRanges("/small", data);

        Path output = tempDir.resolve("small.bin");
        Config config = Config.builder().chunkSize(100).threadCount(4).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            downloader.download(baseUri.resolve("/small"), output);
        }

        assertArrayEquals(data, Files.readAllBytes(output));
    }

    @Test
    void testDownloadLargeFileInParallel() throws Exception {
        byte[] data = randomBytes(5 * 1024 * 1024);
        serveFileWithRanges("/large", data);

        Path output = tempDir.resolve("large.bin");
        Config config = Config.builder()
                .chunkSize(512 * 1024)
                .threadCount(8)
                .requestTimeout(Duration.ofSeconds(30))
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            downloader.download(baseUri.resolve("/large"), output);
        }

        assertArrayEquals(data, Files.readAllBytes(output));
    }

    @Test
    void testDownloadFileSmallerThanOneChunk() throws Exception {
        byte[] data = randomBytes(50);
        serveFileWithRanges("/tiny", data);

        Path output = tempDir.resolve("tiny.bin");
        Config config = Config.builder().chunkSize(1024).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            downloader.download(baseUri.resolve("/tiny"), output);
        }

        assertArrayEquals(data, Files.readAllBytes(output));
    }

    @Test
    void testDownloadFallbackWhenServerDoesNotSupportRanges() throws Exception {
        byte[] data = randomBytes(2000);
        serveFileWithoutRanges("/no-ranges", data);

        Path output = tempDir.resolve("noranges.bin");

        try (FileDownloader downloader = new FileDownloader(Config.defaults())) {
            downloader.download(baseUri.resolve("/no-ranges"), output);
        }

        assertArrayEquals(data, Files.readAllBytes(output));
    }

    @Test
    void testDownloadEmptyFile() throws Exception {
        byte[] data = new byte[0];
        serveFileWithoutRanges("/empty", data);

        Path output = tempDir.resolve("empty.bin");

        try (FileDownloader downloader = new FileDownloader(Config.defaults())) {
            downloader.download(baseUri.resolve("/empty"), output);
        }

        assertTrue(Files.exists(output));
        assertEquals(0, Files.size(output));
    }

    @Test
    void testDownloadCreatesParentDirectories() throws Exception {
        byte[] data = randomBytes(100);
        serveFileWithRanges("/file", data);

        Path output = tempDir.resolve("nested/dir/file.bin");

        try (FileDownloader downloader = new FileDownloader(Config.defaults())) {
            downloader.download(baseUri.resolve("/file"), output);
        }

        assertTrue(Files.exists(output));
        assertArrayEquals(data, Files.readAllBytes(output));
    }

    @Test
    void testDownloadThrowsOn404() {
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Path output = tempDir.resolve("missing.bin");
        Config config = Config.builder().maxRetries(0).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(IOException.class, () -> {
                downloader.download(baseUri.resolve("/missing"), output);
            });
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testDownloadDeletesFileWhenChunkFails() throws Exception {
        byte[] data = randomBytes(10000);
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/flaky", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if (requestCount.incrementAndGet() == 2) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            servePartial(exchange, data, range);
        });

        Path output = tempDir.resolve("flaky.bin");
        Config config = Config.builder().chunkSize(2000).threadCount(4).maxRetries(0).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(IOException.class, () -> {
                downloader.download(baseUri.resolve("/flaky"), output);
            });
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testDownloadVerifiesByteByByte() throws Exception {
        byte[] data = randomBytes(100_000);
        serveFileWithRanges("/data", data);

        Path output = tempDir.resolve("data.bin");
        Config config = Config.builder().chunkSize(8000).threadCount(6).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            downloader.download(baseUri.resolve("/data"), output);
        }

        byte[] downloaded = Files.readAllBytes(output);
        assertEquals(data.length, downloaded.length);
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], downloaded[i]);
        }
    }


    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private void serveFileWithRanges(String path, byte[] data) {
        server.createContext(path, exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            servePartial(exchange, data, range);
        });
    }

    private void serveFileWithoutRanges(String path, byte[] data) {
        server.createContext(path, exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
            exchange.close();
        });
    }

    private void servePartial(HttpExchange exchange, byte[] data, String range) throws IOException {
        if (range == null) {
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
            exchange.close();
            return;
        }

        String[] parts = range.replace("bytes=", "").split("-");
        int start = Integer.parseInt(parts[0]);
        int end = Integer.parseInt(parts[1]);
        int length = end - start + 1;

        exchange.getResponseHeaders().set("Content-Range",
                "bytes " + start + "-" + end + "/" + data.length);
        exchange.sendResponseHeaders(206, length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data, start, length);
        }
        exchange.close();
    }
}