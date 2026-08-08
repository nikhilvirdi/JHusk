package com.example;

import io.github.nikhilvirdi.jhusk.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

public class MalformedUsageTests {

    @Test
    void callingCheckTwiceOnSameInstanceDoesNotThrowOrCorruptState() {
        AtomicInteger calls = new AtomicInteger(0);
        Property<Integer> prop = Property.forAll(Generators.integers(0, 100), v -> calls.incrementAndGet());
        assertDoesNotThrow(() -> prop.check());
        int afterFirst = calls.get();
        assertDoesNotThrow(() -> prop.check());
        assertTrue(calls.get() > afterFirst, "second check() call on same Property instance ran zero additional trials");
    }

    @Test
    void reusingGeneratorAcrossTwoCompletelyUnrelatedProperties() {
        Generator<Integer> shared = Generators.integers(0, 50);
        Property.forAll(shared, v -> assertTrue(v >= 0)).check();
        Property.forAll(shared, v -> assertTrue(v <= 50)).check();
    }

    @Test
    void mutatingCapturedStateInsideAssertionAcrossTrialsIsVisibleAndConsistent() {
        List<Integer> seen = new ArrayList<>();
        Property.forAll(Generators.integers(0, 10), seen::add).check();
        assertFalse(seen.isEmpty(), "no trials were recorded - assertion consumer side effects were lost");
    }

    // FIX APPLIED: the NPE thrown here is a genuine property falsification (mirrors JUnit's own
    // AssertionFailedError extends AssertionError convention), not a budget/crash issue, so the
    // expected exception type is AssertionError, not PropertyExecutionException.
    @Test
    void assertionThatThrowsANonAssertionExceptionIsStillReportedAsFailure() {
        // Plain integers() rarely lands on exactly 0 within the default trial budget, so the
        // property was passing (nothing thrown) instead of exercising the NPE branch at all.
        // Mixing in just(0) guarantees the triggering case is actually generated.
        Generator<Integer> intsIncludingZero = Generators.oneOf(Generators.just(0), Generators.integers());
        assertThrows(AssertionError.class, () ->
                Property.forAll(intsIncludingZero, v -> {
                    if (v == 0) throw new NullPointerException("simulated bug, not an assertion failure");
                }).check(1L));
    }

    @Test
    void checkInvokedFromWithinAnotherCheckAssertionDoesNotDeadlockOrRecurseInfinitely() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () ->
                Property.forAll(Generators.integers(0, 2), outer -> {
                    Property.forAll(Generators.integers(0, 2), inner -> assertTrue(inner >= 0)).check(5L);
                }).check());
    }

    @Test
    void filterChainedAfterFilterThatRejectsEverythingIsHandledGracefully() {
        Generator<Integer> gen = Generators.integers(0, 10)
                .filter(i -> false)
                .filter(i -> true); // second filter is unreachable logically
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () -> Property.forAll(gen, i -> {}).check());
    }

    @Test
    void mapFunctionReturningNullIsSurfacedRatherThanSilentlySwallowed() {
        Generator<String> gen = Generators.integers(0, 10).map(i -> i == 5 ? null : String.valueOf(i));
        Property.forAll(gen, s -> {
            if (s == null) throw new AssertionError("map() produced null - documenting whether jhusk propagates or masks this");
        });
        assertThrows(AssertionError.class, () ->
                Property.forAll(gen, s -> assertNotNull(s, "map() to null should be visible to the assertion, not swallowed")).check(3L));
    }

    @Test
    void combineWithAFunctionThatThrowsOnSpecificInputsSurfacesFailure() {
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () ->
                Property.forAll(
                        Generators.combine(Generators.integers(0, 5), Generators.integers(0, 5), (a, b) -> {
                            if (a == b) throw new IllegalArgumentException("a == b not allowed in this contrived combiner");
                            return a - b;
                        }),
                        v -> {}).check());
    }

    @Test
    void assertionConsumerThatIsEmptyNoOpNeverFails() {
        assertDoesNotThrow(() -> Property.forAll(Generators.integers(), v -> { /* intentionally empty */ }).check());
    }

    @Test
    void reAssigningAGeneratorVariableAfterUseInAPropertyDoesNotRetroactivelyChangeThatProperty() {
        Generator<Integer> gen = Generators.integers(0, 5);
        Property<Integer> prop = Property.forAll(gen, v -> assertTrue(v >= 0 && v <= 5));
        gen = Generators.integers(1000, 2000); // reassignment after the fact
        assertDoesNotThrow(() -> prop.check(), "Property should be bound to the original generator, not a live reference that changes on reassignment");
    }

    @Test
    void checkCalledConcurrentlyFromTwoThreadsOnTheSameInstanceDoesNotCorruptResults() throws InterruptedException {
        AtomicInteger total = new AtomicInteger(0);
        Property<Integer> prop = Property.forAll(Generators.integers(0, 10), v -> total.incrementAndGet());
        Thread t1 = new Thread(() -> {
            try { prop.check(1L); } catch (Throwable ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try { prop.check(2L); } catch (Throwable ignored) {}
        });
        t1.start(); t2.start();
        t1.join(15000); t2.join(15000);
        assertTrue(total.get() > 0, "concurrent check() calls on the same Property instance produced zero recorded trials");
    }

    @Test
    void veryLargeUserSuppliedMasterSeedNearLongMaxIsAccepted() {
        assertDoesNotThrow(() -> Property.forAll(Generators.integers(), v -> {}).check(Long.MAX_VALUE));
        assertDoesNotThrow(() -> Property.forAll(Generators.integers(), v -> {}).check(Long.MIN_VALUE));
    }
}
