package io.github.nikhilvirdi.jhusk.junit;

import io.github.nikhilvirdi.jhusk.Generator;
import io.github.nikhilvirdi.jhusk.Generators;
import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.ShrinkHarness;
import io.github.nikhilvirdi.jhusk.internal.Shrinker;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Use JHusk to test JHusk." These properties use JHusk's own {@code @Property}/
 * {@code @ForAll} JUnit integration to generate inputs that exercise JHusk's own generators and
 * shrinker, checking structural invariants the rest of the hand-written test suite doesn't state
 * as explicitly: encoding correctness across arbitrary (not just hand-picked) ranges, replay
 * fidelity, and the shrinker's two core correctness guarantees.
 *
 * <p>Every property here uses only default-inferred {@code @ForAll} parameter types (int, long)
 * and builds any composed generator (lists, bounded ranges) inside the property body itself, from
 * a {@code DataSource} seeded by a generated {@code long} -- this sidesteps {@code @ForAll}'s
 * documented generic-type-erasure limitation while still letting JHusk's own random search pick
 * the ranges, sizes, and seeds under test on every run.
 */
@DisplayName("Dogfooding — JHusk tests JHusk via its own @Property/@ForAll API")
class DogfoodingTest {

    @Property(examples = 200)
    @DisplayName("integers(min, max) never produces a value outside [min, max], for arbitrary min/max")
    void boundedIntegersStayWithinRange(@ForAll int a, @ForAll int b, @ForAll long seed) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);

        int value = Generators.integers(min, max).generate(new DataSource(seed));

        assertTrue(value >= min && value <= max,
                "integers(" + min + ", " + max + ") produced out-of-range value " + value);
    }

    @Property(examples = 100)
    @DisplayName("lists(elementGen, minSize, maxSize) always produces a list within [minSize, maxSize]")
    void listsRespectSizeBounds(@ForAll int rawMin, @ForAll int rawMax, @ForAll long seed) {
        // Fold into a small, sane range -- this is about exercising the bound, not stress-testing size.
        int minSize = Math.floorMod(rawMin, 20);
        int maxSize = minSize + Math.floorMod(rawMax, 20);

        List<Integer> list = Generators.lists(Generators.integers(0, 100), minSize, maxSize)
                .generate(new DataSource(seed));

        assertTrue(list.size() >= minSize && list.size() <= maxSize,
                "lists(_, " + minSize + ", " + maxSize + ") produced size " + list.size());
    }

    @Property(examples = 100)
    @DisplayName("replaying a recorded buffer through the same generator reproduces an equal value")
    void replayingARecordedBufferReproducesTheExactSameValue(@ForAll long seed) {
        Generator<List<Integer>> gen = Generators.lists(Generators.integers(0, 1000), 0, 20);

        DataSource original = new DataSource(seed);
        List<Integer> firstValue = gen.generate(original);
        original.freeze();

        DataSource replay = new DataSource(original.getRecordedBuffer());
        List<Integer> replayedValue = gen.generate(replay);

        assertEquals(firstValue, replayedValue,
                "Replaying the exact recorded buffer through the same generator must reproduce an equal value");
    }

    @Property(examples = 100)
    @DisplayName("the shrinker never returns a buffer that is shortlex-larger than the original failure")
    void shrunkBufferIsNeverShortlexLargerThanOriginal(@ForAll int rawThreshold, @ForAll long seed) {
        int threshold = Math.floorMod(rawThreshold, 1000) + 1;
        ShrinkTrial trial = shrinkAFailingInteger(threshold, seed);
        if (trial == null) {
            return; // this (threshold, seed) combination didn't produce a failing example
        }

        assertTrue(ShrinkHarness.compareShortlex(trial.shrunkBuffer, trial.originalBuffer) <= 0,
                "Shrunk buffer must never be shortlex-larger than the original failing buffer");
    }

    @Property(examples = 100)
    @DisplayName("the shrunk buffer still reproduces a failure of the exact same exception class")
    void shrunkBufferStillReproducesTheOriginalFailureClass(@ForAll int rawThreshold, @ForAll long seed) {
        int threshold = Math.floorMod(rawThreshold, 1000) + 1;
        ShrinkTrial trial = shrinkAFailingInteger(threshold, seed);
        if (trial == null) {
            return;
        }

        int shrunkValue = trial.generator.generate(new DataSource(trial.shrunkBuffer));

        assertThrows(trial.failureClass, () -> trial.assertion.accept(shrunkValue),
                "Shrunk buffer must still reproduce a failure of the exact same exception class");
    }

    /** Result of forcing and shrinking a single failing {@code integers(0, 2000) < threshold} run. */
    private static final class ShrinkTrial {
        final byte[] originalBuffer;
        final byte[] shrunkBuffer;
        final Generator<Integer> generator;
        final Consumer<Integer> assertion;
        final Class<? extends Throwable> failureClass;

        ShrinkTrial(byte[] originalBuffer, byte[] shrunkBuffer, Generator<Integer> generator,
                    Consumer<Integer> assertion, Class<? extends Throwable> failureClass) {
            this.originalBuffer = originalBuffer;
            this.shrunkBuffer = shrunkBuffer;
            this.generator = generator;
            this.assertion = assertion;
            this.failureClass = failureClass;
        }
    }

    /**
     * Generates one value from {@code integers(0, 2000)} and, if it violates {@code < threshold},
     * shrinks it exactly the way {@code Property.check()} does internally. Returns {@code null}
     * when this particular (threshold, seed) pair doesn't produce a failing example -- not every
     * combination will, and that's an expected, harmless no-op for this trial, not a test failure.
     */
    private static ShrinkTrial shrinkAFailingInteger(int threshold, long seed) {
        Generator<Integer> gen = Generators.integers(0, 2000);
        DataSource source = new DataSource(seed);
        int value = gen.generate(source);
        source.freeze();

        if (value < threshold) {
            return null;
        }

        Consumer<Integer> assertion = v -> assertTrue(v < threshold);
        byte[] originalBuffer = source.getRecordedBuffer();

        Class<? extends Throwable> failureClass;
        try {
            assertion.accept(value);
            return null; // unreachable given the guard above, but never assume
        } catch (Throwable failure) {
            failureClass = failure.getClass();
        }

        ShrinkHarness<Integer> harness = new ShrinkHarness<>(gen, assertion, failureClass);
        byte[] shrunkBuffer = new Shrinker<>(harness).shrink(originalBuffer, source.getRootSpans());

        return new ShrinkTrial(originalBuffer, shrunkBuffer, gen, assertion, failureClass);
    }
}
