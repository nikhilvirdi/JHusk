package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.ShrinkHarness;
import io.github.nikhilvirdi.jhusk.internal.Shrinker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Equivalent, deterministic probes for selected jlink/shrinking-challenge cases. */
class ShrinkingChallengeTest {

    @Test
    void bound5FindsTheSmallestJHuskOrderedOverflowWitness() {
        Generator<List<Integer>> list = Generators.lists(Generators.integers(-32_768, 32_767), 0, 1);
        Generator<List<List<Integer>>> generator = source -> List.of(
            list.generate(source), list.generate(source), list.generate(source),
            list.generate(source), list.generate(source));
        byte[] failing = bytes(1, 0, 0, 0, 0, 1, 0x7F, 0xFF, 0xFF, 0xFF, 0, 0, 0);

        List<List<Integer>> result = replay(generator, shrink(generator, ShrinkingChallengeTest::bound5Property, failing));

        // The challenge's type-directed ideal is [[-32768], [-1], [], [], []].
        // JHusk's raw monotonic encoding instead minimizes the second value to the first
        // overflow boundary, which is the smallest shortlex witness it can represent.
        assertEquals(List.of(List.of(-32_768), List.of(-31_488), List.of(), List.of(), List.of()), result);
    }

    @Test
    void lengthListShrinksDependentLengthAndElementsToTheChallengeWitness() {
        Generator<List<Integer>> generator = Generators.integers(1, 100)
            .flatMap(length -> Generators.lists(Generators.integers(0, 1_000), length, length));
        byte[] failing = bytes(0x05, 0x1E, 0xB8, 0x52,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF);

        byte[] shrunk = shrink(generator, values -> assertTrue(values.stream().max(Integer::compareTo).orElse(0) < 900), failing);

        assertEquals(List.of(900), replay(generator, shrunk));
        assertEquals(8, shrunk.length, "the length selector and sole element are the complete replay buffer");
    }

    @Test
    void reverseNormalizesToTheMinimalDistinctPair() {
        Generator<List<Integer>> generator = Generators.lists(Generators.integers(), 0, 3);
        byte[] failing = bytes(1, 0, 0, 0, 5, 1, 0, 0, 0, 2, 0);

        List<Integer> result = replay(generator, shrink(generator,
            values -> assertEquals(values, reversed(values)), failing));

        assertEquals(List.of(0, 1), result);
    }

    private static void bound5Property(List<List<Integer>> lists) {
        boolean eachBelow256 = lists.stream().allMatch(values -> narrowSum(values) < 256);
        if (eachBelow256) {
            assertTrue(narrowSum(flatten(lists)) < 5 * 256);
        }
    }

    private static int narrowSum(List<Integer> values) {
        short sum = 0;
        for (int value : values) sum = (short) (sum + value);
        return sum;
    }

    private static List<Integer> flatten(List<List<Integer>> lists) {
        List<Integer> result = new ArrayList<>();
        for (List<Integer> values : lists) result.addAll(values);
        return result;
    }

    private static List<Integer> reversed(List<Integer> values) {
        List<Integer> result = new ArrayList<>(values);
        java.util.Collections.reverse(result);
        return result;
    }

    private static <T> byte[] shrink(Generator<T> generator, Consumer<T> assertion, byte[] failing) {
        DataSource source = new DataSource(failing);
        T value = generator.generate(source);
        Class<? extends Throwable> failureClass;
        try {
            assertion.accept(value);
            throw new AssertionError("Benchmark fixture requires a failing input");
        } catch (Throwable failure) {
            failureClass = failure.getClass();
        }
        ShrinkHarness<T> harness = new ShrinkHarness<>(generator, assertion, failureClass, 5_000);
        return new Shrinker<>(harness).shrink(failing, source.getRootSpans());
    }

    private static <T> T replay(Generator<T> generator, byte[] buffer) {
        return generator.generate(new DataSource(buffer));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
