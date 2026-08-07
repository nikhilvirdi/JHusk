package io.github.nikhilvirdi.jhusk.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Foundation of JHusk's byte-stream generation and replay architecture.
 * <p>
 * <b>Role in Architecture:</b><br>
 * {@code DataSource} is the underlying byte provider for all property generators in JHusk.
 * It operates in one of two modes:
 * <ul>
 *   <li><b>Generation Mode:</b> Draws pseudorandom bytes from a seeded PRNG and records every byte handed
 *       out into a growable buffer for later replay and shrinking.</li>
 *   <li><b>Replay Mode:</b> Reads previously recorded bytes from a supplied buffer at an internal cursor,
 *       reproducing identical draw results for deterministic test case replay.</li>
 * </ul>
 * <p>
 * <b>Span Recording:</b><br>
 * Calls to {@link #startSpan(String)} and {@link #endSpan()} structure flat byte draws into a tree of
 * {@link Span} nodes. This metadata lets JHusk's shrinker delete semantically coherent byte regions
 * (e.g. an element in a collection) rather than raw arbitrary byte offsets.
 */
public class DataSource {

    /** Maximum allowed buffer capacity (8192 bytes / 8 KB), matching Hypothesis conventions. */
    public static final int MAX_BUFFER_SIZE = 8192;

    /**
     * Represents the overall validity status of a test run execution.
     */
    public enum Status {
        /** The execution proceeded normally within valid parameters and available buffer bytes. */
        VALID,
        /** The test run was explicitly rejected by a filter or precondition check (e.g., assume/filter failure). */
        INVALID,
        /** Replay mode requested more bytes than were present in the candidate buffer. */
        OVERRUN
    }

    /*
     * PRNG Choice: java.util.SplittableRandom
     * Reproducibility Guarantee:
     * SplittableRandom implements the SplitMix64 algorithm, which has a strictly defined,
     * immutable mathematical specification in Java. Unlike java.util.Random or default
     * RandomGenerator instances (which may vary by JVM vendor, version, or platform),
     * SplittableRandom guarantees identical output sequences for the same seed across all
     * JVM runs and versions. This cross-run determinism is essential for JHusk's test case
     * replay and shrinking mechanisms.
     */
    private final SplittableRandom random;
    private final ByteArrayOutputStream recordingBuffer;

    /** The byte array supplied for replaying a prior run; {@code null} when in generation mode. */
    private final byte[] replayBuffer;

    /** Current read offset index within {@code replayBuffer} during replay mode. */
    private int replayCursor = 0;

    /** Flag indicating whether the DataSource has been frozen (sealed against further modifications/draws). */
    private boolean frozen = false;

    /** Current execution status, defaulting to {@link Status#VALID}. */
    private Status status = Status.VALID;

    // --- Span tracking ---
    /** LIFO stack maintaining currently open/active spans during generation or replay. */
    private final Deque<Span> spanStack = new ArrayDeque<>();

    /** List of top-level completed root spans created during the run. */
    private final List<Span> rootSpans = new ArrayList<>();

    /**
     * Constructs a {@code DataSource} in <b>Generation Mode</b> initialized with a PRNG seed.
     *
     * @param seed the PRNG seed used to initialize {@link SplittableRandom}
     */
    public DataSource(long seed) {
        this.random = new SplittableRandom(seed);
        this.recordingBuffer = new ByteArrayOutputStream();
        this.replayBuffer = null;
    }

    /**
     * Constructs a {@code DataSource} in <b>Replay Mode</b> from a previously recorded byte buffer.
     *
     * @param buffer the byte array to replay (a defensive clone is stored internally)
     */
    public DataSource(byte[] buffer) {
        this.random = null;
        this.recordingBuffer = null;
        this.replayBuffer = buffer.clone();
    }

    /**
     * Draws {@code n} bytes from the data source.
     * <ul>
     *   <li>In <b>Generation Mode</b>: draws {@code n} random bytes from the PRNG, records them in the
     *       internal buffer, and returns a copy.</li>
     *   <li>In <b>Replay Mode</b>: reads the next {@code n} bytes from {@code replayBuffer} at the current cursor.</li>
     * </ul>
     *
     * @param n the number of bytes to draw
     * @return a byte array containing the {@code n} drawn bytes
     * @throws IllegalStateException if the {@code DataSource} has been frozen
     * @throws IllegalArgumentException if {@code n} is negative
     * @throws DataSourceOverrunException if drawing {@code n} bytes would exceed {@link #MAX_BUFFER_SIZE} in generation mode
     */
    public byte[] drawBytes(int n) {
        // Step 1: Enforce freeze immutability check
        if (frozen) {
            throw new IllegalStateException("DataSource is frozen; no further draws permitted");
        }
        // Step 2: Validate input argument
        if (n < 0) {
            throw new IllegalArgumentException("Requested byte count cannot be negative: " + n);
        }

        if (replayBuffer != null) {
            // --- Replay mode execution path ---
            if (replayCursor + n > replayBuffer.length) {
                // Buffer Overrun in Replay Mode:
                // When a shrink candidate or test replays past the end of the available buffer,
                // we set the status to OVERRUN and return a zero-padded array.
                //
                // Why return zeroes instead of throwing an exception:
                // Throwing an exception here would cause uncontrolled runtime failures inside the
                // user's property code under test. Returning a zero-padded array lets the property
                // finish execution deterministically, while the framework checks getStatus() after
                // the run and gracefully discards OVERRUN candidates.
                this.status = Status.OVERRUN;
                return new byte[n];
            }
            
            // Read n bytes starting at replayCursor position
            byte[] drawn = new byte[n];
            System.arraycopy(replayBuffer, replayCursor, drawn, 0, n);
            // Advance replayCursor by n bytes
            replayCursor += n;
            return drawn;
        } else {
            // --- Generation mode execution path ---
            if (recordingBuffer.size() + n > MAX_BUFFER_SIZE) {
                // Exceeding MAX_BUFFER_SIZE (8192 bytes) throws DataSourceOverrunException
                // to prevent runaway generators from exhausting heap memory.
                throw new DataSourceOverrunException(
                    "Drawing " + n + " bytes would exceed buffer capacity limit of " + MAX_BUFFER_SIZE + " bytes"
                );
            }

            // Generate n random bytes from SplittableRandom (nextInt draws 32-bit int, cast to byte)
            byte[] drawn = new byte[n];
            for (int i = 0; i < n; i++) {
                drawn[i] = (byte) random.nextInt();
            }

            // Record drawn bytes into ByteArrayOutputStream for future replay/saving
            recordingBuffer.write(drawn, 0, n);
            return drawn.clone();
        }
    }

    /**
     * Draws a 4-byte integer using big-endian byte layout.
     * <p>
     * <b>Bit Manipulation Breakdown:</b><br>
     * Bitwise AND {@code & 0xFF} promotes each signed byte to an unsigned integer (0–255).
     * Bitwise left-shifts ({@code << 24}, {@code << 16}, {@code << 8}) position each byte into its
     * corresponding 8-bit segment of the 32-bit integer, combined with bitwise OR ({@code |}).
     *
     * @return the reconstructed 32-bit signed integer
     * @throws IllegalStateException if frozen
     * @throws DataSourceOverrunException if buffer cap is exceeded
     */
    public int drawInt() {
        byte[] bytes = drawBytes(4);
        return ((bytes[0] & 0xFF) << 24) |
               ((bytes[1] & 0xFF) << 16) |
               ((bytes[2] & 0xFF) << 8)  |
                (bytes[3] & 0xFF);
    }

    /**
     * Draws an 8-byte long integer using big-endian byte layout.
     * <p>
     * <b>Bit Manipulation Breakdown:</b><br>
     * Cast to {@code (long)} before bitwise AND {@code & 0xFF} prevents integer overflow during 64-bit shifting.
     * Shifting by 56, 48, 40, 32, 24, 16, and 8 bits places each byte into its 64-bit slot, combined with bitwise OR.
     *
     * @return the reconstructed 64-bit signed long
     * @throws IllegalStateException if frozen
     * @throws DataSourceOverrunException if buffer cap is exceeded
     */
    public long drawLong() {
        byte[] bytes = drawBytes(8);
        return (((long) bytes[0] & 0xFF) << 56) |
               (((long) bytes[1] & 0xFF) << 48) |
               (((long) bytes[2] & 0xFF) << 40) |
               (((long) bytes[3] & 0xFF) << 32) |
               (((long) bytes[4] & 0xFF) << 24) |
               (((long) bytes[5] & 0xFF) << 16) |
               (((long) bytes[6] & 0xFF) << 8)  |
                ((long) bytes[7] & 0xFF);
    }

    /**
     * Draws a 1-byte boolean value.
     * <p>
     * <b>Bit Manipulation Breakdown:</b><br>
     * Bitwise AND {@code bytes[0] & 1} extracts the least significant bit (0 or 1), returning {@code true}
     * if the LSB is 1 and {@code false} if 0.
     *
     * @return {@code true} or {@code false} based on drawn byte LSB
     * @throws IllegalStateException if frozen
     * @throws DataSourceOverrunException if buffer cap is exceeded
     */
    public boolean drawBoolean() {
        byte[] bytes = drawBytes(1);
        return (bytes[0] & 1) != 0;
    }

    // --- Span recording ---

    /**
     * Opens a new span starting at the current cursor position.
     * <p>
     * <b>Stack Mechanics:</b><br>
     * The new {@link Span} captures the current byte offset as its start index and is pushed onto
     * {@code spanStack}. Any draws performed until {@link #endSpan()} will be encompassed by this span.
     *
     * @param label optional descriptive label (may be {@code null})
     * @throws IllegalStateException if the {@code DataSource} is frozen
     */
    public void startSpan(String label) {
        if (frozen) {
            throw new IllegalStateException("DataSource is frozen; no further spans permitted");
        }
        // Capture current byte position (recordingBuffer size or replayCursor)
        int cursor = currentCursorPosition();
        Span span = new Span(cursor, label);
        // Push open span onto the tracking stack
        spanStack.push(span);
    }

    /**
     * Closes the currently active span at the current cursor position.
     * <p>
     * <b>Stack Discipline & Hierarchy:</b><br>
     * Pops the top span from {@code spanStack} and sets its exclusive ending byte index.
     * <ul>
     *   <li>If {@code spanStack} is now empty, the span is attached to {@code rootSpans}.</li>
     *   <li>If another span remains on {@code spanStack}, the popped span is added as a child of that enclosing parent.</li>
     * </ul>
     *
     * @throws IllegalStateException if {@code endSpan()} is called when no span is open (stack empty)
     */
    public void endSpan() {
        if (spanStack.isEmpty()) {
            // Unmatched endSpan call indicates a nesting or lifecycle bug in generator code
            throw new IllegalStateException("endSpan() called with no matching startSpan()");
        }
        // Pop the most recently opened span from the stack
        Span span = spanStack.pop();
        // Record exclusive end position at current cursor location
        span.close(currentCursorPosition());

        if (spanStack.isEmpty()) {
            // Top-level span with no enclosing parent
            rootSpans.add(span);
        } else {
            // Attach as a nested child of the enclosing parent span on top of the stack
            spanStack.peek().addChild(span);
        }
    }

    /**
     * Returns an unmodifiable list of all completed root-level spans recorded during the run.
     *
     * @return unmodifiable list of root {@link Span} instances
     */
    public List<Span> getRootSpans() {
        return Collections.unmodifiableList(rootSpans);
    }

    /**
     * Returns the total number of bytes drawn/consumed so far across generation or replay mode.
     * <p>
     * <b>Why this solves the "bytes consumed" gap:</b><br>
     * In replay mode, {@link #getRecordedBuffer()} throws because no recording buffer exists.
     * However, the shrinker (Phases 11–12) needs to know how many bytes a candidate run actually consumed
     * to trim unused trailing bytes. {@code totalBytesConsumed()} exposes this directly in both modes.
     *
     * @return total bytes consumed up to the current moment
     */
    public int totalBytesConsumed() {
        return currentCursorPosition();
    }

    /**
     * Marks the {@code DataSource} as complete and frozen against further modifications.
     *
     * @throws IllegalStateException if any spans remain unclosed on {@code spanStack} (surfaces unclosed span bugs immediately)
     */
    public void freeze() {
        if (!spanStack.isEmpty()) {
            Span dangling = spanStack.peek();
            throw new IllegalStateException(
                "Cannot freeze DataSource: " + spanStack.size() + " unclosed span(s) remain "
                + "(top: label='" + dangling.getLabel() + "', start=" + dangling.getStart() + ")"
            );
        }
        this.frozen = true;
    }

    /**
     * Returns a defensive copy of all bytes recorded so far in generation mode.
     *
     * @return a new byte array containing recorded bytes
     * @throws IllegalStateException if called while in replay mode (no recording buffer present)
     */
    public byte[] getRecordedBuffer() {
        if (recordingBuffer == null) {
            throw new IllegalStateException("DataSource is in replay mode; no recording buffer available");
        }
        return recordingBuffer.toByteArray();
    }

    /**
     * Returns the current validity status of the {@code DataSource}.
     *
     * @return current {@link Status}
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Marks the status of this {@code DataSource} as {@link Status#INVALID}.
     * Called when property filter predicates or precondition checks reject a generated test case.
     */
    public void markInvalid() {
        this.status = Status.INVALID;
    }

    // --- Internal helpers ---

    /**
     * Unified internal helper returning the current byte cursor offset.
     * <ul>
     *   <li>Generation Mode: returns total bytes written to {@code recordingBuffer}.</li>
     *   <li>Replay Mode: returns current read position {@code replayCursor}.</li>
     * </ul>
     *
     * @return current byte cursor position
     */
    private int currentCursorPosition() {
        if (replayBuffer != null) {
            return replayCursor;
        } else {
            return recordingBuffer.size();
        }
    }
}
