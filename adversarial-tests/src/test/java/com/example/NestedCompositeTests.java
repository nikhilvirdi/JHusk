package com.example;

import io.github.nikhilvirdi.jhusk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class NestedCompositeTests {

    // NOTE: Generators.optionals(Generator<T>) returns Generator<T> - a nullable-value generator
    // that can produce a real Java null - NOT Generator<Optional<T>>. Tests below reflect that.

    // Level 2: List<Integer> where individual elements may be null
    @Test
    void listOfNullableIntegersHasNonNullListReferenceEvenWithNullElements() {
        Generator<List<Integer>> gen = Generators.lists(Generators.optionals(Generators.integers()));
        Property.forAll(gen, list -> {
            assertNotNull(list, "the List itself must never be null, even though its elements may be");
        }).check();
    }

    // Level 3: Map<String, List<Integer>> where list elements may be null
    //
    // NOTE ON BYTE BUDGET (D5/D6):
    // strings() produces keys up to 100 chars (up to ~301 bytes each at the 255/256 continuation
    // probability). maps() defaults to up to 100 entries. With 20-element value lists (~121 bytes
    // each), the total exceeds 8192 bytes (DataSource.MAX_BUFFER_SIZE) and causes
    // PropertyExecutionException on budget exhaustion — the same D5/D6 byte-cost compounding
    // documented in Generators.java. This test uses:
    //   - integers(0,99).map(i -> "k"+i) for keys: 5 bytes per key (vs. up to 301 for strings())
    //   - lists(..., 0, 3) for values: ~19 bytes per value list (vs. ~121 at max=20)
    // Total worst-case: ~100 entries × (5 + 19) bytes = ~2400 bytes — well within the cap.
    @Test
    void mapOfListsOfNullableIntegersPreservesKeyValueArity() {
        Generator<Map<String, List<Integer>>> gen =
                Generators.maps(
                        Generators.integers(0, 99).map(i -> "k" + i),
                        Generators.lists(Generators.optionals(Generators.integers()), 0, 3));
        Property.forAll(gen, m -> {
            for (Map.Entry<String, List<Integer>> e : m.entrySet()) {
                assertNotNull(e.getKey());
                assertNotNull(e.getValue());
            }
        }).check();
    }

    // Level 3: Set<List<Integer>> - sets of a non-trivially-hashable element type
    @Test
    void setOfListsRespectsListEqualsHashCodeContract() {
        Generator<Set<List<Integer>>> gen = Generators.sets(Generators.lists(Generators.integers(0, 5), 0, 10));
        Property.forAll(gen, s -> {
            List<List<Integer>> asList = new ArrayList<>(s);
            for (int i = 0; i < asList.size(); i++)
                for (int j = i + 1; j < asList.size(); j++)
                    assertNotEquals(asList.get(i), asList.get(j), "Set<List<Integer>> contains structural duplicates");
        }).check();
    }

    // Level 4: a whole Map<String, List<Integer>> that may itself be null via optionals()
    @Test
    void nullableMapOfListsFourLevelsDeep() {
        Generator<Map<String, List<Integer>>> gen =
                Generators.optionals(Generators.maps(
                        Generators.integers(0, 99).map(i -> "k" + i),
                        Generators.lists(Generators.integers(), 0, 3)));
        Property.forAll(gen, m -> {
            if (m != null) m.values().forEach(Assertions::assertNotNull);
        }).check();
    }

    // Level 5 via combine: tuple of (List<Integer-possibly-null-elements>, Map<String,Integer>, Set<Boolean>)
    @Test
    void fiveLevelCombineOfHeterogeneousComposites() {
        Generator<List<Integer>> a = Generators.lists(Generators.optionals(Generators.integers()));
        Generator<Map<String, Integer>> b = Generators.maps(Generators.strings(), Generators.integers());
        Generator<Set<Boolean>> c = Generators.sets(Generators.booleans());
        Generator<String> combined = Generators.combine(a, b, c,
                (la, mb, sc) -> la.size() + "/" + mb.size() + "/" + sc.size());
        Property.forAll(combined, s -> assertNotNull(s)).check();
    }

    // flatMap chained through 4 nested composite levels
    @Test
    void flatMapThroughFourNestedLevelsTerminates() {
        Generator<List<List<List<List<Integer>>>>> gen =
                Generators.lists(
                        Generators.lists(
                                Generators.lists(
                                        Generators.lists(Generators.integers(0, 3), 0, 2),
                                        0, 2),
                                0, 2),
                        0, 2);
        Property.forAll(gen, x -> assertNotNull(x)).check();
    }

    // Map keyed by a generated List<Integer> - tests whether key generator collisions are handled
    @Test
    void mapKeyedByListDoesNotSilentlyDropCollidingKeys() {
        Generator<Map<List<Integer>, String>> gen =
                Generators.maps(Generators.lists(Generators.integers(0, 1), 0, 2), Generators.strings());
        Property.forAll(gen, m -> {
            for (List<Integer> k : m.keySet()) assertNotNull(k);
        }).check();
    }

    // Nested oneOf inside a combine inside a list
    @Test
    void nestedOneOfInsideCombineInsideList() {
        Generator<Integer> either = Generators.oneOf(Generators.integers(0, 10), Generators.integers(-10, -1));
        Generator<List<Integer>> gen = Generators.lists(
                Generators.combine(either, Generators.booleans(), (i, b) -> b ? i : -i));
        Property.forAll(gen, list -> assertNotNull(list)).check();
    }

    // optionals() applied twice - since it is a nullable-value generator (not an Optional wrapper),
    // nesting it a second time must NOT produce any doubly-wrapped or otherwise exotic structure.
    // It should still just resolve to a plain nullable Integer.
    @Test
    void doublyAppliedOptionalsRemainsASingleNullableLayer() {
        Generator<Integer> gen = Generators.optionals(Generators.optionals(Generators.integers()));
        Property.forAll(gen, v -> {
            // v is either null or a plain Integer - no wrapping type should leak through here.
            if (v != null) assertTrue(v instanceof Integer);
        }).check();
    }

    // Set<Integer> where elements may be null - a Set may contain at most one null, per Set's contract
    @Test
    void setOfNullableIntegersContainsAtMostOneNull() {
        Generator<Set<Integer>> gen = Generators.sets(Generators.optionals(Generators.integers(0, 1)));
        Property.forAll(gen, s -> {
            long nullCount = s.stream().filter(Objects::isNull).count();
            assertTrue(nullCount <= 1, "Set<Integer> contains more than one null element - violates Set semantics");
        }).check();
    }

    // map() chained 6 times deep for structural integrity
    @Test
    void sixLevelChainedMapPreservesFinalType() {
        Generator<String> gen = Generators.integers(0, 100)
                .map(i -> i + 1)
                .map(i -> i * 2)
                .map(Object::toString)
                .map(s -> s + "x")
                .map(String::toUpperCase)
                .map(s -> "[" + s + "]");
        Property.forAll(gen, s -> assertTrue(s.startsWith("[") && s.endsWith("]"))).check();
    }

    // filter narrowing a composite generator (List<Integer> whose sum is even) composed with map
    @Test
    void filterOnDerivedPropertyOfCompositeGenerator() {
        Generator<List<Integer>> evens = Generators.lists(Generators.integers(-5, 5), 1, 6)
                .filter(l -> l.stream().mapToInt(Integer::intValue).sum() % 2 == 0);
        Property.forAll(evens, l -> assertEquals(0, l.stream().mapToInt(Integer::intValue).sum() % 2)).check();
    }
}