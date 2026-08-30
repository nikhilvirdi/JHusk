package io.github.nikhilvirdi.jhusk.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite for {@link DataSource}, testing both generation mode and replay mode,
 * status transitions, overrun exception handling, defensive copying, and span tree recording.
 */
@DisplayName("DataSource behavior & recording tests")
class DataSourceTest {

    private static final long TEST_SEED = 42L;
    private DataSource dataSource;

    /** Instantiates a fresh generation-mode DataSource before each test method. */
    @BeforeEach
    void setUp() {
        dataSource = new DataSource(TEST_SEED);
    }

    /**
     * Verifies that two distinct DataSource instances constructed with the exact same seed
     * and subjected to the identical sequence of draw calls produce identical recorded byte buffers.
     * This determinism guarantee underpins cross-run replayability in JHusk.
     */
    @Test
    @DisplayName("Same seed and sequence of draws produce identical recorded buffer")
    void sameSeedProducesIdenticalBuffer() {
        DataSource ds1 = new DataSource(12345L);
        DataSource ds2 = new DataSource(12345L);

        ds1.drawInt();
        ds1.drawLong();
        ds1.drawBoolean();
        ds1.drawBytes(16);

        ds2.drawInt();
        ds2.drawLong();
        ds2.drawBoolean();
        ds2.drawBytes(16);

        assertArrayEquals(ds1.getRecordedBuffer(), ds2.getRecordedBuffer(),
                "Two DataSource instances with identical seeds and draw sequences must produce identical recorded buffers");
    }

    /**
     * Verifies that the length of the array returned by getRecordedBuffer() matches
     * the sum of byte widths drawn across all draw methods (4 + 8 + 1 + 20 = 33 bytes).
     */
    @Test
    @DisplayName("Recorded buffer length equals exact total bytes drawn across calls")
    void bufferLengthEqualsTotalBytesDrawn() {
        dataSource.drawInt();      // 4 bytes
        dataSource.drawLong();     // 8 bytes
        dataSource.drawBoolean();  // 1 byte
        dataSource.drawBytes(20);  // 20 bytes

        int expectedTotalBytes = 4 + 8 + 1 + 20;
        assertEquals(expectedTotalBytes, dataSource.getRecordedBuffer().length,
                "Recorded buffer length must match total bytes drawn");
    }

    /**
     * Verifies that once freeze() is called, any subsequent call to drawBytes or its convenience
     * methods throws an IllegalStateException, enforcing immutability after test case completion.
     */
    @Test
    @DisplayName("Drawing after freeze throws IllegalStateException")
    void drawAfterFreezeThrowsIllegalStateException() {
        dataSource.drawInt();
        dataSource.freeze();

        assertThrows(IllegalStateException.class, () -> dataSource.drawBytes(1),
                "Drawing bytes after freeze() must throw IllegalStateException");
        assertThrows(IllegalStateException.class, () -> dataSource.drawInt(),
                "Drawing int after freeze() must throw IllegalStateException");
        assertThrows(IllegalStateException.class, () -> dataSource.drawLong(),
                "Drawing long after freeze() must throw IllegalStateException");
        assertThrows(IllegalStateException.class, () -> dataSource.drawBoolean(),
                "Drawing boolean after freeze() must throw IllegalStateException");
    }

    /**
     * Verifies that attempting to draw beyond MAX_BUFFER_SIZE (8192 bytes) in generation mode
     * throws a DataSourceOverrunException rather than causing memory growth or silent truncation.
     */
    @Test
    @DisplayName("Drawing past MAX_BUFFER_SIZE throws DataSourceOverrunException")
    void drawPastMaxBufferSizeThrowsOverrunException() {
        // Draw up to MAX_BUFFER_SIZE (8192 bytes)
        dataSource.drawBytes(DataSource.MAX_BUFFER_SIZE);
        assertEquals(DataSource.MAX_BUFFER_SIZE, dataSource.getRecordedBuffer().length);

        // Attempting to draw even 1 more byte must throw DataSourceOverrunException
        assertThrows(DataSourceOverrunException.class, () -> dataSource.drawBytes(1),
                "Drawing past MAX_BUFFER_SIZE must throw DataSourceOverrunException");
    }

