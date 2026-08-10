package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Generator} primitives from {@link Generators}.
 *
 * <p>Key focus: shrink-monotonicity property tests verifying that lexicographically
 * smaller buffers always produce simpler values. These use both random buffer pairs
 * and boundary-targeted pairs near expected bucket transitions.
 */
@DisplayName("Generators — primitive generator tests")
class GeneratorsTest {

    // ========================= Shrink Monotonicity Tests =========================

    @Nested
    @DisplayName("Shrink monotonicity: smaller bytes → simpler values")
    class ShrinkMonotonicity {

        /**
         * For booleans: false < true. If buffer A < buffer B (lex), then
         * generate(A) must be ≤ generate(B) in boolean ordering.
         */
        @Test
        @DisplayName("booleans: smaller byte → false (or same)")
        void booleansMonotonicity() {
            Generator<Boolean> gen = Generators.booleans();
            SplittableRandom rng = new SplittableRandom(1001);

            for (int i = 0; i < 5000; i++) {
                // Generate two 1-byte buffers where A ≤ B lexicographically
                int a = rng.nextInt(256);
                int b = rng.nextInt(256);
                if (a > b) { int t = a; a = b; b = t; }

                boolean valA = gen.generate(new DataSource(new byte[]{(byte) a}));
                boolean valB = gen.generate(new DataSource(new byte[]{(byte) b}));

                // false=0, true=1; valA must be ≤ valB
                assertTrue(!valA || valB,
                        "Monotonicity violation: byte " + a + " → " + valA + ", byte " + b + " → " + valB);
            }
        }

        /**
         * For unbounded integers: shrink target is 0. Unsigned interpretation of
         * big-endian bytes must be non-decreasing when bytes are lex-ordered.
         * We compare unsigned magnitudes since the encoding is unsigned big-endian.
         */
        @Test
        @DisplayName("integers(): smaller bytes → smaller unsigned value")
        void integersUnboundedMonotonicity() {
            Generator<Integer> gen = Generators.integers();
            SplittableRandom rng = new SplittableRandom(2002);

            for (int i = 0; i < 5000; i++) {
                byte[] bufA = new byte[4];
                byte[] bufB = new byte[4];
                rng.nextBytes(bufA);
                rng.nextBytes(bufB);

                // Ensure A ≤ B lexicographically
                if (compareLex(bufA, bufB) > 0) { byte[] t = bufA; bufA = bufB; bufB = t; }

                int valA = gen.generate(new DataSource(bufA));
                int valB = gen.generate(new DataSource(bufB));

                // Compare as unsigned integers (big-endian encoding is unsigned-monotonic)
                assertTrue(Integer.compareUnsigned(valA, valB) <= 0,
                        "Monotonicity violation: " + Arrays.toString(bufA) + " → " + Integer.toUnsignedString(valA)
                        + ", " + Arrays.toString(bufB) + " → " + Integer.toUnsignedString(valB));
            }
        }

        /**
         * Bounded integers(0, 10) — THE critical test.
         *
         * Tests both:
         * 1. Thousands of random buffer pairs (broad coverage)
         * 2. Adjacent raw values near expected bucket boundaries (targeted coverage)
         *
         * This is the test that would have caught the modulo-encoding bug:
         * with range=11, modulo wraps at raw=11, 22, 33, ... — roughly 1 in 11
         * adjacent pairs would violate monotonicity.
         */
        @Test
        @DisplayName("integers(0, 10): multiplicative scaling is monotonic (random + boundary pairs)")
        void integersBoundedMonotonicitySmallRange() {
            Generator<Integer> gen = Generators.integers(0, 10);
            int range = 11; // max - min + 1

            // --- Part 1: Thousands of random pairs ---
            SplittableRandom rng = new SplittableRandom(3003);
            for (int i = 0; i < 10_000; i++) {
                byte[] bufA = new byte[4];
                byte[] bufB = new byte[4];
                rng.nextBytes(bufA);
                rng.nextBytes(bufB);

                if (compareLex(bufA, bufB) > 0) { byte[] t = bufA; bufA = bufB; bufB = t; }

                int valA = gen.generate(new DataSource(bufA));
                int valB = gen.generate(new DataSource(bufB));

                assertTrue(valA <= valB,
                        "Random pair monotonicity violation: " + Arrays.toString(bufA) + " → " + valA
                        + ", " + Arrays.toString(bufB) + " → " + valB);
            }

            // --- Part 2: Boundary-targeted pairs near bucket transitions ---
            // With multiplicative scaling, bucket boundaries occur at raw values where
            // floor(raw * range / 2^32) increments. The k-th boundary is near
            // raw = ceil(k * 2^32 / range).
            //
            // We test adjacent raw values (boundary-1, boundary, boundary+1) to catch
            // any discontinuity in the encoding.
            for (int k = 0; k <= range; k++) {
                // Compute the approximate boundary where offset transitions from k-1 to k
                long boundary = ((long) k << 32) / range;

                // Test raw values in a window around each boundary
                for (long delta = -3; delta <= 3; delta++) {
                    long rawA = Math.max(0, boundary + delta);
                    long rawB = rawA + 1;
                    if (rawB > 0xFFFFFFFFL) continue;

                    byte[] bufA = unsignedIntToBytes(rawA);
                    byte[] bufB = unsignedIntToBytes(rawB);

                    int valA = gen.generate(new DataSource(bufA));
                    int valB = gen.generate(new DataSource(bufB));

                    assertTrue(valA <= valB,
                            "Boundary monotonicity violation at k=" + k + ", raw=" + rawA
                            + ": " + valA + " > " + valB);
                }
            }
        }

