package com.example;

import io.github.nikhilvirdi.jhusk.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

public class TypeCompositionTests {

    // Diamond inference across map() -> flatMap() -> map() with differing type parameters
    @Test
    void diamondInferenceAcrossChainedMapAndFlatMap() {
        Generator<String> gen = Generators.integers(0, 10)
                .flatMap(i -> Generators.lists(Generators.just(i), 0, i))
                .map(List::size)
                .map(String::valueOf);
        Property.forAll(gen, s -> assertNotNull(s)).check();
    }

    // Wildcard capture: a method accepting Generator<? extends Number>
    static void acceptsNumberGenerator(Generator<? extends Number> gen) {
        Property.forAll(gen, n -> assertNotNull(n)).check();
    }

    @Test
    void wildcardExtendsNumberAcceptsIntegerGenerator() {
        assertDoesNotThrow(() -> acceptsNumberGenerator(Generators.integers()));
    }

    @Test
    void wildcardExtendsNumberAcceptsLongGenerator() {
        assertDoesNotThrow(() -> acceptsNumberGenerator(Generators.longs()));
    }

    @Test
    void wildcardExtendsNumberAcceptsDoubleGenerator() {
        assertDoesNotThrow(() -> acceptsNumberGenerator(Generators.doubles()));
    }

    // Recursive generic type: Generator<List<T>> where T itself is Generator<List<Integer>>'s element
    @Test
    void selfReferentialListOfListsTypeInfersCleanly() {
        Generator<List<List<Integer>>> gen = Generators.lists(Generators.lists(Generators.integers(), 0, 10), 0, 10);
        Property.forAll(gen, ll -> assertNotNull(ll)).check();
    }

    // combine() with three totally different unrelated generic types resolved to a fourth unrelated type
    @Test
    void combineWithThreeUnrelatedTypesProducingFourthType() {
        Generator<UUID> gen = Generators.combine(
                Generators.integers(),
                Generators.strings(),
                Generators.booleans(),
                (i, s, b) -> new UUID(i.longValue(), b ? 1L : 0L));
        Property.forAll(gen, u -> assertNotNull(u)).check();
    }

    // Generic method reference passed as the mapping function to map()
    static <T> String genericToString(T t) { return String.valueOf(t); }

    @Test
    void genericMethodReferenceAsMapFunctionInfersCorrectly() {
        Generator<String> gen = Generators.integers().map(TypeCompositionTests::genericToString);
        Property.forAll(gen, s -> assertNotNull(s)).check();
    }

    // Bounded type parameter: a Generator<T extends Comparable<T>> used generically
    static <T extends Comparable<T>> void assertSortable(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.sort(copy);
        assertEquals(list.size(), copy.size());
    }

    @Test
    void boundedComparableTypeParameterWorksWithIntegerGenerator() {
        Property.forAll(Generators.lists(Generators.integers()), TypeCompositionTests::assertSortable).check();
    }

    // Raw-type usage (deliberately sloppy, as a careless consumer might write)
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rawTypeGeneratorUsageDoesNotCorruptSubsequentTypedUsage() {
        Generator rawGen = Generators.integers();
        Property rawProp = Property.forAll(rawGen, v -> assertNotNull(v));
        rawProp.check();
        // Now verify a properly-typed generator still works after raw-type interaction.
        Generator<Integer> typedGen = Generators.integers();
        Property.forAll(typedGen, v -> assertNotNull(v)).check();
    }

    // Nested oneOf() with generators of a shared supertype but different concrete generic args
    @Test
    void oneOfWithGeneratorsProducingCommonSupertypeInterface() {
        Generator<List<Integer>> listA = Generators.just(new ArrayList<>(List.of(1, 2, 3)));
        Generator<List<Integer>> listB = Generators.just(new LinkedList<>(List.of(4, 5, 6)));
        Generator<List<Integer>> gen = Generators.oneOf(listA, listB);
        Property.forAll(gen, l -> assertTrue(l.size() == 3)).check();
    }

    // Generic array creation edge case inside oneOf's varargs
    @Test
    @SuppressWarnings("unchecked")
    void oneOfVarargsWithGenericArrayCreationCompilesAndRuns() {
        Generator<List<Integer>>[] alts = new Generator[]{
                Generators.lists(Generators.integers(), 0, 1),
                Generators.lists(Generators.integers(), 5, 5)
        };
        Generator<List<Integer>> gen = Generators.oneOf(alts);
        Property.forAll(gen, l -> assertNotNull(l)).check();
    }

    // filter() narrowing that changes the effective type constraint but not the declared type
    @Test
    void filterPreservesDeclaredGenericTypeAfterNarrowing() {
        Generator<Number> gen = Generators.integers().map(i -> (Number) i).filter(n -> n.intValue() >= 0);
        Property.forAll(gen, n -> assertTrue(n.intValue() >= 0)).check();
    }
}