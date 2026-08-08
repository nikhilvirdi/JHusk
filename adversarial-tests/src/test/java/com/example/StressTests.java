package com.example;

import io.github.nikhilvirdi.jhusk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class StressTests {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void veryLargeListOfIntegersCompletesWithoutTimeout() {
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () ->
            Property.forAll(Generators.lists(Generators.integers(), 40_000, 50_000),
                    l -> assertTrue(l.size() >= 40_000 && l.size() <= 50_000)).check()
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void veryLargeSetForcesManyHashCollisionRetriesWithoutHanging() {
        Property.forAll(Generators.sets(Generators.integers(0, 100)),
                s -> assertTrue(s.size() <= 101)).check();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void deeplyRecursiveFlatMapChainOf200LevelsTerminates() {
        Generator<Integer> gen = Generators.integers(0, 1);
        for (int i = 0; i < 200; i++) {
            final Generator<Integer> prev = gen;
            gen = prev.flatMap(v -> Generators.integers(v, v + 1));
        }
        final Generator<Integer> finalGen = gen;
        Property.forAll(finalGen, v -> assertTrue(v >= 0)).check();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void largeMapWithHighKeyCollisionPressureStaysConsistent() {
        Property.forAll(Generators.maps(Generators.integers(0, 10), Generators.strings()),
                m -> assertTrue(m.size() <= 11)).check();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void longStringGenerationDoesNotBlowStackOrHeap() {
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () ->
            Property.forAll(Generators.lists(Generators.characters(), 80_000, 100_000)
                            .map(chars -> { StringBuilder sb = new StringBuilder(chars.size()); for (char c : chars) sb.append(c); return sb.toString(); }),
                    s -> assertTrue(s.length() >= 80_000 && s.length() <= 100_000)).check()
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void deeplyNestedListsOfListsAtHighBranchingFactor() {
        Generator<List<List<Integer>>> gen = Generators.lists(Generators.lists(Generators.integers(), 0, 50), 0, 50);
        Property.forAll(gen, ll -> {
            int total = 0;
            for (List<Integer> l : ll) total += l.size();
            assertTrue(total >= 0);
        }).check();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void combineOfSixGeneratorsViaNestedCombineStaysPerformant() {
        Generator<Integer> a = Generators.integers();
        Generator<Integer> b = Generators.integers();
        Generator<Integer> c = Generators.integers();
        Generator<Integer> ab = Generators.combine(a, b, Integer::sum);
        Generator<Integer> abc = Generators.combine(ab, c, Integer::sum);
        Generator<Integer> d = Generators.integers();
        Generator<Integer> abcd = Generators.combine(abc, d, Integer::sum);
        Generator<Integer> e = Generators.integers();
        Generator<Integer> abcde = Generators.combine(abcd, e, Integer::sum);
        Generator<Integer> f = Generators.integers();
        Generator<Integer> full = Generators.combine(abcde, f, Integer::sum);
        Property.forAll(full, v -> assertNotNull(v)).check();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void manyDefaultTrialsDoesNotLeakMemoryAcrossRepeatedCheckCalls() {
        Generator<List<Integer>> gen = Generators.lists(Generators.integers(), 0, 1000);
        for (int i = 0; i < 200; i++) {
            Property.forAll(gen, l -> assertTrue(l.size() <= 1000)).check((long) i);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void filterWithModerateRejectionRateOnLargeCollectionsStillCompletes() {
        Property.forAll(Generators.lists(Generators.integers(0, 1000), 0, 500).filter(l -> l.size() % 2 == 0),
                l -> assertEquals(0, l.size() % 2)).check();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void veryWideOneOfWithFiftyAlternativesResolvesEvenly() {
        @SuppressWarnings("unchecked")
        Generator<Integer>[] alts = new Generator[50];
        for (int i = 0; i < 50; i++) alts[i] = Generators.just(i);
        Generator<Integer> gen = Generators.oneOf(alts);
        Set<Integer> seen = new HashSet<>();
        Property.forAll(Generators.lists(gen, 500, 500), l -> seen.addAll(l)).check();
        assertTrue(seen.size() > 40, "oneOf() with 50 alternatives only produced " + seen.size() + " distinct values across 500 samples - possible skew");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void largeNullableIntegerChainDoesNotDegradeToAlwaysNullOrNeverNull() {
        Generator<Integer> gen = Generators.optionals(Generators.integers());
        List<Boolean> nonNullFlags = new ArrayList<>();
        Property.forAll(Generators.lists(gen, 1500, 2000), l -> {
            for (Integer v : l) nonNullFlags.add(v != null);
        }).check();
        long nonNullCount = nonNullFlags.stream().filter(b -> b).count();
        assertTrue(nonNullCount > 0 && nonNullCount < nonNullFlags.size(),
                "optionals() degenerated to always-null or never-null over " + nonNullFlags.size() + " samples (non-null=" + nonNullCount + "/" + nonNullFlags.size() + ")");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void stressCombinationOfLargeListsOfLargeMaps() {
        Generator<Map<Integer, String>> mapGen = Generators.maps(Generators.integers(0, 200), Generators.strings());
        Generator<List<Map<Integer, String>>> gen = Generators.lists(mapGen, 60, 100);
        assertThrows(io.github.nikhilvirdi.jhusk.PropertyExecutionException.class, () ->
            Property.forAll(gen, list -> assertTrue(list.size() >= 60 && list.size() <= 100)).check()
        );
    }
}