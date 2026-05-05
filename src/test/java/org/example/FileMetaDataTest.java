package org.example;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class FileMetaDataTest {

    private HttpServer server;
    private HttpClient client;
    private URI baseUri;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        baseUri = URI.create("http://localhost:" + server.getAddress().getPort());
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void testFetchReturnsContentLengthAndAcceptsRanges() throws Exception {
        server.createContext("/file.bin", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Length", "12345");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        FileMetaData metadata = FileMetaData.fetch(
                client,
                baseUri.resolve("/file.bin"),
                Duration.ofSeconds(5)
        );

        assertEquals(12345, metadata.contentLength());
        assertTrue(metadata.acceptsRanges());
    }

    @Test
    void testFetchWhenServerDoesNotAcceptRanges() throws Exception {
        server.createContext("/no-ranges", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", "500");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        FileMetaData metadata = FileMetaData.fetch(
                client,
                baseUri.resolve("/no-ranges"),
                Duration.ofSeconds(5)
        );

        assertEquals(500, metadata.contentLength());
        assertFalse(metadata.acceptsRanges());
    }

    @Test
    void testFetchWhenAcceptRangesIsNone() throws Exception {
        server.createContext("/none", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "none");
            exchange.getResponseHeaders().set("Content-Length", "100");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        FileMetaData metadata = FileMetaData.fetch(
                client,
                baseUri.resolve("/none"),
                Duration.ofSeconds(5)
        );

        assertFalse(metadata.acceptsRanges());
    }

    @Test
    void testFetchAcceptsRangesIsCaseInsensitive() throws Exception {
        server.createContext("/upper", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "BYTES");
            exchange.getResponseHeaders().set("Content-Length", "200");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        FileMetaData metadata = FileMetaData.fetch(
                client,
                baseUri.resolve("/upper"),
                Duration.ofSeconds(5)
        );

        assertTrue(metadata.acceptsRanges());
    }

    @Test
    void testFetchThrowsWhenContentLengthMissing() {
        server.createContext("/no-length", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertThrows(IOException.class, () -> {
            FileMetaData.fetch(
                    client,
                    baseUri.resolve("/no-length"),
                    Duration.ofSeconds(5)
            );
        });
    }

    @Test
    void testFetchThrowsOn404() {
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertThrows(IOException.class, () -> {
            FileMetaData.fetch(
                    client,
                    baseUri.resolve("/missing"),
                    Duration.ofSeconds(5)
            );
        });
    }

    @Test
    void testFetchThrowsOn500() {
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        assertThrows(IOException.class, () -> {
            FileMetaData.fetch(
                    client,
                    baseUri.resolve("/error"),
                    Duration.ofSeconds(5)
            );
        });
    }

    @Test
    void testFetchHandlesEmptyFile() throws Exception {
        server.createContext("/empty", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        FileMetaData metadata = FileMetaData.fetch(
                client,
                baseUri.resolve("/empty"),
                Duration.ofSeconds(5)
        );

        assertEquals(0, metadata.contentLength());
        assertTrue(metadata.acceptsRanges());
    }

    @Test
    void testFetchHandlesLargeFile() throws Exception {
        long size = 5L * 1024 * 1024 * 1024; // 5 GB
        server.createContext("/large", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        FileMetaData metadata = FileMetaData.fetch(
                client,
                baseUri.resolve("/large"),
                Duration.ofSeconds(5)
        );

        assertEquals(size, metadata.contentLength());
    }
}