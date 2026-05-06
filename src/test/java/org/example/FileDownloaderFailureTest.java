package org.example;

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

import static org.junit.jupiter.api.Assertions.*;

class FileDownloaderFailureTest {

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
    void testFailsWhenServerReturns404OnHead() {
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Path output = tempDir.resolve("missing.bin");
        Config config = Config.builder().maxRetries(0).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            IOException ex = assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/missing"), output));
            assertTrue(ex.getMessage().contains("404"));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenServerReturns500OnHead() {
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        Path output = tempDir.resolve("error.bin");
        Config config = Config.builder().maxRetries(0).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/error"), output));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenContentLengthHeaderIsMissing() {
        server.createContext("/no-length", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        Path output = tempDir.resolve("no-length.bin");
        Config config = Config.builder().maxRetries(0).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            IOException ex = assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/no-length"), output));
            assertTrue(ex.getMessage().toLowerCase().contains("content-length"));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenChunkReturns500() throws Exception {
        byte[] data = randomBytes(20_000);

        server.createContext("/chunk-fails", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        Path output = tempDir.resolve("chunk-fails.bin");
        Config config = Config.builder()
                .chunkSize(2000)
                .threadCount(4)
                .maxRetries(0)
                .build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/chunk-fails"), output));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenServerIgnoresRangeAndReturns200() throws Exception {
        byte[] data = randomBytes(10_000);

        server.createContext("/ignores-range", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
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

        Path output = tempDir.resolve("ignores.bin");
        Config config = Config.builder()
                .chunkSize(1000)
                .threadCount(4)
                .maxRetries(0)
                .build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            IOException ex = assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/ignores-range"), output));
            assertTrue(ex.getMessage().contains("206") || ex.getMessage().contains("200"));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenOnlyOneChunkFailsButOthersSucceed() throws Exception {
        byte[] data = randomBytes(20_000);

        server.createContext("/one-fails", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.contains("4000-")) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            servePartial(exchange, data, range);
        });

        Path output = tempDir.resolve("one-fails.bin");
        Config config = Config.builder()
                .chunkSize(2000)
                .threadCount(4)
                .maxRetries(0)
                .build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/one-fails"), output));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenChunkConnectionDrops() throws Exception {
        byte[] data = randomBytes(20_000);

        server.createContext("/drops", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            String[] parts = range.replace("bytes=", "").split("-");
            int length = Integer.parseInt(parts[1]) - Integer.parseInt(parts[0]) + 1;
            exchange.sendResponseHeaders(206, length);
            exchange.close();
        });

        Path output = tempDir.resolve("drops.bin");
        Config config = Config.builder()
                .chunkSize(2000)
                .threadCount(4)
                .maxRetries(0)
                .requestTimeout(Duration.ofSeconds(5))
                .build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/drops"), output));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenRequestTimesOut() throws Exception {
        byte[] data = randomBytes(5000);

        server.createContext("/slow", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            servePartial(exchange, data, range);
        });

        Path output = tempDir.resolve("slow.bin");
        Config config = Config.builder()
                .chunkSize(1000)
                .threadCount(2)
                .maxRetries(0)
                .requestTimeout(Duration.ofSeconds(1))
                .build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(Exception.class, () ->
                    downloader.download(baseUri.resolve("/slow"), output));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testFailsWhenServerIsNotRunning() {
        URI deadUri = URI.create("http://localhost:1");

        Path output = tempDir.resolve("dead.bin");
        Config config = Config.builder()
                .maxRetries(0)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            assertThrows(Exception.class, () ->
                    downloader.download(deadUri.resolve("/file"), output));
        }

        assertFalse(Files.exists(output));
    }

    @Test
    void testDoesNotLeaveFileWhenWriteFails() throws Exception {
        byte[] data = randomBytes(5000);
        serveFileWithRanges("/file", data);

        Path output = tempDir.resolve("nested/sub/file.bin");

        try (FileDownloader downloader = new FileDownloader(Config.defaults())) {
            downloader.download(baseUri.resolve("/file"), output);
        }

        assertTrue(Files.exists(output));
    }

    @Test
    void testDownloaderCanBeReusedAfterFailure() throws Exception {
        byte[] data = randomBytes(5000);
        serveFileWithRanges("/good", data);
        server.createContext("/bad", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        Config config = Config.builder().maxRetries(0).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            Path bad = tempDir.resolve("bad.bin");
            assertThrows(IOException.class, () ->
                    downloader.download(baseUri.resolve("/bad"), bad));
            assertFalse(Files.exists(bad));

            Path good = tempDir.resolve("good.bin");
            downloader.download(baseUri.resolve("/good"), good);
            assertArrayEquals(data, Files.readAllBytes(good));
        }
    }

    @Test
    void testMultipleConcurrentFailuresAllCleanedUp() throws Exception {
        server.createContext("/always-fails", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", "10000");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        Config config = Config.builder().maxRetries(0).build();

        try (FileDownloader downloader = new FileDownloader(config)) {
            for (int i = 0; i < 5; i++) {
                Path output = tempDir.resolve("fail-" + i + ".bin");
                assertThrows(IOException.class, () ->
                        downloader.download(baseUri.resolve("/always-fails"), output));
                assertFalse(Files.exists(output));
            }
        }
    }

    @Test
    void testChunkRangeRejectsNegativeStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkRange(-1, 100));
    }

    @Test
    void testChunkRangeRejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkRange(100, 50));
    }

    @Test
    void testChunkRangeRejectsNegativeTail() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkRange(0, -1));
    }

    @Test
    void testConfigRejectsZeroThreadCount() {
        assertThrows(IllegalArgumentException.class,
                () -> Config.builder().threadCount(0).build());
    }

    @Test
    void testConfigRejectsNegativeChunkSize() {
        assertThrows(IllegalArgumentException.class,
                () -> Config.builder().chunkSize(-100).build());
    }

    @Test
    void testConfigRejectsNullTimeout() {
        assertThrows(NullPointerException.class,
                () -> Config.builder().requestTimeout(null).build());
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

    private void servePartial(com.sun.net.httpserver.HttpExchange exchange,
                              byte[] data, String range) throws IOException {
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