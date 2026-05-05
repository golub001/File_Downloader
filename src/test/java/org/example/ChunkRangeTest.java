package org.example;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkRangeTest {

    @Test
    void testSize() {
        ChunkRange chunk = new ChunkRange(0, 1023);
        assertEquals(1024, chunk.size());

        ChunkRange chunk2 = new ChunkRange(100, 199);
        assertEquals(100, chunk2.size());
    }

    @Test
    void testRangeHeader() {
        ChunkRange chunk = new ChunkRange(1024, 2047);
        assertEquals("bytes=1024-2047", chunk.toRangeHeader());
    }

    @Test
    void testSplitWhenSizeIsDivisible() {
        List<ChunkRange> result = ChunkRange.split(1000, 100);

        assertEquals(10, result.size());
        assertEquals(0, result.get(0).head());
        assertEquals(99, result.get(0).tail());
        assertEquals(900, result.get(9).head());
        assertEquals(999, result.get(9).tail());
    }

    @Test
    void testSplitWhenSizeIsNotDivisible() {
        List<ChunkRange> result = ChunkRange.split(1050, 100);

        assertEquals(11, result.size());

        ChunkRange last = result.get(10);
        assertEquals(1000, last.head());
        assertEquals(1049, last.tail());
        assertEquals(50, last.size());
    }

    @Test
    void testSplitWhenFileIsSmallerThanChunk() {
        List<ChunkRange> result = ChunkRange.split(50, 1000);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).head());
        assertEquals(49, result.get(0).tail());
    }

    @Test
    void testSplitWhenSizeIsZero() {
        List<ChunkRange> result = ChunkRange.split(0, 100);
        assertTrue(result.isEmpty());
    }

    @Test
    void testInvalidRangeNegativeHead() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ChunkRange(-1, 10);
        });
    }

    @Test
    void testInvalidRangeHeadBiggerThanTail() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ChunkRange(10, 5);
        });
    }
    @Test
    void testHeadAndTailGetters() {
        ChunkRange chunk = new ChunkRange(500, 999);
        assertEquals(500, chunk.head());
        assertEquals(999, chunk.tail());
    }

    @Test
    void testSingleByteRange() {
        ChunkRange chunk = new ChunkRange(42, 42);
        assertEquals(1, chunk.size());
        assertEquals("bytes=42-42", chunk.toRangeHeader());
    }

    @Test
    void testRangeHeaderForFirstChunk() {
        ChunkRange chunk = new ChunkRange(0, 0);
        assertEquals("bytes=0-0", chunk.toRangeHeader());
    }

    @Test
    void testSplitWithChunkSizeOfOne() {
        List<ChunkRange> result = ChunkRange.split(5, 1);

        assertEquals(5, result.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i, result.get(i).head());
            assertEquals(i, result.get(i).tail());
            assertEquals(1, result.get(i).size());
        }
    }

    @Test
    void testSplitWithChunkSizeBiggerThanFile() {
        List<ChunkRange> result = ChunkRange.split(500, 10000);

        assertEquals(1, result.size());
        assertEquals(500, result.get(0).size());
    }

    @Test
    void testSplitTotalSizeMatchesInputSize() {
        long totalSize = 12345;
        List<ChunkRange> chunks = ChunkRange.split(totalSize, 1000);

        long sum = 0;
        for (ChunkRange chunk : chunks) {
            sum += chunk.size();
        }

        assertEquals(totalSize, sum);
    }

    @Test
    void testSplitChunksAreContiguous() {
        List<ChunkRange> chunks = ChunkRange.split(10000, 333);

        for (int i = 1; i < chunks.size(); i++) {
            long prevTail = chunks.get(i - 1).tail();
            long currentHead = chunks.get(i).head();
            assertEquals(prevTail + 1, currentHead, "chunks must be contiguous");
        }
    }

    @Test
    void testSplitFirstChunkStartsAtZero() {
        List<ChunkRange> chunks = ChunkRange.split(5000, 100);
        assertEquals(0, chunks.get(0).head());
    }

    @Test
    void testSplitLastChunkEndsAtTotalSizeMinusOne() {
        List<ChunkRange> chunks = ChunkRange.split(5000, 100);
        ChunkRange last = chunks.get(chunks.size() - 1);
        assertEquals(4999, last.tail());
    }

    @Test
    void testInvalidRangeNegativeTail() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ChunkRange(0, -5);
        });
    }

    @Test
    void testRangeWithLargeOffsets() {
        long start = 1_000_000_000L;
        long end = 1_000_000_999L;
        ChunkRange chunk = new ChunkRange(start, end);

        assertEquals(1000, chunk.size());
        assertEquals("bytes=1000000000-1000000999", chunk.toRangeHeader());
    }

    @Test
    void testSplitOneByteFile() {
        List<ChunkRange> chunks = ChunkRange.split(1, 100);

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).head());
        assertEquals(0, chunks.get(0).tail());
        assertEquals(1, chunks.get(0).size());
    }
}