    /**
     * Verifies that getRecordedBuffer() returns a defensive copy, ensuring that external modifications
     * to the returned array do not alter the DataSource's internal recording state.
     */
    @Test
    @DisplayName("getRecordedBuffer returns a defensive copy that does not mutate internal state")
    void getRecordedBufferReturnsDefensiveCopy() {
        dataSource.drawInt();
        byte[] buffer = dataSource.getRecordedBuffer();
        byte[] originalCopy = buffer.clone();

        // Mutate the returned byte array
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (byte) (buffer[i] ^ 0xFF);
        }

        // Subsequent call to getRecordedBuffer should still match the unmutated state
        assertArrayEquals(originalCopy, dataSource.getRecordedBuffer(),
                "Mutating the array returned by getRecordedBuffer() must not mutate internal recorded buffer");
    }

    /**
     * Verifies the round-trip property: recording a sequence of draws in generation mode, then
     * constructing a replay-mode DataSource from the recorded buffer, yields the exact same
     * values in the same order.
     */
    @Test
    @DisplayName("Round-trip: Replay mode exactly reproduces generation mode values")
    void roundTripReplayMatchesGeneration() {
        // Generation
        DataSource genDs = new DataSource(999L);
        int v1 = genDs.drawInt();
        long v2 = genDs.drawLong();
        boolean v3 = genDs.drawBoolean();
        byte[] v4 = genDs.drawBytes(15);
        
        byte[] recorded = genDs.getRecordedBuffer();
        
        // Replay
        DataSource repDs = new DataSource(recorded);
        assertEquals(v1, repDs.drawInt());
        assertEquals(v2, repDs.drawLong());
        assertEquals(v3, repDs.drawBoolean());
        assertArrayEquals(v4, repDs.drawBytes(15));
        
        assertEquals(DataSource.Status.VALID, repDs.getStatus());
    }

    /**
     * Verifies that when a replay-mode DataSource is asked to draw more bytes than exist in the
     * buffer, it transitions status to OVERRUN and returns zero-padded byte arrays.
     */
    @Test
    @DisplayName("Truncated buffer in replay mode sets status to OVERRUN and returns zero-padded arrays")
    void replayOverrunSetsStatusAndReturnsZeroPadded() {
        byte[] buffer = new byte[] { 1, 2, 3 }; // Only 3 bytes provided
        DataSource repDs = new DataSource(buffer);
        
        assertEquals(DataSource.Status.VALID, repDs.getStatus());
        
        // Try to read 4 bytes (drawInt requires 4 bytes)
        int result = repDs.drawInt();
        
        // Should return 0 (zero-padded bytes result in 0)
        assertEquals(0, result);
        
        // Status must be OVERRUN
        assertEquals(DataSource.Status.OVERRUN, repDs.getStatus());
    }

    /** Verifies that calling markInvalid() transitions the DataSource status to Status.INVALID. */
    @Test
    @DisplayName("markInvalid correctly sets status to INVALID")
    void markInvalidSetsStatus() {
        DataSource ds = new DataSource(123L);
        assertEquals(DataSource.Status.VALID, ds.getStatus());
        ds.markInvalid();
        assertEquals(DataSource.Status.INVALID, ds.getStatus());
    }
    
    /** Verifies that new DataSource instances default to Status.VALID in both generation and replay modes. */
    @Test
    @DisplayName("Status defaults to VALID for both generation and replay modes")
    void statusDefaultsToValid() {
        DataSource genDs = new DataSource(1L);
        assertEquals(DataSource.Status.VALID, genDs.getStatus());
        
        DataSource repDs = new DataSource(new byte[10]);
        assertEquals(DataSource.Status.VALID, repDs.getStatus());
    }

    // ==================== Span Recording Tests ====================

    /**
     * Verifies that opening nested spans produces a hierarchy where child spans are registered
     * under parent spans without overlapping sibling byte boundaries.
     */
    @Test
    @DisplayName("Nested spans produce a correctly nested, non-overlapping tree")
    void nestedSpansProduceCorrectTree() {
        DataSource ds = new DataSource(77L);

        ds.startSpan("outer");
        {
            ds.startSpan("inner-a");
            ds.drawInt();    // 4 bytes at [0, 4)
            ds.endSpan();

            ds.startSpan("inner-b");
            ds.drawLong();   // 8 bytes at [4, 12)
            ds.endSpan();
        }
        ds.endSpan();
        ds.freeze();

        List<Span> roots = ds.getRootSpans();
        assertEquals(1, roots.size(), "Should have exactly one root span");

        Span outer = roots.get(0);
        assertEquals("outer", outer.getLabel());
        assertEquals(0, outer.getStart());
        assertEquals(12, outer.getEnd());
        assertEquals(2, outer.getChildren().size(), "Outer span should have 2 children");

        Span innerA = outer.getChildren().get(0);
        assertEquals("inner-a", innerA.getLabel());
        assertEquals(0, innerA.getStart());
        assertEquals(4, innerA.getEnd());
        assertEquals(0, innerA.getChildren().size());

        Span innerB = outer.getChildren().get(1);
        assertEquals("inner-b", innerB.getLabel());
        assertEquals(4, innerB.getStart());
        assertEquals(12, innerB.getEnd());
        assertEquals(0, innerB.getChildren().size());

        // Siblings must not overlap: inner-a ends at or before inner-b starts
        assertTrue(innerA.getEnd() <= innerB.getStart(),
                "Sibling spans must not overlap");
    }

    /**
     * Verifies that span start and end byte offsets precisely match the boundaries of byte draws made inside them.
     */
    @Test
    @DisplayName("Span boundaries align exactly with the byte ranges drawn within them")
    void spanBoundariesAlignWithDrawnBytes() {
        DataSource ds = new DataSource(88L);

        ds.drawBytes(5); // 5 bytes outside any span

        ds.startSpan("measured");
        ds.drawInt();      // 4 bytes
        ds.drawLong();     // 8 bytes
        ds.drawBoolean();  // 1 byte
        ds.endSpan();
        ds.freeze();

        List<Span> roots = ds.getRootSpans();
        assertEquals(1, roots.size());

        Span measured = roots.get(0);
        assertEquals(5, measured.getStart(), "Span should start after the 5 pre-span bytes");
        assertEquals(5 + 4 + 8 + 1, measured.getEnd(), "Span should end after all draws within it");
        assertEquals(4 + 8 + 1, measured.size(), "Span size should equal sum of draws (13 bytes)");
    }

    /** Verifies that calling endSpan() without an open span throws an IllegalStateException. */
    @Test
    @DisplayName("endSpan() with no matching startSpan() throws IllegalStateException")
    void endSpanWithNoStartSpanThrows() {
        DataSource ds = new DataSource(99L);
        assertThrows(IllegalStateException.class, ds::endSpan,
                "endSpan() with no open span must throw IllegalStateException");
    }

    /**
     * Verifies that span trees constructed during generation mode are structurally identical
     * to span trees constructed during replay mode when executing the same draw/span sequence.
     */
    @Test
    @DisplayName("Generation-mode and replay-mode span trees are structurally identical")
    void generationAndReplaySpanTreesAreIdentical() {
        // --- Generation mode ---
        DataSource genDs = new DataSource(555L);
        genDs.startSpan("root");
        {
            genDs.startSpan("a");
            genDs.drawInt();
            genDs.drawBoolean();
            genDs.endSpan();

            genDs.startSpan("b");
            genDs.drawLong();
            genDs.endSpan();
        }
        genDs.endSpan();
        genDs.freeze();

        byte[] recorded = genDs.getRecordedBuffer();
        List<Span> genTree = genDs.getRootSpans();

        // --- Replay mode with identical call sequence ---
        DataSource repDs = new DataSource(recorded);
        repDs.startSpan("root");
        {
            repDs.startSpan("a");
            repDs.drawInt();
            repDs.drawBoolean();
            repDs.endSpan();

            repDs.startSpan("b");
            repDs.drawLong();
            repDs.endSpan();
        }
        repDs.endSpan();
        repDs.freeze();

        List<Span> repTree = repDs.getRootSpans();

        // Structural comparison
        assertSpanTreesEqual(genTree, repTree);
        assertEquals(DataSource.Status.VALID, repDs.getStatus());
    }

    /** Verifies that calling freeze() while a span is still open throws an IllegalStateException. */
    @Test
    @DisplayName("Freezing with an unclosed span throws IllegalStateException")
    void freezeWithUnclosedSpanThrows() {
        DataSource ds = new DataSource(111L);
        ds.startSpan("dangling");
        ds.drawInt();

        IllegalStateException ex = assertThrows(IllegalStateException.class, ds::freeze,
                "freeze() with unclosed span(s) must throw IllegalStateException");
        assertTrue(ex.getMessage().contains("unclosed"),
                "Exception message should mention unclosed spans");
    }

    /** Recursively asserts structural equality between two lists of spans. */
    private void assertSpanTreesEqual(List<Span> expected, List<Span> actual) {
        assertEquals(expected.size(), actual.size(), "Span list sizes must match");
        for (int i = 0; i < expected.size(); i++) {
            Span e = expected.get(i);
            Span a = actual.get(i);
            assertEquals(e.getStart(), a.getStart(), "Span start mismatch at index " + i);
            assertEquals(e.getEnd(), a.getEnd(), "Span end mismatch at index " + i);
            assertEquals(e.getLabel(), a.getLabel(), "Span label mismatch at index " + i);
            assertSpanTreesEqual(e.getChildren(), a.getChildren());
        }
    }

    @Nested
    @DisplayName("ConfigurableBufferSizeTests — custom maxBufferSize constructors")
    class ConfigurableBufferSizeTests {

        @Test
        @DisplayName("Custom buffer size allows drawing up to the configured cap without throwing")
        void customBufferSizeAllowsLargerGeneration() {
            // Double the default cap: 16384 bytes
            DataSource ds = new DataSource(1L, 16384);
            // Draw 4000 x 4 bytes = 16000 bytes — under 16KB but over the default 8KB cap.
            // If the custom cap is not honoured, DataSourceOverrunException would be thrown.
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 4000; i++) {
                    ds.drawBytes(4);
                }
            }, "Drawing 16000 bytes within a 16KB custom cap must not throw DataSourceOverrunException");
        }

        @Test
        @DisplayName("Default single-arg constructor still enforces the original 8KB cap")
        void defaultConstructorStillEnforcesOriginalEightKbCap() {
            // The original single-arg constructor must behave byte-for-byte identically to before
            DataSource ds = new DataSource(1L);
            // Draw bytes until we exceed 8192
            int drawn = 0;
            while (drawn + 256 <= 8192) {
                final int count = 256;
                ds.drawBytes(count);
                drawn += count;
            }
            // The next draw that would push us past 8192 must throw
            assertThrows(DataSourceOverrunException.class,
                () -> ds.drawBytes(256),
                "Default constructor must still throw DataSourceOverrunException at the 8KB boundary");
        }

        @Test
        @DisplayName("Zero or negative maxBufferSize is rejected eagerly at construction")
        void zeroOrNegativeCustomBufferSizeRejectedEagerly() {
            assertThrows(IllegalArgumentException.class,
                () -> new DataSource(1L, 0),
                "maxBufferSize=0 must throw IllegalArgumentException");
            assertThrows(IllegalArgumentException.class,
                () -> new DataSource(1L, -100),
                "maxBufferSize=-100 must throw IllegalArgumentException");
        }
    }
}
