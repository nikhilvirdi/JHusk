package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.ShrinkHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Shrink Scaffolding tests")
class ShrinkHarnessTest {

    @Nested
    @DisplayName("Shortlex Order Validation")
    class ShortlexOrder {

        @Test
        @DisplayName("Shorter arrays sort before longer arrays regardless of byte values")
        void shorterBeforeLonger() {
            byte[] shorter = { (byte) 255, (byte) 255 }; // 2 bytes, max values
            byte[] longer = { 0, 0, 0 }; // 3 bytes, min values

            assertTrue(ShrinkHarness.compareShortlex(shorter, longer) < 0);
            assertTrue(ShrinkHarness.compareShortlex(longer, shorter) > 0);
        }

        @Test
        @DisplayName("Equal length arrays sort lexicographically (unsigned)")
        void equalLengthLexicographically() {
            byte[] a = { 1, 2, 3 };
            byte[] b = { 1, 2, 4 };
            byte[] c = { 1, (byte) 255, 0 }; // 255 is unsigned > 2

            assertTrue(ShrinkHarness.compareShortlex(a, b) < 0);
            assertTrue(ShrinkHarness.compareShortlex(b, a) > 0);
            
            // Unsigned comparison means 255 > 2, even though (byte) 255 is -1
            assertTrue(ShrinkHarness.compareShortlex(b, c) < 0); 
        }

        @Test
        @DisplayName("Identical arrays sort as equal (0)")
        void identicalArraysEqual() {
            byte[] a = { 1, 2, 3 };
            byte[] b = { 1, 2, 3 };

            assertEquals(0, ShrinkHarness.compareShortlex(a, b));
            assertEquals(0, ShrinkHarness.compareShortlex(a, a));
        }
    }

    @Nested
    @DisplayName("tryBuffer Validity Protocol")
    class TryBufferValidity {

        // A buggy property checking integers < 1,000,000
        private final Generator<Integer> gen = Generators.integers();
        
        // This is a dummy test assertion to grab the AssertionError class type
        private Class<? extends Throwable> getOriginalFailureClass() {
            try {
                assertTrue(false, "Deliberate failure to get exception class");
                return null;
            } catch (AssertionError e) {
                return e.getClass();
            }
        }

        @Test
        @DisplayName("Accepts candidate that reproduces the failure (class match)")
        void acceptsReproducingCandidate() {
            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                gen, 
                val -> assertTrue(val < 1000000, "Value " + val + " >= 1000000"), 
                getOriginalFailureClass()
            );

            // A buffer that will yield an int > 1,000,000. 
            // e.g. 100,000,000 in bytes is 0x05, 0xF5, 0xE1, 0x00
            byte[] failingBuffer = { 5, (byte) 245, (byte) 225, 0 }; 

            assertTrue(harness.tryBuffer(failingBuffer), "Must accept candidate that reproduces failure");
        }

        @Test
        @DisplayName("Rejects candidate that passes the assertion")
        void rejectsPassingCandidate() {
            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                gen, 
                val -> assertTrue(val < 1000000), 
                getOriginalFailureClass()
            );

            // A buffer that yields 0, which is < 1000000, so it passes.
            byte[] passingBuffer = { 0, 0, 0, 0 };