        /**
         * Bounded integers over a larger range to exercise different scaling factors.
         */
        @Test
        @DisplayName("integers(-100, 100): monotonicity across negative-to-positive range")
        void integersBoundedMonotonicityLargerRange() {
            Generator<Integer> gen = Generators.integers(-100, 100);
            SplittableRandom rng = new SplittableRandom(4004);

            for (int i = 0; i < 5000; i++) {
                byte[] bufA = new byte[4];
                byte[] bufB = new byte[4];
                rng.nextBytes(bufA);
                rng.nextBytes(bufB);

                if (compareLex(bufA, bufB) > 0) { byte[] t = bufA; bufA = bufB; bufB = t; }

                int valA = gen.generate(new DataSource(bufA));
                int valB = gen.generate(new DataSource(bufB));

                assertTrue(valA <= valB,
                        "Monotonicity violation: " + valA + " > " + valB);
            }
        }

        /**
         * For longs: unsigned big-endian interpretation must be non-decreasing.
         */
        @Test
        @DisplayName("longs(): smaller bytes → smaller unsigned value")
        void longsMonotonicity() {
            Generator<Long> gen = Generators.longs();
            SplittableRandom rng = new SplittableRandom(5005);

            for (int i = 0; i < 5000; i++) {
                byte[] bufA = new byte[8];
                byte[] bufB = new byte[8];
                rng.nextBytes(bufA);
                rng.nextBytes(bufB);

                if (compareLex(bufA, bufB) > 0) { byte[] t = bufA; bufA = bufB; bufB = t; }

                long valA = gen.generate(new DataSource(bufA));
                long valB = gen.generate(new DataSource(bufB));

                assertTrue(Long.compareUnsigned(valA, valB) <= 0,
                        "Monotonicity violation for longs");
            }
        }

        /**
         * For characters: shrink target is ' ' (space). Smaller bytes → char closer to ' '.
         */
        @Test
        @DisplayName("characters(): smaller bytes → char closer to space")
        void charactersMonotonicity() {
            Generator<Character> gen = Generators.characters();
            SplittableRandom rng = new SplittableRandom(6006);

            for (int i = 0; i < 5000; i++) {
                byte[] bufA = new byte[2];
                byte[] bufB = new byte[2];
                rng.nextBytes(bufA);
                rng.nextBytes(bufB);

                if (compareLex(bufA, bufB) > 0) { byte[] t = bufA; bufA = bufB; bufB = t; }

                char valA = gen.generate(new DataSource(bufA));
                char valB = gen.generate(new DataSource(bufB));

                assertTrue(valA <= valB,
                        "Monotonicity violation: '" + valA + "' (" + (int) valA
                        + ") > '" + valB + "' (" + (int) valB + ")");
            }
        }

        /**
         * For doubles: since IEEE 754 encoding is the acknowledged weak point (D1),
         * we only verify the shrink target (0.0 from all-zero bytes) and that the
         * encoding is deterministic — full monotonicity is not expected for doubles.
         */
        @Test
        @DisplayName("doubles(): all-zero bytes produce 0.0 (shrink target)")
        void doublesShrinkTarget() {
            Generator<Double> gen = Generators.doubles();
            double val = gen.generate(new DataSource(new byte[8]));
            assertEquals(0.0, val, "All-zero bytes must produce 0.0");
        }

        /**
         * integers(MIN_VALUE, MAX_VALUE) hits the {@code range == (1L << 32)} special case in
         * integers(min, max) -- a DIFFERENT formula (identity mapping via wrapping addition) than
         * the general multiplicative-scaling path every other bounded test above exercises.
         * fullIntRangeContainment (below, in BoundedIntegerRange) already checks containment for
         * this range, but never monotonicity specifically -- this closes that gap.
         */
        @Test
        @DisplayName("integers(MIN_VALUE, MAX_VALUE): the full-range identity-mapping special case is monotonic")
        void fullRangeSpecialCaseMonotonicity() {
            Generator<Integer> gen = Generators.integers(Integer.MIN_VALUE, Integer.MAX_VALUE);
            SplittableRandom rng = new SplittableRandom(31415);

            for (int i = 0; i < 20_000; i++) {
                byte[] bufA = new byte[4];
                byte[] bufB = new byte[4];
                rng.nextBytes(bufA);
                rng.nextBytes(bufB);
                if (compareLex(bufA, bufB) > 0) { byte[] t = bufA; bufA = bufB; bufB = t; }

                int valA = gen.generate(new DataSource(bufA));
                int valB = gen.generate(new DataSource(bufB));

                assertTrue(valA <= valB,
                        "Monotonicity violation in full-range special case: " + valA + " > " + valB);
            }

            assertEquals(Integer.MIN_VALUE, gen.generate(new DataSource(new byte[4])),
                    "All-zero bytes must produce MIN_VALUE (D4 shrink target)");
            assertEquals(Integer.MAX_VALUE,
                    gen.generate(new DataSource(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})),
                    "All-0xFF bytes must produce MAX_VALUE");
        }

