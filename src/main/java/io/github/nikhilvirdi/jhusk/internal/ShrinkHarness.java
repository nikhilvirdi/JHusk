package io.github.nikhilvirdi.jhusk.internal;

import io.github.nikhilvirdi.jhusk.Generator;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Evaluates candidate byte buffers during the shrinking process.
 * 
 * <p>This harness is pure evaluation scaffolding. It does not search for shrinks
 * or track the "best" buffer (that is Phase 12's job). It takes a candidate,
 * replays it through the generator and assertion, and definitively answers:
 * "does this candidate reproduce the original failure?"
 * 
 * @param <T> the type of value generated and tested
 */
public class ShrinkHarness<T> {

    /**
     * Compares two byte arrays in shortlex order:
     * 1. Shorter arrays sort first.
     * 2. For equal-length arrays, sorts lexicographically (unsigned byte comparison).
     * 
     * @param a the first array
     * @param b the second array
     * @return a negative integer, zero, or a positive integer if a is less than, equal to, or greater than b
     */
    public static int compareShortlex(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return Integer.compare(a.length, b.length);
        }
        for (int i = 0; i < a.length; i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Exception thrown when the harness exceeds its attempt budget.
     */
    public static class AttemptBoundExceededException extends RuntimeException {
        /**
         * Constructs a new AttemptBoundExceededException with the specified detail message.
         *
         * @param message the detail message
         */
        public AttemptBoundExceededException(String message) {
            super(message);
        }
    }

    /**
     * Immutable wrapper for byte[] that implements value-based equals and hashCode, so it can be
     * safely used as a key in Sets/Maps without array copying or string allocation.
     *
     * <p>Package-private: this is purely an implementation detail of {@link #attempted}'s
     * dedup tracking below, with no reason to be constructed or referenced from outside this
     * class -- narrower visibility than {@link ShrinkHarness} itself, which some tests reference
     * directly for white-box testing.
     */
    static final class BufferKey {
        private final byte[] bytes;
        private final int hashCode;

        BufferKey(byte[] bytes) {
            this.bytes = bytes;
            this.hashCode = Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BufferKey bufferKey = (BufferKey) o;
            return Arrays.equals(bytes, bufferKey.bytes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private final Generator<T> generator;
    private final Consumer<T> assertion;
    private final Class<? extends Throwable> originalFailureClass;

    private final Set<BufferKey> attempted = new HashSet<>();
    private final int maxAttempts;
    private int attempts = 0;
    private List<Span> lastAcceptedRootSpans = Collections.emptyList();

    /**
     * Constructs a ShrinkHarness.
     *
     * @param generator the generator that produced the original failure
     * @param assertion the property assertion that originally failed
     * @param originalFailureClass the exact class of the exception thrown by the original failure
     * @param maxAttempts the maximum number of times {@code tryBuffer} can be called before aborting
     */
    public ShrinkHarness(Generator<T> generator, Consumer<T> assertion, 
                         Class<? extends Throwable> originalFailureClass, int maxAttempts) {
        this.generator = generator;
        this.assertion = assertion;
        this.originalFailureClass = originalFailureClass;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Constructs a ShrinkHarness with a default maximum of 5,000 attempts.
     *
     * @param generator the generator that produced the original failure
     * @param assertion the property assertion that originally failed
     * @param originalFailureClass the exact class of the exception thrown by the original failure
     */
    public ShrinkHarness(Generator<T> generator, Consumer<T> assertion, 
                         Class<? extends Throwable> originalFailureClass) {
        this(generator, assertion, originalFailureClass, 5000);
    }

    /**
     * Evaluates a candidate buffer to see if it cleanly reproduces the original failure.
     * 
     * @param candidate the byte buffer to evaluate
     * @return true if the candidate failed with the exact same exception class; false otherwise
     * @throws AttemptBoundExceededException if maxAttempts has been reached
     */
    public boolean tryBuffer(byte[] candidate) {
        if (attempts >= maxAttempts) {
            throw new AttemptBoundExceededException("ShrinkHarness exceeded attempt budget of " + maxAttempts);
        }

        BufferKey key = new BufferKey(candidate);
        if (!attempted.add(key)) {
            // Already attempted this exact buffer; save the execution cost and reject it.
            // A duplicate is not a "new" successful shrink anyway.
            return false;
        }

        attempts++;

        DataSource source = new DataSource(candidate);
        T value;
        try {
            value = generator.generate(source);
        } catch (DataSourceOverrunException e) {
            // Buffer didn't have enough bytes to fulfill the generator. Reject per R4.
            return false;
        } catch (Throwable t) {
            // The generator itself crashed on this buffer (not the assertion).
            // Unless this crash *was* the original failure we are shrinking, reject it.
            //
            // Also require VALID status here (R4): a generator can throw on OVERRUN/INVALID
            // garbage for reasons unrelated to the real bug -- e.g. a custom generator that
            // validates its own drawn value and throws when the buffer ran out. Without this
            // check, an exception class that happens to coincide with originalFailureClass would
            // be accepted as a "reproduction" even though it was only ever exercising zero-padded
            // OVERRUN bytes, not a genuine minimal failing case.
            boolean accepted = source.getStatus() == DataSource.Status.VALID
                    && t.getClass().equals(originalFailureClass);
            if (accepted) {
                lastAcceptedRootSpans = source.getRootSpans();
            }
            return accepted;
        }

        if (source.getStatus() != DataSource.Status.VALID) {
            // Explicit rejections and replay overruns are never valid shrink candidates.
            return false;
        }

        try {
            assertion.accept(value);
            // The assertion passed! This candidate doesn't reproduce the bug. Reject.
            return false;
        } catch (Throwable t) {
            // The assertion failed. Check if it's the SAME kind of failure.
            // We match by class only, not message, because messages contain dynamic
            // values that shrink (e.g., "Expected < 10 but was 99").
            boolean accepted = t.getClass().equals(originalFailureClass);
            if (accepted) {
                lastAcceptedRootSpans = source.getRootSpans();
            }
            return accepted;
        }
    }

    /**
     * Returns the total number of unique buffers evaluated.
     *
     * @return the number of unique candidates evaluated
     */
    public int getAttempts() {
        return attempts;
    }

    /**
     * Returns spans from the most recently accepted candidate. This metadata is
     * captured by {@link #tryBuffer(byte[])}, so callers never replay candidates.
     *
     * @return the list of root spans recorded during the last accepted candidate run
     */
    public List<Span> getLastAcceptedRootSpans() {
        return lastAcceptedRootSpans;
    }
}
