package org.example;

import org.junit.jupiter.api.Test;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test
    void testDefaultsHasReasonableValues() {
        Config config = Config.defaults();

        assertTrue(config.threadCount() > 0);
        assertTrue(config.chunkSize() > 0);
        assertTrue(config.maxRetries() >= 0);
        assertNotNull(config.requestTimeout());
        assertNotNull(config.connectTimeout());
    }

    @Test
    void testBuilderSetsValues() {
        Config config = Config.builder()
                .threadCount(16)
                .chunkSize(2048)
                .maxRetries(5)
                .requestTimeout(Duration.ofMinutes(2))
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        assertEquals(16, config.threadCount());
        assertEquals(2048, config.chunkSize());
        assertEquals(5, config.maxRetries());
        assertEquals(Duration.ofMinutes(2), config.requestTimeout());
        assertEquals(Duration.ofSeconds(15), config.connectTimeout());
    }

    @Test
    void testThreadCountZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            Config.builder().threadCount(0).build();
        });
    }

    @Test
    void testThreadCountNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            Config.builder().threadCount(-1).build();
        });
    }

    @Test
    void testChunkSizeZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            Config.builder().chunkSize(0).build();
        });
    }

    @Test
    void testChunkSizeNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            Config.builder().chunkSize(-100).build();
        });
    }

    @Test
    void testMaxRetriesNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            Config.builder().maxRetries(-1).build();
        });
    }

    @Test
    void testMaxRetriesZeroAllowed() {
        Config config = Config.builder().maxRetries(0).build();
        assertEquals(0, config.maxRetries());
    }

    @Test
    void testNullRequestTimeoutThrows() {
        assertThrows(NullPointerException.class, () -> {
            Config.builder().requestTimeout(null).build();
        });
    }

    @Test
    void testNullConnectTimeoutThrows() {
        assertThrows(NullPointerException.class, () -> {
            Config.builder().connectTimeout(null).build();
        });
    }

    @Test
    void testBuilderCanBeUsedMultipleTimes() {
        Config.Builder builder = Config.builder();
        Config first = builder.threadCount(4).build();
        Config second = builder.threadCount(8).build();

        assertEquals(4, first.threadCount());
        assertEquals(8, second.threadCount());
    }
}