            assertFalse(harness.tryBuffer(passingBuffer), "Must reject candidate that passes the test");
        }

        @Test
        @DisplayName("Rejects candidate that triggers OVERRUN")
        void rejectsOverrunCandidate() {
            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                gen, 
                val -> assertTrue(val < 1000000), 
                getOriginalFailureClass()
            );

            // integers() requires 4 bytes. This buffer only has 2. Will trigger DataSourceOverrunException.
            byte[] shortBuffer = { 0, 0 };

            assertFalse(harness.tryBuffer(shortBuffer), "Must reject candidate that overruns the buffer");
        }

        @Test
        @DisplayName("Rejects candidate that generates INVALID")
        void rejectsInvalidCandidate() {
            // A generator with a filter that rejects odd numbers
            Generator<Integer> filteredGen = Generators.integers().filter(x -> x % 2 == 0);
            
            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                filteredGen, 
                val -> assertTrue(val < 1000000), 
                getOriginalFailureClass()
            );

            // A buffer that generates 1 (which will be filtered out, marking source INVALID)
            byte[] oddBuffer = { 0, 0, 0, 1 };

            assertFalse(harness.tryBuffer(oddBuffer), "Must reject candidate that is filtered (INVALID)");
        }

        @Test
        @DisplayName("Rejects candidate that fails with a DIFFERENT exception class")
        void rejectsDifferentExceptionClass() {
            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                gen, 
                val -> {
                    if (val == 42) {
                        throw new RuntimeException("Unexpected crash, not an AssertionError!");
                    }
                    assertTrue(val < 1000000);
                }, 
                getOriginalFailureClass() // Expected failure is AssertionError
            );

            byte[] crashBuffer = { 0, 0, 0, 42 };

            assertFalse(harness.tryBuffer(crashBuffer), "Must reject if exception class differs");
        }

        /**
         * Regression test for a bug found during the final pre-release audit: the "generator
         * itself crashed" branch of tryBuffer accepted a candidate purely by exception-class
         * match, without ever checking source.getStatus() -- unlike the assertion-failure branch
         * a few lines below, which correctly requires VALID status first. A custom generator that
         * validates internally and throws on OVERRUN (a plausible pattern, not a contrived one)
         * could therefore have its OVERRUN-induced exception accepted as a "reproduction" of the
         * real bug whenever the two exception classes happened to coincide, violating R4's
         * explicit guard ("never accept a candidate whose status is OVERRUN or INVALID").
         * Confirmed empirically before the fix: tryBuffer(truncated) returned true here.
         */
        @Test
        @DisplayName("Rejects a candidate whose generator crashes due to OVERRUN, even if the exception class happens to match")
        void rejectsOverrunInducedCrashEvenWhenExceptionClassMatches() {
            // Throws IllegalStateException specifically when (and only when) the buffer overran --
            // the SAME exception class as the "real" failure condition below, but for an unrelated
            // reason.
            Generator<Integer> adversarial = source -> {
                int val = source.drawInt();
                if (source.getStatus() == DataSource.Status.OVERRUN) {
                    throw new IllegalStateException("ran out of bytes");
                }
                return val;
            };

            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                    adversarial,
                    val -> {
                        if (val >= 100) throw new IllegalStateException("value too large: " + val);
                    },
                    IllegalStateException.class
            );

            // drawInt() needs 4 bytes; this buffer has 1 -> OVERRUN -> adversarial throws.
            byte[] truncated = { 0 };

            assertFalse(harness.tryBuffer(truncated),
                    "An OVERRUN-induced crash must never be accepted as a valid shrink candidate (R4), "
                            + "even when its exception class coincidentally matches the original failure's");
        }
    }

    @Nested
    @DisplayName("Buffer Caching & Attempt Bound")
    class CachingAndBounds {

        @Test
        @DisplayName("Identical buffers are cached and skipped (only evaluates once)")
        void cachingSkipsRedundantEvaluations() {
            AtomicInteger executionCount = new AtomicInteger(0);
            
            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                Generators.integers(), 
                val -> {
                    executionCount.incrementAndGet();
                    assertTrue(val < 1000000);
                }, 
                AssertionError.class
            );

            byte[] candidate = { 1, 2, 3, 4 };

            harness.tryBuffer(candidate);
            harness.tryBuffer(candidate);
            harness.tryBuffer(candidate);

            assertEquals(1, executionCount.get(), "Assertion should only run once for identical buffers");
            assertEquals(1, harness.getAttempts(), "Attempts count should only reflect unique buffers");
        }

        @Test
        @DisplayName("Exceeding maxAttempts throws AttemptBoundExceededException")
        void exceedingMaxAttemptsThrows() {
            // Set maxAttempts to 5 for quick testing
            ShrinkHarness<Integer> harness = new ShrinkHarness<>(
                Generators.integers(), 
                val -> assertTrue(true), 
                AssertionError.class,
                5
            );

            // Evaluate 5 distinct buffers
            for (int i = 0; i < 5; i++) {
                byte[] candidate = { 0, 0, 0, (byte) i };
                harness.tryBuffer(candidate);
            }
            
            assertEquals(5, harness.getAttempts());

            // 6th attempt should throw
            assertThrows(ShrinkHarness.AttemptBoundExceededException.class, () -> {
                byte[] overflowCandidate = { 0, 0, 0, 6 };
                harness.tryBuffer(overflowCandidate);
            });
        }
    }
}
