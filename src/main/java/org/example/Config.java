package org.example;

import java.time.Duration;
import java.util.Objects;

public record Config(
        int threadCount,
        int chunkSize,
        int maxRetries,
        Duration requestTimeout,
        Duration connectTimeout
) {
    public Config{
        if (threadCount < 1) throw new IllegalArgumentException("threadCount must be bigger than 0");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be bigger than 0");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Config defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int threadCount = 8;
        private int chunkSize = 4 * 1024 * 1024;
        private int maxRetries = 3;
        private Duration requestTimeout = Duration.ofMinutes(5);
        private Duration connectTimeout = Duration.ofSeconds(10);

        public Builder threadCount(int v)        { this.threadCount = v; return this; }
        public Builder chunkSize(int v)          { this.chunkSize = v; return this; }
        public Builder maxRetries(int v)         { this.maxRetries = v; return this; }
        public Builder requestTimeout(Duration v){ this.requestTimeout = v; return this; }
        public Builder connectTimeout(Duration v){ this.connectTimeout = v; return this; }

        public Config build() {
            return new Config(threadCount, chunkSize, maxRetries,
                    requestTimeout, connectTimeout);
        }
    }
}
