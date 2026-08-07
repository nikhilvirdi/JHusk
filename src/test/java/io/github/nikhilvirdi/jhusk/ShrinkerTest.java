package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.ShrinkHarness;
import io.github.nikhilvirdi.jhusk.internal.Shrinker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end coverage of the Phase 12 byte-buffer shrinking pipeline. */
class ShrinkerTest {

    @Test
    void shrinksIntegerAtFailureBoundary() {
        Generator<Integer> generator = Generators.integers();
        byte[] failing = bytes(0x00, 0x10, 0x00, 0x00); // 1,048,576

        ShrinkResult result = shrink(generator, value -> assertTrue(value < 1_000_000), failing, 500);

        assertArrayEquals(bytes(0x00, 0x0F, 0x42, 0x40), result.buffer()); // 1,000,000
        assertTrue(result.attempts() > 0);
    }

    @Test
    void spanDeletionShrinksListWithoutDesynchronizingContinuationFlags() {
        Generator<List<Integer>> generator = Generators.lists(Generators.integers(), 0, 3);
        byte[] failing = bytes(1, 0, 0, 0, 1, 1, 0, 0, 0, 10, 0);

        ShrinkResult result = shrink(generator,
            values -> assertFalse(values.stream().anyMatch(value -> value >= 5)), failing, 500);

        assertTrue(result.buffer().length < failing.length);
        assertEquals(List.of(5), replay(generator, result.buffer()));
    }

    @Test
    void terminatesAtAttemptBoundAndReturnsBestBufferSoFar() {
        Generator<Integer> generator = Generators.integers();
        byte[] failing = bytes(0, 0, 0, 7);

        ShrinkResult result = shrink(generator, value -> assertTrue(value == 0), failing, 1);

        assertArrayEquals(failing, result.buffer());
        assertEquals(1, result.attempts());
    }

    @Test
    void returnsShortlexMinimalIntegerFailure() {
        Generator<Integer> generator = Generators.integers();
        byte[] failing = bytes(0x7F, 0xFF, 0xFF, 0xFF);

        ShrinkResult result = shrink(generator, value -> assertTrue(value < 1_000_000), failing, 500);

        assertArrayEquals(bytes(0x00, 0x0F, 0x42, 0x40), result.buffer());
        assertTrue(ShrinkHarness.compareShortlex(result.buffer(), failing) < 0);
    }

    @Test
    void zeroingPassCanAcceptEntirePrimitiveSpan() {
        Generator<Integer> generator = Generators.integers();
        byte[] failing = bytes(0, 0, 0, 7);

        ShrinkResult result = shrink(generator, value -> assertTrue(value < 0), failing, 100);

        assertArrayEquals(bytes(0, 0, 0, 0), result.buffer());
    }

    @Test
    void blockLoweringPassFindsSmallestBoundaryValue() {
        Generator<Integer> generator = Generators.integers();
        byte[] failing = bytes(0, 0, 3, 0xE8); // 1,000

        ShrinkResult result = shrink(generator, value -> assertTrue(value < 500), failing, 300);

        assertArrayEquals(bytes(0, 0, 1, 0xF4), result.buffer()); // 500
    }

    @Test
    void blockDeduplicationPreservesFailureWhenARepeatedBlockIsUseful() {
        Generator<List<Integer>> generator = Generators.lists(Generators.integers(), 2, 2);
        byte[] failing = bytes(0, 0, 0, 25, 0, 0, 0, 100);

        ShrinkResult result = shrink(generator,
            values -> assertFalse(values.get(0) == 25
                && (values.get(1) == 100 || values.get(1) == 25)), failing, 500);

        assertEquals(List.of(25, 25), replay(generator, result.buffer()));
        assertTrue(ShrinkHarness.compareShortlex(result.buffer(), failing) < 0);
    }

    @Test
    void normalizationSortsOnlyWhenSortedCollectionStillFails() {
        Generator<List<Integer>> generator = Generators.lists(Generators.integers(), 0, 2);
        byte[] failing = bytes(1, 0, 0, 0, 2, 1, 0, 0, 0, 1, 0);

        ShrinkResult result = shrink(generator,
            values -> assertTrue(values.stream().mapToInt(Integer::intValue).sum() < 3), failing, 500);

        assertArrayEquals(bytes(1, 0, 0, 0, 1, 1, 0, 0, 0, 2, 0), result.buffer());
        assertEquals(List.of(1, 2), replay(generator, result.buffer()));
    }

    @Test
    void combinedPassesReachFixpointAfterDeletionAndLowering() {
        Generator<List<Integer>> generator = Generators.lists(Generators.integers(), 0, 3);
        byte[] failing = bytes(1, 0, 0, 0, 2, 1, 0, 0, 0, 10, 0);

        ShrinkResult result = shrink(generator,
            values -> assertFalse(values.stream().anyMatch(value -> value >= 5)), failing, 500);

        assertArrayEquals(bytes(1, 0, 0, 0, 5, 0), result.buffer());
        assertTrue(result.attempts() > 1);
    }

    @Test
    void regressionDeletedListElementLeavesAValidReplay() {
        Generator<List<Integer>> generator = Generators.lists(Generators.integers(), 0, 3);
        byte[] failing = bytes(1, 0, 0, 0, 1, 1, 0, 0, 0, 9, 0);

        ShrinkResult result = shrink(generator,
            values -> assertFalse(values.stream().anyMatch(value -> value >= 5)), failing, 500);

        assertFalse(replay(generator, result.buffer()).isEmpty());
        assertEquals(5, replay(generator, result.buffer()).get(0));
    }

    @Test
    void regressionInvalidCandidatesNeverBecomeShrinks() {
        Generator<Integer> generator = Generators.integers().filter(value -> value % 2 == 0);
        byte[] failing = bytes(0, 0, 0, 8);

        ShrinkResult result = shrink(generator, value -> assertTrue(value < 2), failing, 300);

        assertArrayEquals(bytes(0, 0, 0, 2), result.buffer());
        assertEquals(2, replay(generator, result.buffer()));
    }

    private static <T> ShrinkResult shrink(Generator<T> generator, Consumer<T> assertion,
                                            byte[] failing, int maxAttempts) {
        DataSource source = new DataSource(failing);
        T value = generator.generate(source);
        Class<? extends Throwable> failureClass;
        try {
            assertion.accept(value);
            throw new AssertionError("Test fixture requires a failing input");
        } catch (Throwable failure) {
            failureClass = failure.getClass();
        }
        ShrinkHarness<T> harness = new ShrinkHarness<>(generator, assertion, failureClass, maxAttempts);
        byte[] shrunk = new Shrinker<>(harness).shrink(failing, source.getRootSpans());
        return new ShrinkResult(shrunk, harness.getAttempts());
    }

    private static <T> T replay(Generator<T> generator, byte[] buffer) {
        return generator.generate(new DataSource(buffer));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private record ShrinkResult(byte[] buffer, int attempts) { }
}
