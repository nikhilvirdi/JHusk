package com.example;

import io.github.nikhilvirdi.jhusk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class ImpossibleGeneratorTests {

    // A filter that can never pass should fail fast with a clear "unable to satisfy" style error,
    // not hang forever retrying. Every test in this file is time-bounded to prove that.

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void impossibleFilterOnIntegersFailsFastRatherThanHanging() {
        Generator<Integer> impossible = Generators.integers(0, 10).filter(i -> i > 1000);
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(impossible, i -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void impossibleFilterOnStringsExactLengthMillion() {
        Generator<String> impossible = Generators.strings().filter(s -> s.length() == 1_000_000);
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(impossible, s -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void doublyNarrowedFilterCompoundsRejectionRate() {
        Generator<Integer> narrow = Generators.integers(0, 1000)
                .filter(i -> i % 97 == 0)
                .filter(i -> i % 89 == 0)
                .filter(i -> i > 900);
        // 97*89 = 8633 > 1000, so this is mathematically impossible within the range.
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(narrow, i -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void impossibleFilterOnSingletonRangeIsInstantlyUnsatisfiable() {
        Generator<Integer> impossible = Generators.integers(5, 5).filter(i -> i != 5);
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(impossible, i -> {}).check());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void impossibleFilterInsideListElementGeneratorPropagatesUp() {
        Generator<Integer> impossibleElement = Generators.integers(0, 10).filter(i -> i > 100);
        Generator<List<Integer>> gen = Generators.lists(impossibleElement, 1, 5);
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(gen, l -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void impossibleFilterAfterMapStillDetected() {
        Generator<Integer> gen = Generators.integers(0, 10).map(i -> i).filter(i -> i > 1000);
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(gen, i -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void possibleButExtremelyRareFilterEventuallySucceedsOrFailsCleanly() {
        // 1-in-many-thousand odds; a good implementation either finds it with enough budget
        // or throws a clean "unable to satisfy" - either outcome is acceptable, hanging is not.
        Generator<Integer> rare = Generators.integers(0, 1_000_000).filter(i -> i == 999_999);
        try {
            Property.forAll(rare, i -> assertEquals(999_999, i)).check();
        } catch (RuntimeException expectedIfBudgetExhausted) {
            // acceptable outcome
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void filterThatThrowsInsidePredicatePropagatesRatherThanSilentlySkipping() {
        Generator<Integer> gen = Generators.integers(0, 10).filter(i -> {
            if (i == 3) throw new IllegalStateException("boom");
            return true;
        });
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(gen, i -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void mapThatThrowsPropagatesAsPropertyFailure() {
        Generator<Integer> gen = Generators.integers(0, 10).map(i -> {
            if (i == 0) throw new ArithmeticException("divide by zero simulation");
            return 100 / i;
        });
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(gen, i -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void impossibleFilterInsideFlatMapDoesNotHang() {
        Generator<Integer> gen = Generators.integers(0, 5).flatMap(i ->
                Generators.integers(0, 5).filter(j -> j > 1000));
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(gen, i -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void impossibleFilterOnNullableWrappedNarrowedIntegers() {
        Generator<Integer> gen = Generators.optionals(Generators.integers(0, 10).filter(i -> i > 1000));
        // Measured behavior: optionals() does not unconditionally short-circuit to null - it still
        // attempts to draw from the inner generator often enough that the impossible filter's budget
        // exhaustion surfaces as a genuine PropertyExecutionException, same as an unwrapped impossible
        // filter would. Updated to match observed behavior rather than the original masking assumption.
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class,
                () -> Property.forAll(gen, v -> {}).check());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void zeroArgumentOneOfIsRejectedNotSilentlyEmpty() {
        // Deliberately left as RuntimeException.class, not PropertyExecutionException.class:
        // Generators.oneOf() with no alternatives is expected to reject eagerly at construction
        // time (likely IllegalArgumentException), before Property.check() ever runs - so this is
        // not a check()-time budget-exhaustion/generator-crash case.
        assertThrows(RuntimeException.class, () -> {
            @SuppressWarnings("unchecked")
            Generator<Integer> gen = Generators.oneOf();
            Property.forAll(gen, i -> {}).check();
        });
    }
}