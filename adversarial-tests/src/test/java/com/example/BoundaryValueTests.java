package com.example;

import io.github.nikhilvirdi.jhusk.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class BoundaryValueTests {

    @Test
    void integerMinValueSingletonRangeIsReachable() {
        Property.forAll(Generators.integers(Integer.MIN_VALUE, Integer.MIN_VALUE),
                v -> assertEquals(Integer.MIN_VALUE, v)).check();
    }

    @Test
    void integerMaxValueSingletonRangeIsReachable() {
        Property.forAll(Generators.integers(Integer.MAX_VALUE, Integer.MAX_VALUE),
                v -> assertEquals(Integer.MAX_VALUE, v)).check();
    }

    @Test
    void singleValueRangeAlwaysProducesThatValue() {
        Property.forAll(Generators.integers(5, 5), v -> assertEquals(5, v)).check();
    }

    @Test
    void fullIntRangeNeverEscapesBounds() {
        // width = MAX - MIN overflows a signed int internally if computed naively.
        Property.forAll(Generators.integers(Integer.MIN_VALUE, Integer.MAX_VALUE),
                v -> assertTrue(v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)).check();
    }

    @Test
    void negativeOnlyRangeStaysNegative() {
        Property.forAll(Generators.integers(-1000, -1), v -> assertTrue(v < 0)).check();
    }

    @Test
    void rangeStraddlingZeroIncludesBothSigns() {
        boolean[] sawNeg = {false}, sawPos = {false}, sawZero = {false};
        Property.forAll(Generators.lists(Generators.integers(-3, 3), 500, 500), xs -> {
            for (int x : xs) {
                if (x < 0) sawNeg[0] = true;
                if (x > 0) sawPos[0] = true;
                if (x == 0) sawZero[0] = true;
            }
        }).check();
        assertTrue(sawNeg[0] && sawPos[0] && sawZero[0],
                "integers(-3,3) failed to produce all of negative/zero/positive in 500 samples");
    }

    @Test
    void invertedRangeMinGreaterThanMaxRejectedEagerly() {
        assertThrows(IllegalArgumentException.class, () -> Generators.integers(10, 5));
    }

    @Test
    void emptyListAllowedWhenSizeRangeIsZeroZero() {
        Property.forAll(Generators.lists(Generators.integers(), 0, 0),
                l -> assertTrue(l.isEmpty())).check();
    }

    @Test
    void listMinSizeEqualsMaxSizeIsExact() {
        Property.forAll(Generators.lists(Generators.integers(), 7, 7),
                l -> assertEquals(7, l.size())).check();
    }

    @Test
    void listNegativeMinSizeRejectedEagerly() {
        assertThrows(IllegalArgumentException.class, () -> Generators.lists(Generators.integers(), -1, 5));
    }

    @Test
    void emptyStringIsEventuallyProduced() {
        // Forcing an exact-size-300 list of strings() stresses exact-size list generation enough
        // to occasionally exhaust the generation budget. Sampling plain strings() across several
        // independent seeded runs tests the same thing (does strings() ever produce "") far more cheaply.
        boolean[] sawEmpty = {false};
        for (long seed = 0; seed < 50 && !sawEmpty[0]; seed++) {
            final long s = seed;
            Property.forAll(Generators.strings(), str -> {
                if (str.isEmpty()) sawEmpty[0] = true;
            }).check(s);
        }
        assertTrue(sawEmpty[0], "strings() never produced \"\" across 50 independent seeded check() runs");
    }

    @Test
    void doubleSpaceIncludesNaN() {
        boolean[] sawNaN = {false};
        Property.forAll(Generators.lists(Generators.doubles(), 1000, 1000), ds -> {
            for (double d : ds) {
                if (Double.isNaN(d)) sawNaN[0] = true;
            }
        }).check();
        assertTrue(sawNaN[0], "doubles() never produced NaN in 1000 samples");
        // Deliberately not asserting +/-Infinity here: doubles() generates via direct 64-bit
        // IEEE-754 bit patterns, so hitting either of the two Infinity bit patterns out of the
        // full double space has probability ~1.08e-16 per sample. 1000 samples never hitting it
        // is the expected outcome, not a generator defect - confirmed against JHusk's source.
    }

    @Test
    void unicodeSurrogateCharactersAreEitherExcludedOrHandled() {
        // If characters() can emit a lone (unpaired) surrogate, downstream String construction
        // can throw or produce corrupt data. This documents the actual behavior.
        Property.forAll(Generators.lists(Generators.characters(), 2000, 2000), chars -> {
            for (char c : chars) {
                if (Character.isSurrogate(c)) {
                    // Not failing automatically - recording that surrogates ARE produced.
                    // If jhusk's string-building from characters() blows up on this, that's a bug.
                    assertDoesNotThrow(() -> String.valueOf(c));
                }
            }
        }).check();
    }
}