package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;

import java.util.function.Function;
import java.util.function.Predicate;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 * DESIGN DECISIONS: D3 (Bounded-Integer Encoding) & D4 (Shrink Targets)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * D3 — Bounded-Integer Encoding (Multiplicative Scaling)
 * ──────────────────────────────────────────────────────
 * The core challenge: how to map raw random bytes to a bounded integer range
 * [min, max] such that lexicographically smaller bytes always produce values
 * closer to the shrink target (min).
 *
 * REJECTED: Modulo encoding (offset = rawUnsigned % range).
 *   Modulo wraps at every multiple of range, not just at the top of uint32
 *   space. For range=10: raw=9 → offset 9, raw=10 → offset 0. That's a
 *   monotonicity violation at every cycle boundary — hundreds of millions of
 *   wrap points for small ranges. The shrinker would silently produce larger
 *   values when trying to reduce bytes, making shrinking unreliable.
 *
 * CHOSEN: Multiplicative scaling.
 *   offset = (int) (((long) rawUnsigned * (long) range) >>> 32)
 *
 *   This computes floor(rawUnsigned × range / 2^32), a linear scaling function.
 *   Why it is monotonic across the full uint32 input range:
 *     The underlying continuous function g(x) = x × range / 2^32 is strictly
 *     increasing because range > 0 and 2^32 > 0 — both are positive constants,
 *     so the slope range/2^32 is always positive. Taking floor() of a non-
 *     decreasing continuous function yields a non-decreasing step function:
 *     floor(g(x+1)) ≥ floor(g(x)) always holds. There are ZERO wraparound
 *     points anywhere in the input domain — the function is a staircase that
 *     only goes up or stays flat, never drops.
 *
 *   Boundary verification:
 *     raw = 0         → offset = floor(0 × range / 2^32)         = 0        → min
 *     raw = 2^32 - 1  → offset = floor((2^32-1) × range / 2^32) = range-1  → max
 *
 *   No bias correction needed: each offset value is hit by either
 *   floor(2^32/range) or ceil(2^32/range) raw inputs — maximum bucket-size
 *   difference is 1, which is the theoretical minimum.
 *
 * D4 — Shrink Targets
 * ───────────────────
 * Every encoding is designed so that an all-zero byte buffer produces the
 * simplest possible value for that type. The shrinker relies on this:
 * "lexicographically smaller bytes ⇒ simpler value."
 *
 *   boolean        → false   (byte 0x00 → false)
 *   int (unbounded)→ 0       (4 zero bytes → 0)
 *   int(min, max)  → min     (4 zero bytes → offset 0 → min)
 *   long           → 0L      (8 zero bytes → 0L)
 *   char           → ' '     (space, 0x20; offset 0 from base ' ')
 *   double         → 0.0     (8 zero bytes → IEEE 754 positive zero)
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */

/**
 * A generator of random values of type {@code T}, drawing bytes from a {@link DataSource}.
 *
 * <p><b>Core Invariant:</b> Every {@code Generator<T>} implementation must satisfy the property
 * that <em>lexicographically smaller byte buffers produce simpler values</em>. Specifically,
 * if buffer A is lexicographically less than or equal to buffer B (both of the same length),
 * then generating from A must produce a value that is "no larger" than the value generated
 * from B, according to that type's natural shrink ordering (toward the shrink target defined
 * in D4 above).
 *
 * <p>This invariant is what makes JHusk's byte-level shrinker work: reducing bytes in the
 * buffer is guaranteed to produce simpler values, so the shrinker can minimize failing test
 * cases by systematically reducing the byte buffer.
 *
 * @param <T> the type of value this generator produces
 */
@FunctionalInterface
public interface Generator<T> {

    /**
     * Generates a value of type {@code T} by drawing bytes from the given {@link DataSource}.
     *
     * <p>Implementations must:
     * <ul>
     *   <li>Wrap all byte draws in {@code source.startSpan(label)} / {@code source.endSpan()}
     *       so that the span tree records the byte boundaries of each generated value.</li>
     *   <li>Preserve the shrink-monotonicity invariant: smaller raw bytes → simpler values.</li>
     * </ul>
     *
     * @param source the byte source to draw from (generation or replay mode)
     * @return the generated value
     */
    T generate(DataSource source);

    /**
     * Maximum number of attempts {@link #filter(Predicate)} makes before giving up and marking
     * the {@link DataSource} run {@link DataSource.Status#INVALID}.
     */
    int FILTER_RETRY_BUDGET = 100;

    /**
     * Returns a generator that applies {@code f} to every value this generator produces.
     *
     * <p>No shrink logic needed: {@code map} only transforms the output of an already-shrinkable
     * draw, so shrinking still operates on the same underlying bytes.
     *
     * @param f the transformation to apply to generated values
     * @param <R> the resulting value type
     * @return a generator producing {@code f.apply(value)} for each underlying value
     */
    default <R> Generator<R> map(Function<T, R> f) {
        return source -> f.apply(generate(source));
    }

    /**
     * Returns a generator that retries drawing from this generator until {@code pred} accepts the
     * value, up to {@link #FILTER_RETRY_BUDGET} attempts.
     *
     * <p>If the budget is exhausted, the run is marked {@link DataSource.Status#INVALID} via
     * {@link DataSource#markInvalid()} instead of throwing, consistent with how JHusk treats
     * rejected runs elsewhere (R4).
     *
     * @param pred the predicate a generated value must satisfy
     * @return a filtering generator
     */
    default Generator<T> filter(Predicate<T> pred) {
        return source -> {
            source.startSpan("filter");
            try {
                for (int attempt = 0; attempt < FILTER_RETRY_BUDGET; attempt++) {
                    T value = generate(source);
                    if (pred.test(value)) {
                        return value;
                    }
                }
                source.markInvalid();
                return null;
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator that uses a value from this generator to produce and immediately run a
     * second, dependent generator.
     *
     * <p>Dependent generation is nearly free under this byte-stream architecture (unlike
     * integrated/tree-based shrinking): both draws just continue consuming the same
     * {@link DataSource} sequentially, so no extra machinery is needed to keep them in sync.
     *
     * @param f produces the next generator from this generator's value
     * @param <R> the resulting value type
     * @return a generator combining both draws
     */
    default <R> Generator<R> flatMap(Function<T, Generator<R>> f) {
        return source -> f.apply(generate(source)).generate(source);
    }
}
