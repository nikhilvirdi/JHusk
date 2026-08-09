package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Factory class providing primitive {@link Generator} instances for JHusk's
 * property-based testing framework.
 *
 * <p>Every generator wraps its byte draws in {@code startSpan(label)} / {@code endSpan()}
 * so the span tree records byte boundaries for each generated value.
 *
 * <p>All encodings satisfy the shrink-monotonicity invariant: lexicographically smaller
 * bytes produce simpler values (closer to the type's shrink target). See the design
 * decision comment block in {@link Generator} for full D3/D4 rationale.
 */
public final class Generators {

    private Generators() {
        // Static factory class — no instantiation
    }

    /**
     * Returns a generator of {@code boolean} values.
     *
     * <p><b>Encoding:</b> Draws 1 byte via {@link DataSource#drawBoolean()}. Byte {@code 0x00} →
     * {@code false} (shrink target). Any nonzero byte → {@code true}. This is monotonic by
     * construction: 0 is the unique lexicographic minimum and the only value producing
     * {@code false}; every larger byte value produces {@code true}.
     *
     * <p><b>Span:</b> labeled {@code "bool"}, 1 byte wide.
     *
     * @return a boolean generator
     */
    public static Generator<Boolean> booleans() {
        return source -> {
            source.startSpan("bool");
            try {
                return source.drawBoolean();
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator of unbounded {@code int} values spanning the full 32-bit signed range.
     *
     * <p><b>Encoding:</b> Draws 4 bytes in big-endian layout. All-zero bytes → 0 (shrink target).
     * Big-endian means lexicographic byte order matches unsigned integer magnitude order:
     * smaller bytes → smaller unsigned value → value closer to zero.
     *
     * <p><b>Span:</b> labeled {@code "int"}, 4 bytes wide.
     *
     * @return an unbounded integer generator
     */
    public static Generator<Integer> integers() {
        return source -> {
            source.startSpan("int");
            try {
                // 4 bytes big-endian; all-zero bytes → 0 (shrink target D4)
                return source.drawInt();
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator of bounded {@code int} values in the range {@code [min, max]}.
     *
     * <p><b>Encoding (D3 — Multiplicative Scaling):</b>
     * <ol>
     *   <li>Draw 4 raw bytes and interpret as an unsigned 32-bit big-endian integer.</li>
     *   <li>Compute {@code offset = (int)(((long) rawUnsigned * (long) range) >>> 32)}
     *       where {@code range = (long) max - (long) min + 1}.</li>
     *   <li>Result: {@code min + offset}.</li>
     * </ol>
     *
     * <p><b>Monotonicity:</b> The function {@code floor(raw × range / 2^32)} is a linear
     * scaling followed by floor — a non-decreasing staircase with zero wraparound points.
     * All-zero bytes → offset 0 → {@code min} (shrink target D4 for bounded ranges).
     *
     * <p><b>Span:</b> labeled {@code "int"}, 4 bytes wide.
     *
     * @param min minimum value (inclusive)
     * @param max maximum value (inclusive)
     * @return a bounded integer generator
     * @throws IllegalArgumentException if min &gt; max
     */
    public static Generator<Integer> integers(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
        }
        // Pre-compute range as long to handle full int range without overflow
        // e.g. integers(Integer.MIN_VALUE, Integer.MAX_VALUE) → range = 2^32
        final long range = (long) max - (long) min + 1;

        return source -> {
            source.startSpan("int");
            try {
                // Draw 4 raw bytes and interpret as unsigned 32-bit big-endian integer.
                // DataSource.drawInt() returns signed int; we need unsigned interpretation.
                int rawSigned = source.drawInt();
                long rawUnsigned = Integer.toUnsignedLong(rawSigned);

                // Special case: when range == 2^32 (full int range), rawUnsigned * range
                // would overflow long. But range == 2^32 means every uint32 maps to a
                // unique offset (identity), so offset = rawUnsigned directly.
                if (range == (1L << 32)) {
                    return min + (int) rawUnsigned;
                }

                // Multiplicative scaling (D3):
                // offset = floor(rawUnsigned × range / 2^32)
                //
                // The multiplication is safe in long: rawUnsigned max is 2^32-1 (~4.29e9),
                // range max is 2^32-1 (when range < 2^32, handled here), so the product
                // max is (2^32-1)^2 ≈ 1.8×10^19, which fits in long (max ~9.2×10^18).
                // Wait — (2^32-1)^2 = 2^64 - 2^33 + 1 which DOES overflow long.
                // But range < 2^32 here (the == case is handled above), and practically
                // range ≤ 2^32 - 1 when max - min < 2^32 - 1. For range up to 2^31,
                // product max is (2^32-1) * 2^31 ≈ 4.6×10^18, safe in long.
                // For range in (2^31, 2^32), we use Math.multiplyHigh or manual split:
                //
                // To be safe for ALL range values < 2^32, we split the multiplication:
                //   rawUnsigned * range = rawUnsigned * rangeLow16 + rawUnsigned * rangeHigh16 * 2^16
                // But a simpler approach: since both rawUnsigned and range fit in unsigned 32 bits
                // (i.e., range < 2^32), the product fits in 64 unsigned bits. Java's long is
                // signed 64-bit, so if the product exceeds 2^63-1 it wraps negative. However,
                // >>> 32 on a long does an unsigned right shift, correctly extracting the upper
                // 32 bits regardless of the sign bit. So the formula works even when the product
                // "overflows" to negative — >>> treats it as unsigned.
                int offset = (int) ((rawUnsigned * range) >>> 32);

                return min + offset;
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator of unbounded {@code long} values spanning the full 64-bit signed range.
     *
     * <p><b>Encoding:</b> Draws 8 bytes in big-endian layout. All-zero bytes → 0L (shrink target).
     *
     * <p><b>Span:</b> labeled {@code "long"}, 8 bytes wide.
     *
     * @return an unbounded long generator
     */
    public static Generator<Long> longs() {
        return source -> {
            source.startSpan("long");
            try {
                // 8 bytes big-endian; all-zero bytes → 0L (shrink target D4)
                return source.drawLong();
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator of printable ASCII characters in the range {@code [' ', '~']}
     * (code points 0x20 through 0x7E, 95 characters).
     *
     * <p><b>Character Range Justification:</b> Printable ASCII covers all common test-relevant
     * characters (letters, digits, punctuation, space) without the complexity of full Unicode.
     * This range is sufficient for the vast majority of property-based string testing scenarios.
     * A future {@code characters(char min, char max)} overload can extend this if needed.
     *
     * <p><b>Encoding:</b> Uses the same multiplicative scaling as bounded integers with
     * {@code min=' '} (0x20) and {@code max='~'} (0x7E), but draws only 2 bytes since
     * the range (95) fits easily in 16 bits, saving buffer space for string generation.
     * All-zero bytes → {@code ' '} (space, shrink target D4 for characters).
     *
     * <p><b>Span:</b> labeled {@code "char"}, 2 bytes wide.
     *
     * @return a printable ASCII character generator
     */
    public static Generator<Character> characters() {
        // Printable ASCII range: ' ' (0x20) through '~' (0x7E) = 95 characters
        final int charMin = ' ';  // 0x20 = 32
        final int charMax = '~';  // 0x7E = 126
        final long range = charMax - charMin + 1; // 95

        return source -> {
            source.startSpan("char");
            try {
                // Draw 2 bytes (16 bits) — sufficient for 95-char range.
                // Interpret as unsigned 16-bit big-endian value.
                byte[] bytes = source.drawBytes(2);
                int rawUnsigned = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
                long rawUnsignedLong = rawUnsigned & 0xFFFFL;

                // Multiplicative scaling over 16-bit space (analogous to D3 over 32-bit):
                // offset = floor(rawUnsigned × range / 2^16)
                int offset = (int) ((rawUnsignedLong * range) >>> 16);

                return (char) (charMin + offset);
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator of {@code double} values.
     *
     * <p><b>Encoding:</b> Draws 8 bytes, interprets them as an IEEE 754 double via
     * {@link Double#longBitsToDouble(long)}. All-zero bytes → {@code 0.0} (shrink target D4).
     *
     * <p><b>⚠ FLOATING-POINT WEAKNESS (D1 Acknowledged):</b>
     * Doubles are the acknowledged weak point of a raw-byte intermediate representation.
     * IEEE 754 bit patterns do NOT shrink smoothly toward simple decimal values the way
     * integers do. Reducing bytes in the buffer produces arbitrary floating-point values
     * rather than converging toward "nice" numbers like 0.5 or 1.0. Specific problems:
     * <ul>
     *   <li>The sign bit, exponent, and mantissa fields have different shrink semantics —
     *       reducing the exponent byte might increase the mantissa's apparent value.</li>
     *   <li>Denormalized numbers, NaN, and ±Infinity are reachable by byte reduction and
     *       don't correspond to "simpler" values in any human-useful sense.</li>
     *   <li>Hypothesis (Python) solves this with a post-hoc float-specific shrinker that
     *       knows IEEE 754 structure. JHusk will likely need the same in later phases.</li>
     * </ul>
     * For now, this generator produces valid doubles from raw bytes, and the shrinker
     * will at least converge toward 0.0 (all-zero bytes), but intermediate shrink steps
     * may produce seemingly random floating-point values.
     *
     * <p><b>Span:</b> labeled {@code "double"}, 8 bytes wide.
     *
     * @return a double generator
     */
    public static Generator<Double> doubles() {
        return source -> {
            source.startSpan("double");
            try {
                // 8 bytes big-endian → long bits → IEEE 754 double
                // All-zero bytes → 0L → Double.longBitsToDouble(0L) = 0.0 (shrink target D4)
                long bits = source.drawLong();
                return Double.longBitsToDouble(bits);
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Combines two generators into one, applying {@code f} to their generated values.
     *
     * <p><b>Span:</b> labeled {@code "combine"}, wrapping both component draws — their own
     * spans nest inside it as children.
     *
     * @param a the first generator
     * @param b the second generator
     * @param f combines the two generated values into a result
     * @param <A> first argument type
     * @param <B> second argument type
     * @param <R> result type
     * @return a generator producing {@code f.apply(a's value, b's value)}
     */
    public static <A, B, R> Generator<R> combine(Generator<A> a, Generator<B> b, BiFunction<A, B, R> f) {
        return source -> {
            source.startSpan("combine");
            try {
                A valueA = a.generate(source);
                B valueB = b.generate(source);
                return f.apply(valueA, valueB);
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Combines three generators into one, applying {@code f} to their generated values.
     *
     * <p><b>Span:</b> labeled {@code "combine"}, wrapping all three component draws — their own
     * spans nest inside it as children.
     *
     * @param a the first generator
     * @param b the second generator
     * @param c the third generator
     * @param f combines the three generated values into a result
     * @param <A> first argument type
     * @param <B> second argument type
     * @param <C> third argument type
     * @param <R> result type
     * @return a generator producing {@code f.apply(a's value, b's value, c's value)}
     */
    public static <A, B, C, R> Generator<R> combine(Generator<A> a, Generator<B> b, Generator<C> c, Function3<A, B, C, R> f) {
        return source -> {
            source.startSpan("combine");
            try {
                A valueA = a.generate(source);
                B valueB = b.generate(source);
                C valueC = c.generate(source);
                return f.apply(valueA, valueB, valueC);
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator that draws a selector and delegates to one of {@code alternatives}.
     *
     * <p><b>Encoding:</b> Draws 4 bytes and scales them to an index in
     * {@code [0, alternatives.length)} using the same multiplicative scaling as bounded integers
     * (D3) — never modulo, which wraps at every multiple of the alternative count and breaks
     * shrink monotonicity. All-zero bytes scale to index 0, making the first alternative the
     * shrink target (D4).
     *
     * <p><b>Span:</b> labeled {@code "oneOf"}, wrapping the selector draw and the chosen
     * alternative's own draw (and its nested spans) as children.
     *
     * @param alternatives the candidate generators to choose from (at least one required)
     * @param <T> the common value type
     * @return a generator that delegates to one of {@code alternatives}
     * @throws IllegalArgumentException if {@code alternatives} is empty
     */
    @SafeVarargs
    public static <T> Generator<T> oneOf(Generator<T>... alternatives) {
        if (alternatives.length == 0) {
            throw new IllegalArgumentException("oneOf requires at least one alternative");
        }
        final long range = alternatives.length;

        return source -> {
            source.startSpan("oneOf");
            try {
                // Same multiplicative scaling as integers(min, max) (D3):
                // offset = floor(rawUnsigned × range / 2^32). All-zero bytes → index 0 (D4).
                int rawSigned = source.drawInt();
                long rawUnsigned = Integer.toUnsignedLong(rawSigned);
                int index = (int) ((rawUnsigned * range) >>> 32);
                return alternatives[index].generate(source);
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator that draws zero bytes and always returns {@code value}.
     *
     * @param value the constant value to return
     * @param <T> the value type
     * @return a constant generator
     */
    public static <T> Generator<T> just(T value) {
        return source -> value;
    }

    /**
     * Alias for {@link #just(Object)}.
     *
     * @param value the constant value to return
     * @param <T> the value type
     * @return a constant generator
     */
    public static <T> Generator<T> constant(T value) {
        return just(value);
    }

    /**
     * A three-argument analogue of {@link BiFunction}, used by the 3-arity
     * {@link #combine(Generator, Generator, Generator, Function3)} overload.
     *
     * @param <A> first argument type
     * @param <B> second argument type
     * @param <C> third argument type
     * @param <R> result type
     */
    @FunctionalInterface
    public interface Function3<A, B, C, R> {
        /**
         * Applies this function to the given arguments.
         *
         * @param a the first argument
         * @param b the second argument
         * @param c the third argument
         * @return the function result
         */
        R apply(A a, B b, C c);
    }

    /*
     * ═══════════════════════════════════════════════════════════════════════════════
     * DESIGN DECISION: D5 (Collection Encoding)
     * ═══════════════════════════════════════════════════════════════════════════════
     *
     * The core challenge: how to encode a variable-length collection into the byte
     * stream such that deleting bytes belonging to one element leaves the rest of the
     * buffer correctly interpretable (R1 — spans exist precisely to make this possible).
     *
     * REJECTED: Length-prefix encoding (draw a count N up front, then N elements).
     *   Shrinks terribly. If the shrinker later deletes the bytes belonging to one
     *   element (e.g. via span deletion), the length prefix drawn up front no longer
     *   matches the number of elements actually present in the remaining bytes — every
     *   element after the deleted one is desynchronized, reinterpreting bytes that
     *   belonged to a different element as its own. The length is a promise made
     *   before the shrinker has any say in the buffer, and byte deletion breaks it.
     *
     * CHOSEN: Continuation-flag encoding.
     *   Before each candidate element, draw a single flag: nonzero → continue (draw
     *   and emit one more element), zero → stop (the collection ends here). The flag
     *   is drawn INSIDE the same span as the element it gates, so "one element" maps
     *   to exactly one contiguous, self-describing span: [flag byte][element bytes].
     *   Deleting that whole span removes the choice-to-continue and the payload
     *   together, so the next span's flag byte simply becomes the next thing read —
     *   no desync, no dangling length to reconcile. This is D5 and R1 (spans) working
     *   as a single mechanism: the span *is* the deletable unit the shrinker needs.
     *
     * Shrink target (D4): the flag reuses the same nonzero=true/zero=false convention
     * as booleans() below, so all-zero bytes → every flag reads false immediately →
     * the empty (or minimum-size) list falls out for free, with no special-casing.
     *
     * INTERACTION WITH D6 (DataSource.MAX_BUFFER_SIZE, 8192 bytes):
     *   Two consequences of this encoding compound against the buffer cap for large or
     *   deeply-nested bounds:
     *   (1) The mandatory minSize prefix draws no flag at all -- it cannot stop early --
     *       so minSize alone must fit under the buffer cap. lists(integers(), 40_000, 50_000)
     *       needs 160,000 bytes (4 bytes/int × 40,000) just for the mandatory elements,
     *       ~20x the 8192-byte cap: every single attempt overruns, deterministically,
     *       regardless of seed. This isn't a probability tail -- it's structurally impossible.
     *   (2) The continuation flag is a raw random byte, "nonzero" (255/256 ≈ 99.6%) to
     *       continue. That means optional elements are heavily biased toward maxSize rather
     *       than averaging small, and this bias compounds across nesting (a list of maps,
     *       each map a list of entries, each entry's value a string that's itself a list of
     *       chars): every level tends toward its own max independently, multiplying the
     *       total byte cost. See {@link DataSourceOverrunException}.
     *   Neither is a bug in isolation -- D5 needs spans to be independently deletable, D6
     *   needs a hard cap to bound worst-case memory -- but together they mean minSize ×
     *   (min per-element byte width) must stay well under MAX_BUFFER_SIZE, and maxSize
     *   should be sized assuming elements usually reach it, not average out low.
     * ═══════════════════════════════════════════════════════════════════════════════
     */

    /** Default upper bound on list length for {@link #lists(Generator)} when no bound is given. */
    static final int DEFAULT_LIST_MAX_SIZE = 100;

    /**
     * Returns a generator of {@code List<T>} with length in {@code [0, 100]}.
     *
     * <p>100 is a fixed default chosen to keep generated collections small enough to be readable
     * in failure reports while still exercising realistic sizes; use
     * {@link #lists(Generator, int, int)} for an explicit bound.
     *
     * <p><b>Note on large collections:</b> Generating very large lists (roughly above the point
     * where the per-element byte cost times the max size approaches ~8KB) may exceed the internal
     * {@link io.github.nikhilvirdi.jhusk.internal.DataSource#MAX_BUFFER_SIZE} cap and cause a
     * {@link PropertyExecutionException} during generation.
     *
     * @param elementGen generator for each element
     * @param <T> the element type
     * @return a list generator
     */
    public static <T> Generator<List<T>> lists(Generator<T> elementGen) {
        return lists(elementGen, 0, DEFAULT_LIST_MAX_SIZE);
    }

    /**
     * Returns a generator of {@code List<T>} with length in {@code [minSize, maxSize]}.
     *
     * <p><b>Encoding (D5 — Continuation-Flag):</b> see the design decision block above. The
     * first {@code minSize} elements are mandatory — no flag is drawn for them, since a fixed
     * count has no choice to encode. Every element beyond that is gated by a continuation flag
     * drawn inside its own span.
     *
     * <p><b>Span:</b> outer span labeled {@code "list"}. Each element (mandatory or optional)
     * gets its own {@code "list-element"} child span, which in turn nests that element
     * generator's own span — e.g. an {@code integers()} element produces
     * {@code list -> list-element -> int}. This is R1/Phase 6's payoff: one element's span
     * (flag + payload) is exactly the unit the shrinker needs to delete to remove that element
     * without corrupting anything after it. When the list stops before reaching {@code maxSize},
     * the final continuation-flag probe still opens its own (childless) {@code "list-element"}
     * span for just that one stop byte — harmless, since a childless span has nothing worth
     * deleting.
     *
     * <p><b>Buffer budget (see D5/D6 note above):</b> {@code minSize} elements are unconditional —
     * they cannot stop early — so {@code minSize} times the element's minimum byte width must stay
     * well under {@link io.github.nikhilvirdi.jhusk.internal.DataSource#MAX_BUFFER_SIZE} (8192
     * bytes) or every generation attempt overruns the buffer and counts as invalid, deterministically
     * exhausting {@link Property#maxInvalidRuns(int)} with zero successful runs — e.g.
     * {@code lists(integers(), 40_000, 50_000)} needs 160,000 bytes for its mandatory prefix alone.
     * Elements beyond {@code minSize} are biased toward {@code maxSize} (the continuation flag
     * stops only ~1/256 of the time), and this bias compounds across nested generators (lists of
     * strings, lists of maps, etc.), so budget {@code maxSize} assuming most runs approach it rather
     * than average out small. For collections too large to fit this budget, write a custom
     * {@link Generator} that draws a small seed and fills the collection with a local PRNG instead
     * of drawing one span per element.
     *
     * @param elementGen generator for each element
     * @param minSize minimum list length (inclusive), must be &gt;= 0
     * @param maxSize maximum list length (inclusive), must be &gt;= minSize
     * @param <T> the element type
     * @return a bounded list generator
     * @throws IllegalArgumentException if minSize &lt; 0 or minSize &gt; maxSize
     */
    public static <T> Generator<List<T>> lists(Generator<T> elementGen, int minSize, int maxSize) {
        if (minSize < 0) {
            throw new IllegalArgumentException("minSize cannot be negative: " + minSize);
        }
        if (minSize > maxSize) {
            throw new IllegalArgumentException("minSize (" + minSize + ") must be <= maxSize (" + maxSize + ")");
        }

        return source -> {
            source.startSpan("list");
            try {
                List<T> result = new ArrayList<>();

                // Mandatory prefix: always present, no continuation flag (no choice to encode).
                for (int i = 0; i < minSize; i++) {
                    source.startSpan("list-element");
                    try {
                        result.add(elementGen.generate(source));
                    } finally {
                        source.endSpan();
                    }
                }

                // Optional elements (D5): the flag lives inside the same span as the element
                // it gates, so flag+payload delete together as one unit.
                while (result.size() < maxSize) {
                    source.startSpan("list-element");
                    try {
                        // Same nonzero=true/zero=false convention as booleans() (D4).
                        boolean cont = source.drawBytes(1)[0] != 0;
                        if (!cont) {
                            break;
                        }
                        result.add(elementGen.generate(source));
                    } finally {
                        source.endSpan();
                    }
                }

                return result;
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator of {@code String} built compositionally on {@link #characters()} via
     * {@link #lists(Generator)} — a string is just a list of characters, so it inherits D5's
     * continuation-flag encoding and shrink behavior with no separate hand-rolled encoding.
     *
     * <p>Length follows {@link #lists(Generator)}'s default bound: {@code [0, 100]} characters,
     * each drawn from {@link #characters()}'s printable ASCII range {@code [' ', '~']}.
     *
     * @return a string generator
     */
    public static Generator<String> strings() {
        return lists(characters()).map(chars -> {
            StringBuilder sb = new StringBuilder(chars.size());
            for (char c : chars) {
                sb.append(c);
            }
            return sb.toString();
        });
    }

    /**
     * Returns a generator that wraps {@code gen}, drawing a single flag to decide between
     * {@code null} (absence) and a value from {@code gen} (presence).
     *
     * <p><b>Encoding:</b> Draws 1 byte using the same nonzero=true/zero=false convention as
     * {@link #booleans()}. All-zero bytes → absent (D4: absence is simpler than presence, so
     * {@code null} is the shrink target).
     *
     * <p><b>Null probability (documented, stable guarantee):</b> Exactly 1 of the 256 possible
     * unsigned byte values (0x00) maps to "absent" ({@code null}). The remaining 255 values map
     * to "present" (delegates to {@code gen}). This gives a null rate of exactly 1/256 ≈ 0.39%
     * and a present rate of exactly 255/256 ≈ 99.61%. This encoding is a fixed, committed
     * guarantee — not an implementation detail — because any change would silently alter the
     * replay behavior of existing {@code .jhusk/} stored-failure buffers. At the default 100
     * examples, the expected number of null draws is under 0.4; it is entirely normal to run
     * the full example budget without ever hitting the null branch. Use
     * {@link #optionals(Generator, double)} to configure a higher null probability when
     * null-handling coverage is important to your property.
     *
     * <p><b>Span:</b> labeled {@code "optional"}, wrapping the flag and, when present, {@code gen}'s
     * own nested span.
     *
     * @param gen the generator to run when a value is present
     * @param <T> the value type
     * @return a generator producing {@code null} or a value from {@code gen}
     */
    public static <T> Generator<T> optionals(Generator<T> gen) {
        return source -> {
            source.startSpan("optional");
            try {
                boolean present = source.drawBytes(1)[0] != 0;
                if (!present) {
                    return null;
                }
                return gen.generate(source);
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator that wraps {@code gen} with a configurable null probability.
     *
     * <p><b>Encoding:</b> Draws 1 byte via {@link DataSource#drawBytes(int) drawBytes(1)}, wrapped
     * in the same {@code startSpan("optional")} / {@code finally { endSpan(); }} structure as
     * {@link #optionals(Generator)}. The drawn byte is interpreted as an unsigned value in
     * {@code [0, 255]}. A threshold of {@code (int)(nullProbability × 256)} divides the byte
     * space: unsigned byte values {@code < threshold} map to absent ({@code null}); values
     * {@code >= threshold} map to present (delegates to {@code gen}). All-zero bytes always
     * map to the outcome with the lowest threshold — absent when {@code nullProbability > 0.0},
     * present when {@code nullProbability == 0.0} — preserving the D4 shrink-toward-all-zero
     * invariant.
     *
     * <p><b>Edge cases:</b>
     * <ul>
     *   <li>{@code nullProbability == 0.0} → threshold is 0; no byte value is ever {@code < 0},
     *       so {@code null} is never returned and {@code gen} is always invoked.</li>
     *   <li>{@code nullProbability == 1.0} → threshold is 256; every byte value (0-255) is
     *       {@code < 256}, so {@code null} is always returned and {@code gen} is never invoked.</li>
     * </ul>
     *
     * <p><b>Note:</b> This overload uses a different byte-encoding from the single-argument
     * {@link #optionals(Generator)} — the two are intentionally independent implementations.
     * Do not substitute one for the other in stored failure buffers.
     *
     * <p><b>Span:</b> labeled {@code "optional"}, wrapping the flag and, when present, {@code gen}'s
     * own nested span.
     *
     * @param gen the generator to run when a value is present
     * @param nullProbability the probability of returning {@code null}, in the range {@code [0.0, 1.0]}
     * @param <T> the value type
     * @return a generator producing {@code null} with the configured probability, or a value from {@code gen}
     * @throws IllegalArgumentException if {@code nullProbability} is outside {@code [0.0, 1.0]}
     */
    public static <T> Generator<T> optionals(Generator<T> gen, double nullProbability) {
        if (nullProbability < 0.0 || nullProbability > 1.0) {
            throw new IllegalArgumentException(
                "nullProbability must be in [0.0, 1.0] but was: " + nullProbability);
        }
        // Pre-compute threshold: how many of the 256 unsigned byte values map to absent.
        // Threshold 0 → never null (nullProbability == 0.0).
        // Threshold 256 → always null (nullProbability == 1.0).
        final int threshold = (int) (nullProbability * 256);

        return source -> {
            source.startSpan("optional");
            try {
                int unsigned = source.drawBytes(1)[0] & 0xFF;
                if (unsigned < threshold) {
                    return null;
                }
                return gen.generate(source);
            } finally {
                source.endSpan();
            }
        };
    }

    /**
     * Returns a generator of {@code Set<T>} built on {@link #lists(Generator)}.
     *
     * <p><b>Deduplication:</b> insertion order is preserved and later duplicate elements are
     * dropped — first occurrence wins ({@link LinkedHashSet}'s standard behavior).
     *
     * <p><b>Interaction with shrinking:</b> deduplication happens after the list is decoded, so
     * the <em>set's</em> size is not a direct function of the list buffer's size — deleting a
     * list element's span that happened to be a duplicate leaves the set's size unchanged even
     * though the underlying list shrank by one element. The shrinker ({@code Shrinker}/
     * {@code ShrinkHarness}) doesn't need to know this: it only ever re-runs the property
     * assertion against the fully-decoded {@code Set<T>} and checks whether the same failure
     * still reproduces, so "smaller list buffer" not always implying "smaller set" is harmless
     * by construction rather than something shrinking has to special-case.
     *
     * <p><b>Note on large collections:</b> Generating very large sets (roughly above the point
     * where the per-element byte cost times the max size approaches ~8KB) may exceed the internal
     * {@link io.github.nikhilvirdi.jhusk.internal.DataSource#MAX_BUFFER_SIZE} cap and cause a
     * {@link PropertyExecutionException} during generation.
     *
     * @param elementGen generator for each element
     * @param <T> the element type
     * @return a set generator
     */
    public static <T> Generator<Set<T>> sets(Generator<T> elementGen) {
        return lists(elementGen).map(list -> {
            Set<T> set = new LinkedHashSet<>(list);
            return set;
        });
    }

    /**
     * Returns a generator of {@code Map<K, V>} built on {@link #lists(Generator)} of key-value
     * pairs (via {@link #combine(Generator, Generator, BiFunction)}).
     *
     * <p><b>Deduplication:</b> later duplicate keys overwrite earlier ones' values, keeping the
     * key's original insertion position — standard {@link Map#put} semantics via
     * {@link LinkedHashMap}.
     *
     * <p><b>Interaction with shrinking:</b> as with {@link #sets(Generator)}, the map's size is
     * not a direct function of the entry list's size once duplicate keys collapse. This is
     * harmless for the same reason: the shrinker only ever observes the fully-decoded
     * {@code Map<K, V>} through the property assertion, never the intermediate entry list.
     *
     * <p><b>Note on large collections:</b> Generating very large maps (roughly above the point
     * where the per-entry byte cost times the max size approaches ~8KB) may exceed the internal
     * {@link io.github.nikhilvirdi.jhusk.internal.DataSource#MAX_BUFFER_SIZE} cap and cause a
     * {@link PropertyExecutionException} during generation.
     *
     * @param keyGen generator for each key
     * @param valGen generator for each value
     * @param <K> the key type
     * @param <V> the value type
     * @return a map generator
     */
    public static <K, V> Generator<Map<K, V>> maps(Generator<K> keyGen, Generator<V> valGen) {
        Generator<Map.Entry<K, V>> entryGen = combine(keyGen, valGen, AbstractMap.SimpleEntry::new);
        return lists(entryGen).map(entries -> {
            Map<K, V> map = new LinkedHashMap<>();
            for (Map.Entry<K, V> entry : entries) {
                map.put(entry.getKey(), entry.getValue());
            }
            return map;
        });
    }
}