        /** Single-value ranges pinned to the exact extremes of the int type, not just the middle (5,5). */
        @Test
        @DisplayName("integers(): single-value ranges at the extreme int boundaries always return that exact value")
        void extremeSingleValueRanges() {
            Generator<Integer> atMin = Generators.integers(Integer.MIN_VALUE, Integer.MIN_VALUE);
            Generator<Integer> atMax = Generators.integers(Integer.MAX_VALUE, Integer.MAX_VALUE);
            byte[] zero = new byte[4];
            byte[] allOnes = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

            assertEquals(Integer.MIN_VALUE, atMin.generate(new DataSource(zero)));
            assertEquals(Integer.MIN_VALUE, atMin.generate(new DataSource(allOnes)));
            assertEquals(Integer.MAX_VALUE, atMax.generate(new DataSource(zero)));
            assertEquals(Integer.MAX_VALUE, atMax.generate(new DataSource(allOnes)));
        }

        /** The narrowest possible 2-value ranges sitting right at each extreme boundary. */
        @Test
        @DisplayName("integers(): the narrowest possible 2-value ranges at both extreme boundaries are monotonic")
        void extremeTwoValueRangesMonotonicity() {
            Generator<Integer> low = Generators.integers(Integer.MIN_VALUE, Integer.MIN_VALUE + 1);
            Generator<Integer> high = Generators.integers(Integer.MAX_VALUE - 1, Integer.MAX_VALUE);

            assertEquals(Integer.MIN_VALUE, low.generate(new DataSource(new byte[4])));
            assertEquals(Integer.MAX_VALUE - 1, high.generate(new DataSource(new byte[4])));

            SplittableRandom rng = new SplittableRandom(27182);
            for (int i = 0; i < 5000; i++) {
                byte[] bufA = new byte[4];
                byte[] bufB = new byte[4];
                rng.nextBytes(bufA);
                rng.nextBytes(bufB);
                if (compareLex(bufA, bufB) > 0) { byte[] t = bufA; bufA = bufB; bufB = t; }

                assertTrue(low.generate(new DataSource(bufA)) <= low.generate(new DataSource(bufB)),
                        "Monotonicity violation near MIN_VALUE boundary");
                assertTrue(high.generate(new DataSource(bufA)) <= high.generate(new DataSource(bufB)),
                        "Monotonicity violation near MAX_VALUE boundary");
            }
        }
    }

    // ========================= Bounded Integer Range Tests =========================

    @Nested
    @DisplayName("Bounded integers: values within [min, max]")
    class BoundedIntegerRange {

        @Test
        @DisplayName("integers(0, 10): all values in [0, 10] across many draws")
        void smallRangeContainment() {
            Generator<Integer> gen = Generators.integers(0, 10);
            SplittableRandom rng = new SplittableRandom(7007);

            for (int i = 0; i < 10_000; i++) {
                DataSource ds = new DataSource(rng.nextLong());
                int val = gen.generate(ds);
                assertTrue(val >= 0 && val <= 10,
                        "Value " + val + " out of range [0, 10]");
            }
        }

        @Test
        @DisplayName("integers(-50, 50): all values in [-50, 50]")
        void negativeRangeContainment() {
            Generator<Integer> gen = Generators.integers(-50, 50);
            SplittableRandom rng = new SplittableRandom(8008);

            for (int i = 0; i < 10_000; i++) {
                DataSource ds = new DataSource(rng.nextLong());
                int val = gen.generate(ds);
                assertTrue(val >= -50 && val <= 50,
                        "Value " + val + " out of range [-50, 50]");
            }
        }

        @Test
        @DisplayName("integers(Integer.MIN_VALUE, Integer.MAX_VALUE): full int range")
        void fullIntRangeContainment() {
            Generator<Integer> gen = Generators.integers(Integer.MIN_VALUE, Integer.MAX_VALUE);
            SplittableRandom rng = new SplittableRandom(9009);

            for (int i = 0; i < 1_000; i++) {
                DataSource ds = new DataSource(rng.nextLong());
                // Should not throw — any int value is valid
                int val = gen.generate(ds);
                assertNotNull(val); // Just verify it completes without exception
            }
        }

        @Test
        @DisplayName("integers(5, 5): single-value range always returns 5")
        void singleValueRange() {
            Generator<Integer> gen = Generators.integers(5, 5);
            SplittableRandom rng = new SplittableRandom(1010);

            for (int i = 0; i < 100; i++) {
                DataSource ds = new DataSource(rng.nextLong());
                assertEquals(5, gen.generate(ds));
            }
        }

        @Test
        @DisplayName("integers(min > max) throws IllegalArgumentException")
        void invalidRangeThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> Generators.integers(10, 5));
        }
    }

    // ========================= Shrink Target Tests =========================

    @Nested
    @DisplayName("Shrink targets: all-zero bytes → simplest value (D4)")
    class ShrinkTargets {

        @Test
        @DisplayName("booleans: zero byte → false")
        void booleanShrinkTarget() {
            assertEquals(false, Generators.booleans().generate(new DataSource(new byte[]{0})));
        }

        @Test
        @DisplayName("integers(): zero bytes → 0")
        void integerShrinkTarget() {
            assertEquals(0, Generators.integers().generate(new DataSource(new byte[4])));
        }

        @Test
        @DisplayName("integers(5, 15): zero bytes → min (5)")
        void boundedIntegerShrinkTarget() {
            assertEquals(5, Generators.integers(5, 15).generate(new DataSource(new byte[4])));
        }

        @Test
        @DisplayName("longs(): zero bytes → 0L")
        void longShrinkTarget() {
            assertEquals(0L, Generators.longs().generate(new DataSource(new byte[8])));
        }

        @Test
        @DisplayName("characters(): zero bytes → ' ' (space)")
        void charShrinkTarget() {
            assertEquals(' ', Generators.characters().generate(new DataSource(new byte[2])));
        }

        @Test
        @DisplayName("doubles(): zero bytes → 0.0")
        void doubleShrinkTarget() {
            assertEquals(0.0, Generators.doubles().generate(new DataSource(new byte[8])));
        }
    }

    // ========================= Span Verification Tests =========================

    @Nested
    @DisplayName("Span wrapping: each primitive creates a span with correct byte width")
    class SpanVerification {

        @Test
        @DisplayName("booleans: span 'bool', 1 byte")
        void booleanSpan() {
            DataSource ds = new DataSource(42L);
            Generators.booleans().generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size());
            assertEquals("bool", roots.get(0).getLabel());
            assertEquals(1, roots.get(0).size(), "Boolean span should be 1 byte");
        }

        @Test
        @DisplayName("integers(): span 'int', 4 bytes")
        void integerSpan() {
            DataSource ds = new DataSource(42L);
            Generators.integers().generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size());
            assertEquals("int", roots.get(0).getLabel());
            assertEquals(4, roots.get(0).size(), "Integer span should be 4 bytes");
        }

        @Test
        @DisplayName("integers(min, max): span 'int', 4 bytes")
        void boundedIntegerSpan() {
            DataSource ds = new DataSource(42L);
            Generators.integers(0, 100).generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size());
            assertEquals("int", roots.get(0).getLabel());
            assertEquals(4, roots.get(0).size(), "Bounded integer span should be 4 bytes");
        }

        @Test
        @DisplayName("longs(): span 'long', 8 bytes")
        void longSpan() {
            DataSource ds = new DataSource(42L);
            Generators.longs().generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size());
            assertEquals("long", roots.get(0).getLabel());
            assertEquals(8, roots.get(0).size(), "Long span should be 8 bytes");
        }

        @Test
        @DisplayName("characters(): span 'char', 2 bytes")
        void charSpan() {
            DataSource ds = new DataSource(42L);
            Generators.characters().generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size());
            assertEquals("char", roots.get(0).getLabel());
            assertEquals(2, roots.get(0).size(), "Character span should be 2 bytes");
        }

        @Test
        @DisplayName("doubles(): span 'double', 8 bytes")
        void doubleSpan() {
            DataSource ds = new DataSource(42L);
            Generators.doubles().generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size());
            assertEquals("double", roots.get(0).getLabel());
            assertEquals(8, roots.get(0).size(), "Double span should be 8 bytes");
        }
    }

    // ========================= Characters Range Test =========================

    @Test
    @DisplayName("characters(): all values in printable ASCII [' ', '~']")
    void charactersRangeContainment() {
        Generator<Character> gen = Generators.characters();
        SplittableRandom rng = new SplittableRandom(1111);

        for (int i = 0; i < 10_000; i++) {
            DataSource ds = new DataSource(rng.nextLong());
            char val = gen.generate(ds);
            assertTrue(val >= ' ' && val <= '~',
                    "Character '" + val + "' (" + (int) val + ") out of printable ASCII range");
        }
    }

    // ========================= Phase 8: Combinator Tests =========================

    @Nested
    @DisplayName("Combinators: map, filter, flatMap, combine, oneOf, just")
    class Combinators {

        @Test
        @DisplayName("map preserves shrink-monotonicity of the underlying generator")
        void mapPreservesShrinkMonotonicity() {
            Generator<Integer> gen = Generators.integers(0, 1000).map(x -> x + 5);
            assertShrinkMonotonic(gen, 4, 4, 50);
        }

        @Test
        @DisplayName("filter preserves shrink-monotonicity when the predicate is accepted immediately")
        void filterPreservesShrinkMonotonicity() {
            // integers(0, 1000) never produces a negative value, so this predicate always accepts
            // on the first attempt — isolating "does filter's wrapper preserve monotonicity" from
            // retry timing, which is covered separately below.
            Generator<Integer> gen = Generators.integers(0, 1000).filter(x -> x >= 0);
            assertShrinkMonotonic(gen, 4, 4, 50);
        }

        @Test
        @DisplayName("flatMap preserves shrink-monotonicity of the first draw")
        void flatMapPreservesShrinkMonotonicity() {
            // The dependent draw's range width (51) is identical regardless of x, and restricting
            // the differing byte to the first 4 bytes (the x draw) means the dependent draw
            // consumes identical bytes in both buffers, contributing an identical offset.
            Generator<Integer> gen = Generators.integers(0, 100).flatMap(x -> Generators.integers(x, x + 50));
            assertShrinkMonotonic(gen, 8, 4, 50);
        }

        @Test
        @DisplayName("filter with an always-false predicate exhausts its retry budget and marks INVALID")
        void filterExhaustsRetryBudgetAndMarksInvalid() {
            DataSource ds = new DataSource(42L);
            Generator<Integer> gen = Generators.integers(0, 10).filter(x -> false);

            Integer result = gen.generate(ds);

            assertNull(result, "Exhausted filter should return null rather than an accepted value");
            assertEquals(DataSource.Status.INVALID, ds.getStatus());
            assertEquals(Generator.FILTER_RETRY_BUDGET * 4, ds.totalBytesConsumed(),
                    "Should consume exactly FILTER_RETRY_BUDGET attempts worth of bytes (4 bytes per "
                            + "int draw), proving the retry loop is bounded by the named constant, not unbounded");
        }

        @Test
        @DisplayName("oneOf with an all-zero buffer selects the first alternative")
        void oneOfWithAllZeroBufferSelectsFirstAlternative() {
            Generator<String> gen = Generators.oneOf(
                    Generators.just("first"),
                    Generators.just("second"),
                    Generators.just("third")
            );

            String result = gen.generate(new DataSource(new byte[4])); // all-zero selector bytes

            assertEquals("first", result);
        }

        @Test
        @DisplayName("combine nests the component generators' spans as children of the combine span")
        void combineNestsComponentSpansAsChildren() {
            DataSource ds = new DataSource(42L);
            Generator<Integer> gen = Generators.combine(
                    Generators.integers(0, 10),
                    Generators.booleans(),
                    (i, b) -> b ? i + 1 : i
            );

            gen.generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size(), "combine should produce exactly one root span");

            Span combineSpan = roots.get(0);
            assertEquals("combine", combineSpan.getLabel());
            assertEquals(2, combineSpan.getChildren().size(), "combine span should have exactly 2 children");
            assertEquals("int", combineSpan.getChildren().get(0).getLabel());
            assertEquals("bool", combineSpan.getChildren().get(1).getLabel());
        }

        @Test
        @DisplayName("3-arity combine applies the function to all three generated values")
        void threeArgCombineAppliesFunctionToAllValues() {
            Generator<Integer> gen = Generators.combine(
                    Generators.just(1), Generators.just(2), Generators.just(3),
                    (a, b, c) -> a + b + c
            );

            assertEquals(6, gen.generate(new DataSource(0L)));
        }
    }

    // ========================= Phase 9: Collection Generator Tests =========================

    @Nested
    @DisplayName("Collections: lists, strings, optionals, sets, maps (D5)")
    class Collections {

        @Test
        @DisplayName("lists(): all-false continuation flags (all-zero buffer) produce an empty list")
        void allZeroBufferProducesEmptyList() {
            Generator<List<Integer>> gen = Generators.lists(Generators.integers(0, 100));

            List<Integer> result = gen.generate(new DataSource(new byte[64]));

            assertTrue(result.isEmpty(), "All-zero buffer must decode to the empty list (D4 shrink target)");
        }

        /**
         * The single most important test in this phase: hand-crafts a buffer encoding a 3-element
         * list via D5's continuation-flag scheme, uses the RECORDED SPAN METADATA (not a hand-
         * computed offset) to find the byte range of the second element, splices exactly that span
         * (flag byte + payload) out of the buffer, and replays the result. If D5 + spans (R1) do
         * their job, the spliced buffer decodes to a valid 2-element list with the untouched
         * elements' values intact — proving span deletion never desynchronizes the remainder of
         * the buffer, unlike length-prefix encoding would.
         */
        @Test
        @DisplayName("deleting one element's span (flag + bytes) yields a valid list with exactly one fewer element, uncorrupted")
        void deletingElementSpanProducesValidShrunkList() {
            // Hand-crafted buffer for lists(integers()): [flag][4-byte elem] x3, then [flag=stop].
            // integers() (unbounded) returns its 4 raw bytes verbatim (no scaling), so picking
            // exact element values is trivial and the test's expectations are easy to verify by eye.
            byte[] original = {
                    1, 0, 0, 0, 1,   // flag=continue, element = 1
                    1, 0, 0, 0, 2,   // flag=continue, element = 2
                    1, 0, 0, 0, 3,   // flag=continue, element = 3
                    0                // flag=stop
            };

            Generator<List<Integer>> gen = Generators.lists(Generators.integers());

            DataSource replaySource = new DataSource(original);
            List<Integer> originalList = gen.generate(replaySource);
            replaySource.freeze();
            assertEquals(List.of(1, 2, 3), originalList, "Sanity check: hand-crafted buffer decodes to [1, 2, 3]");

            Span listSpan = replaySource.getRootSpans().get(0);
            Span secondElementSpan = listSpan.getChildren().get(1); // the element with value 2

            // Splice out exactly the second element's span (flag byte + its 4 payload bytes) —
            // precisely the deletion the future shrinker (Phase 11-12) will perform.
            int deletedLen = secondElementSpan.size();
            byte[] spliced = new byte[original.length - deletedLen];
            System.arraycopy(original, 0, spliced, 0, secondElementSpan.getStart());
            System.arraycopy(original, secondElementSpan.getEnd(), spliced,
                    secondElementSpan.getStart(), original.length - secondElementSpan.getEnd());

            List<Integer> shrunkList = gen.generate(new DataSource(spliced));

            assertEquals(List.of(1, 3), shrunkList,
                    "Deleting element 2's span must remove exactly that element, leaving 1 and 3 untouched");
        }

        @Test
        @DisplayName("strings(): respects size bounds and only contains characters() printable ASCII range")
        void stringsRespectsBoundsAndCharacterRange() {
            Generator<String> gen = Generators.strings();
            SplittableRandom rng = new SplittableRandom(2468);

            for (int i = 0; i < 500; i++) {
                String s = gen.generate(new DataSource(rng.nextLong()));

                assertTrue(s.length() <= Generators.DEFAULT_LIST_MAX_SIZE,
                        "String length " + s.length() + " exceeds DEFAULT_LIST_MAX_SIZE");
                for (int j = 0; j < s.length(); j++) {
                    char c = s.charAt(j);
                    assertTrue(c >= ' ' && c <= '~',
                            "Character '" + c + "' (" + (int) c + ") outside characters()' printable ASCII range");
                }
            }
        }

        @Test
        @DisplayName("optionals(): all-zero buffer produces null (absence is the shrink target)")
        void optionalsAllZeroBufferProducesNull() {
            Generator<Integer> gen = Generators.optionals(Generators.integers(0, 100));

            Integer result = gen.generate(new DataSource(new byte[8]));

            assertNull(result, "All-zero buffer must decode to null (D4 shrink target: absence is simpler)");
        }

        @Test
        @DisplayName("list span tree: outer 'list' span has N 'list-element' children, each nesting the element's own generator span")
        void listSpanTreeNestsElementSpansCorrectly() {
            DataSource ds = new DataSource(99L);
            // minSize == maxSize: purely mandatory elements, no continuation flags and therefore
            // no trailing terminator span to complicate the child count.
            Generator<List<Integer>> gen = Generators.lists(Generators.integers(0, 100), 3, 3);

            gen.generate(ds);
            ds.freeze();

            List<Span> roots = ds.getRootSpans();
            assertEquals(1, roots.size());

            Span listSpan = roots.get(0);
            assertEquals("list", listSpan.getLabel());
            assertEquals(3, listSpan.getChildren().size(), "list span should have exactly 3 'list-element' children");

            for (Span elementSpan : listSpan.getChildren()) {
                assertEquals("list-element", elementSpan.getLabel());
                assertEquals(1, elementSpan.getChildren().size(),
                        "each list-element should nest exactly one child: the element generator's own span");
                assertEquals("int", elementSpan.getChildren().get(0).getLabel());
            }
        }

        /**
         * D4 says shrink targets are "empty for collections" -- sets()/maps() were never directly
         * checked for this, only inferred from lists()'s own empty-list test plus reasoning about
         * how LinkedHashSet/LinkedHashMap behave on an empty input.
         */
        @Test
        @DisplayName("sets()/maps(): all-zero buffer produces an empty collection (D4, inherited from lists())")
        void setsAndMapsShrinkTargetIsEmpty() {
            Set<Integer> emptySet = Generators.sets(Generators.integers(0, 100))
                    .generate(new DataSource(new byte[64]));
            assertTrue(emptySet.isEmpty(), "sets() must decode an all-zero buffer to an empty set");

            Map<Integer, Integer> emptyMap = Generators.maps(Generators.integers(0, 100), Generators.integers(0, 100))
                    .generate(new DataSource(new byte[64]));
            assertTrue(emptyMap.isEmpty(), "maps() must decode an all-zero buffer to an empty map");
        }
    }

    // ========================= Deep Nesting Stress Test (Part 2 audit) =========================

    /**
     * R1 (span recording) is the project's own "highest risk" register entry, and every existing
     * span test uses shallow, hand-sized structures. This stresses the SAME mechanism at real
     * composite depth: a list of lists of optionals of a custom combined type, four generator
     * layers deep. If span nesting/boundaries ever desynchronized at depth, this is where it would
     * show first -- shallow structures would still look fine.
     */
    @Nested
    @DisplayName("Span tree integrity under deep composite nesting")
    class DeepNesting {

        private record Point(int x, int y) { }

        @Test
        @DisplayName("list<list<optional<combine>>>: every level nests correctly, with no overlapping or out-of-bounds spans")
        void deeplyNestedCompositeProducesCorrectlyNestedNonOverlappingSpanTree() {
            Generator<Point> point = Generators.combine(
                    Generators.integers(-10, 10), Generators.integers(-10, 10), Point::new);
            Generator<Point> optionalPoint = Generators.optionals(point);
            // Fixed sizes throughout: avoids the harmless-but-noisy terminator span (see
            // listSpanTreeNestsElementSpansCorrectly above) so the shape is exact and predictable.
            Generator<List<Point>> innerList = Generators.lists(optionalPoint, 2, 2);
            Generator<List<List<Point>>> outerList = Generators.lists(innerList, 2, 2);

            // Try several seeds so both "optional present" and "optional absent" branches get
            // exercised across runs -- the structural assertions below tolerate either.
            for (long seed = 1; seed <= 20; seed++) {
                DataSource ds = new DataSource(seed);
                outerList.generate(ds);
                ds.freeze();

                List<Span> roots = ds.getRootSpans();
                assertEquals(1, roots.size());
                Span outer = roots.get(0);
                assertEquals("list", outer.getLabel());
                assertEquals(2, outer.getChildren().size());

                for (Span outerElement : outer.getChildren()) {
                    assertEquals("list-element", outerElement.getLabel());
                    assertEquals(1, outerElement.getChildren().size());
                    Span inner = outerElement.getChildren().get(0);
                    assertEquals("list", inner.getLabel());
                    assertEquals(2, inner.getChildren().size());

                    for (Span innerElement : inner.getChildren()) {
                        assertEquals("list-element", innerElement.getLabel());
                        assertEquals(1, innerElement.getChildren().size());
                        Span optionalSpan = innerElement.getChildren().get(0);
                        assertEquals("optional", optionalSpan.getLabel());
                        assertTrue(optionalSpan.getChildren().size() <= 1,
                                "optional should have 0 children (absent) or 1 (present)");

                        for (Span combineSpan : optionalSpan.getChildren()) {
                            assertEquals("combine", combineSpan.getLabel());
                            assertEquals(2, combineSpan.getChildren().size());
                            assertEquals("int", combineSpan.getChildren().get(0).getLabel());
                            assertEquals("int", combineSpan.getChildren().get(1).getLabel());
                        }
                    }
                }

                assertSpanTreeIntegrity(outer);
            }
        }

        /**
         * Recursively verifies the R1 nesting discipline at every level: each child starts no
         * earlier than the previous sibling ended (non-overlapping, in order) and ends no later
         * than its parent (fully contained) -- the exact property span deletion depends on to
         * safely remove one element without corrupting anything else in the buffer.
         */
        private void assertSpanTreeIntegrity(Span span) {
            int cursor = span.getStart();
            for (Span child : span.getChildren()) {
                assertTrue(child.getStart() >= cursor,
                        "Child span [" + child.getStart() + "," + child.getEnd()
                                + ") must not start before the previous sibling ended (cursor=" + cursor + ")");
                assertTrue(child.getEnd() <= span.getEnd(),
                        "Child span [" + child.getStart() + "," + child.getEnd()
                                + ") must be fully contained within parent's [" + span.getStart() + "," + span.getEnd() + ")");
                cursor = child.getEnd();
                assertSpanTreeIntegrity(child);
            }
        }
    }

    // ========================= Helper Methods =========================

    /**
     * Lexicographic comparison of two byte arrays (unsigned byte values).
     * Returns negative if a < b, zero if equal, positive if a > b.
     */
    private static int compareLex(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    /**
     * Converts an unsigned 32-bit long value to a 4-byte big-endian byte array.
     */
    private static byte[] unsignedIntToBytes(long val) {
        return new byte[]{
                (byte) (val >>> 24),
                (byte) (val >>> 16),
                (byte) (val >>> 8),
                (byte) val
        };
    }

    /**
     * Buffer-pair monotonicity check for composed generators (same approach as the Phase 7
     * primitive tests above): builds pairs of buffers that share a common prefix and differ at
     * exactly one byte position within {@code [0, diffPositionBound)}, where the "smaller" buffer
     * has a strictly lower byte at that position — satisfying the lexicographic-order definition.
     * Per the D4 invariant, replaying the smaller buffer must never produce a larger value than
     * replaying the larger one.
     */
    private static void assertShrinkMonotonic(Generator<Integer> gen, int bufferLen, int diffPositionBound, int trials) {
        SplittableRandom rng = new SplittableRandom(4242);
        int checked = 0;
        while (checked < trials) {
            byte[] shared = new byte[bufferLen];
            rng.nextBytes(shared);

            int pos = rng.nextInt(diffPositionBound);
            int base = shared[pos] & 0xFF;
            if (base >= 255) {
                continue; // can't bump this byte upward; resample
            }

            byte[] smaller = shared.clone();
            byte[] larger = shared.clone();
            larger[pos] = (byte) (base + 1 + rng.nextInt(255 - base));

            int valueSmaller = gen.generate(new DataSource(smaller));
            int valueLarger = gen.generate(new DataSource(larger));

            assertTrue(valueSmaller <= valueLarger,
                    "Lexicographically smaller buffer must not produce a larger value: "
                            + valueSmaller + " > " + valueLarger);
            checked++;
        }
    }

    @Nested
    @DisplayName("ConfigurableOptionalsTests — optionals(Generator, double) overload")
    class ConfigurableOptionalsTests {

        @Test
        @DisplayName("nullProbability=0.0 never produces null across 100 examples")
        void zeroNullProbabilityNeverProducesNull() {
            // Should pass without exception: null is never generated at probability 0.0
            Property.forAll(Generators.optionals(Generators.integers(1, 100), 0.0),
                v -> assertNotNull(v, "Expected non-null but got null at nullProbability=0.0")
            ).check();
        }

        @Test
        @DisplayName("nullProbability=1.0 always produces null across 100 examples")
        void oneNullProbabilityAlwaysProducesNull() {
            // Should pass without exception: null is always generated at probability 1.0
            Property.forAll(Generators.optionals(Generators.integers(1, 100), 1.0),
                v -> assertNull(v, "Expected null but got non-null at nullProbability=1.0")
            ).check();
        }

        @Test
        @DisplayName("nullProbability=-0.1 throws IllegalArgumentException immediately at construction time")
        void invalidNullProbabilityBelowZeroThrowsImmediately() {
            assertThrows(IllegalArgumentException.class,
                () -> Generators.optionals(Generators.integers(), -0.1),
                "Negative nullProbability must throw IllegalArgumentException immediately");
        }

        @Test
        @DisplayName("nullProbability=1.1 throws IllegalArgumentException immediately at construction time")
        void invalidNullProbabilityAboveOneThrowsImmediately() {
            assertThrows(IllegalArgumentException.class,
                () -> Generators.optionals(Generators.integers(), 1.1),
                "nullProbability > 1.0 must throw IllegalArgumentException immediately");
        }

        @Test
        @DisplayName("nullProbability=0.5 produces null rate within [0.35, 0.65] over 2000 samples")
        void approximatelyMatchesConfiguredProbabilityOverManySamples() {
            AtomicInteger nullCount = new AtomicInteger(0);
            AtomicInteger totalCount = new AtomicInteger(0);

            Property.forAll(Generators.optionals(Generators.integers(1, 100), 0.5), v -> {
                totalCount.incrementAndGet();
                if (v == null) {
                    nullCount.incrementAndGet();
                }
            }).examples(2000).check();

            double observedRate = (double) nullCount.get() / totalCount.get();
            assertTrue(observedRate >= 0.35 && observedRate <= 0.65,
                "Observed null rate " + observedRate + " is outside the expected [0.35, 0.65] band "
                + "for nullProbability=0.5 over " + totalCount.get() + " samples");
        }
    }

    @Nested
    @DisplayName("Exhaustive & Booleans Edge-Case Tests (Item #4)")
    class ExhaustiveAndBooleansEdgeCaseTests {

        @Test
        @DisplayName("booleans(): deterministic edge-case corpus exercises both false and true even with examples(1)")
        void booleansEdgeCaseCoverage() {
            Set<Boolean> valuesSeen = new java.util.HashSet<>();
            Property.forAll(Generators.booleans(), v -> {
                valuesSeen.add(v);
            }).examples(1).check();

            assertTrue(valuesSeen.contains(false), "booleans() must exercise false via the all-zero edge-case buffer");
            assertTrue(valuesSeen.contains(true), "booleans() must exercise true via the all-0xFF edge-case buffer");
        }

        @Test
        @DisplayName("exhaustive() with zero arguments throws IllegalArgumentException")
        void exhaustiveZeroArgumentsThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> Generators.exhaustive(),
                "exhaustive() with 0 arguments must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("exhaustive(a, b, c): deterministic edge-case corpus exercises first ('a') and last ('c') even with examples(1)")
        void exhaustiveExercisesFirstAndLastEdgeCases() {
            Set<String> valuesSeen = new java.util.HashSet<>();
            Property.forAll(Generators.exhaustive("a", "b", "c"), v -> {
                valuesSeen.add(v);
            }).examples(1).check();

            assertTrue(valuesSeen.contains("a"), "exhaustive() must exercise first value ('a') via the all-zero edge-case buffer");
            assertTrue(valuesSeen.contains("c"), "exhaustive() must exercise last value ('c') via the all-0xFF edge-case buffer");
        }

        @Test
        @DisplayName("exhaustive(first, second, third): property failing on all values shrinks to values[0]")
        void exhaustiveShrinksToFirstValue() {
            AssertionError error = assertThrows(AssertionError.class, () ->
                Property.forAll(Generators.exhaustive("first", "second", "third"), v -> {
                    fail("failing for all values");
                }).check()
            );

            assertTrue(error.getMessage().contains("first"),
                "Property failing on exhaustive() must shrink to values[0] ('first')");
        }
    }
}
