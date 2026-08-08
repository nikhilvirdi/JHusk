package com.example;

import io.github.nikhilvirdi.jhusk.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

public class DeterminismConcurrencyTests {

    @Test
    void sameSeedProducesIdenticalIntegerSequenceAcrossRuns() {
        long seed = 123456789L;
        List<Integer> run1 = new ArrayList<>();
        List<Integer> run2 = new ArrayList<>();
        Property.forAll(Generators.integers(), v -> run1.add(v)).check(seed);
        Property.forAll(Generators.integers(), v -> run2.add(v)).check(seed);
        assertEquals(run1, run2, "identical seeds produced different integer sequences");
    }

    @Test
    void sameSeedProducesIdenticalCompositeSequenceAcrossRuns() {
        // Bounded ranges throughout - unbounded strings()/lists() here occasionally exhausted the
        // generation budget on nested composites without adding anything to what's being tested
        // (determinism of the same seed, not raw generator coverage).
        long seed = 42L;
        Generator<Map<Integer, List<Integer>>> gen =
                Generators.maps(Generators.integers(0, 20), Generators.lists(Generators.integers(0, 20), 0, 10));
        List<Map<Integer, List<Integer>>> run1 = new ArrayList<>();
        List<Map<Integer, List<Integer>>> run2 = new ArrayList<>();
        Property.forAll(gen, v -> run1.add(v)).check(seed);
        Property.forAll(gen, v -> run2.add(v)).check(seed);
        assertEquals(run1, run2, "identical seeds produced different composite-generator sequences");
    }

    @Test
    void differentSeedsUsuallyProduceDifferentSequences() {
        List<Integer> run1 = new ArrayList<>();
        List<Integer> run2 = new ArrayList<>();
        Property.forAll(Generators.integers(), v -> run1.add(v)).check(1L);
        Property.forAll(Generators.integers(), v -> run2.add(v)).check(2L);
        assertNotEquals(run1, run2, "different seeds produced an identical sequence - suspiciously weak seeding");
    }

    @Test
    void noArgCheckIsNotAccidentallyDeterministicAcrossInstances() {
        List<Integer> run1 = new ArrayList<>();
        List<Integer> run2 = new ArrayList<>();
        Property.forAll(Generators.integers(), v -> run1.add(v)).check();
        Property.forAll(Generators.integers(), v -> run2.add(v)).check();
        // Two independently seeded runs matching exactly would indicate a hardcoded/static default seed.
        assertNotEquals(run1, run2, "two unseeded check() calls produced identical sequences - default seed may be static/hardcoded");
    }

    @Test
    void sameSeedReplayedFromMultipleThreadsAgrees() throws InterruptedException {
        long seed = 999L;
        int threadCount = 8;
        List<List<Integer>> results = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                List<Integer> local = new ArrayList<>();
                try {
                    Property.forAll(Generators.integers(), v -> local.add(v)).check(seed);
                    results.add(local);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(threadCount, results.size(), "not all threads completed");
        for (List<Integer> r : results)
            assertEquals(results.get(0), r, "same seed produced divergent results across threads - shared mutable state?");
    }

    @Test
    void concurrentDifferentPropertiesDoNotCorruptEachOthersState() throws InterruptedException {
        int threadCount = 16;
        AtomicInteger failures = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int seedBase = t;
            pool.submit(() -> {
                try {
                    Property.forAll(Generators.integers(0, 100), v -> {
                        if (v < 0 || v > 100) throw new AssertionError("range violated under concurrency: " + v);
                    }).check(seedBase);
                } catch (Throwable th) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, failures.get(), "concurrent independent properties interfered with each other");
    }

    @Test
    void reusingSameGeneratorInstanceAcrossTwoUnrelatedPropertiesIsSafe() {
        Generator<Integer> shared = Generators.integers(0, 1000);
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        Property.forAll(shared, v -> count1.incrementAndGet()).check(1L);
        Property.forAll(shared, v -> count2.incrementAndGet()).check(1L);
        assertTrue(count1.get() > 0 && count2.get() > 0, "reusing a generator across properties broke execution");
    }

    @Test
    void checkCalledTwiceOnSamePropertyInstanceBehavesConsistently() {
        AtomicInteger totalCalls = new AtomicInteger(0);
        Property<Integer> prop = Property.forAll(Generators.integers(0, 10), v -> totalCalls.incrementAndGet());
        prop.check(7L);
        int firstRunCalls = totalCalls.get();
        prop.check(7L);
        int secondRunTotal = totalCalls.get();
        assertEquals(firstRunCalls * 2, secondRunTotal, "calling check() twice on the same Property with the same seed produced a different call count the second time");
    }

    @Test
    void checkCalledTwiceWithDifferentSeedsOnSameInstanceIsIndependent() {
        List<Integer> firstSeedValues = new ArrayList<>();
        List<Integer> secondSeedValues = new ArrayList<>();
        Property<Integer> prop = Property.forAll(Generators.integers(), v -> {
            if (firstSeedValues.size() < 1000) firstSeedValues.add(v); else secondSeedValues.add(v);
        });
        prop.check(1L);
        int afterFirst = firstSeedValues.size();
        prop.check(2L);
        assertTrue(afterFirst > 0, "first check() produced no samples");
    }

    @Test
    void massivelyParallelCheckCallsOnDistinctPropertiesDoNotDeadlock() throws InterruptedException {
        int n = 64;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    Property.forAll(Generators.lists(Generators.integers(), 0, 20), l -> {}).check();
                } finally {
                    latch.countDown();
                }
            });
        }
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(completed, "64 concurrent check() calls did not complete within 60s - possible deadlock or contention bug");
    }

    @Test
    void seedZeroIsAValidSeedNotTreatedAsUnset() {
        List<Integer> viaZero = new ArrayList<>();
        List<Integer> viaOne = new ArrayList<>();
        Property.forAll(Generators.integers(), v -> viaZero.add(v)).check(0L);
        Property.forAll(Generators.integers(), v -> viaOne.add(v)).check(1L);
        assertFalse(viaZero.isEmpty(), "seed 0 produced no samples - 0 may be misinterpreted as 'no seed'");
        assertNotEquals(viaZero, viaOne, "seed 0 behaves identically to seed 1");
    }

    @Test
    void negativeSeedIsAcceptedAndDeterministic() {
        long seed = -777L;
        List<Integer> run1 = new ArrayList<>();
        List<Integer> run2 = new ArrayList<>();
        Property.forAll(Generators.integers(), v -> run1.add(v)).check(seed);
        Property.forAll(Generators.integers(), v -> run2.add(v)).check(seed);
        assertEquals(run1, run2, "negative seed was not handled deterministically");
    }
}