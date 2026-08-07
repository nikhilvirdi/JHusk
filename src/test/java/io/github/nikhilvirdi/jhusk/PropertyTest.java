package io.github.nikhilvirdi.jhusk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Property Runner tests (Generate, Check & Failure Reporting)")
class PropertyTest {

    private static final Path FAILURE_DIR = Path.of(".jhusk");
    private static final String FAILURE_PREFIX = "io.github.nikhilvirdi.jhusk.PropertyTest";

    @BeforeEach
    @AfterEach
    void clearStoredFailures() throws IOException {
        if (Files.isDirectory(FAILURE_DIR)) {
            try (var files = Files.list(FAILURE_DIR)) {
                files.filter(file -> file.getFileName().toString().startsWith(FAILURE_PREFIX))
                     .forEach(file -> {
                         try {
                             Files.deleteIfExists(file);
                         } catch (IOException e) {
                             throw new java.io.UncheckedIOException(e);
                         }
                     });
            }
        }
    }

    @Test
    @DisplayName("Correct property passes cleanly across N examples")
    void correctPropertyPasses() {
        Generator<List<Integer>> gen = Generators.lists(Generators.integers());

        // Property: "Reversing a list twice returns the original list"
        // Should pass cleanly, no exception thrown.
        Property.forAll(gen, list -> {
            List<Integer> copy = new ArrayList<>(list);
            Collections.reverse(copy);
            Collections.reverse(copy);
            assertEquals(list, copy);
        }).check(99L);
    }

    @Test
    @DisplayName("Filter exhaustion triggers invalid budget abort")
    void filterExhaustionAborts() {
        // Generator that always marks itself invalid via an impossible filter
        Generator<Integer> gen = Generators.integers().filter(x -> false);

        AssertionError error = assertThrows(AssertionError.class, () -> {
            Property.forAll(gen, value -> {
                assertTrue(true);
            }).check(123L);
        });

        assertTrue(error.getMessage().contains("exhausted invalid budget"), 
            "Should throw invalid budget exception, not silently pass");
        assertTrue(error.getMessage().contains("Too many invalid runs"),
            "Message should clarify too many invalid runs occurred");
    }

    @Nested
    @DisplayName("Phase 13 Failure Reporting & Reproduction Tests")
    class FailureReporting {

        @Test
        @DisplayName("Failing property report contains minimal shrunk value, seed, and reproduction instruction")
        void reportContainsShrunkValueSeedAndReproduction() {
            Generator<Integer> gen = Generators.integers();

            AssertionError error = assertThrows(AssertionError.class, () -> {
                Property.forAll(gen, value -> {
                    assertTrue(value < 1000000, "Value " + value + " is >= 1,000,000");
                }).check(42L);
            });

            String message = error.getMessage();
            System.out.println("--- PHASE 13 UPGRADED FAILURE REPORT ---");
            System.out.println(message);
            System.out.println("----------------------------------------");

            assertTrue(message.contains("Property Falsified!"), "Report header present");
            assertTrue(message.contains("Falsifying (shrunk) value:"), "Shrunk value header present");
            assertTrue(message.contains("1000000"), "Minimal shrunk value (1000000) present in report");
            assertTrue(message.contains("Seed: 42L"), "Literal seed present in report");
            assertTrue(message.contains("To reproduce this exact failure, run: check(42L)"), 
                "Explicit copy-pasteable reproduction instructions present");
        }

        @Test
        @DisplayName("Original exception is preserved as the cause of AssertionError")
        void originalExceptionIsPreservedAsCause() {
            Generator<Integer> gen = Generators.integers();
            RuntimeException originalException = new RuntimeException("Custom assertion breakdown");

            AssertionError error = assertThrows(AssertionError.class, () -> {
                Property.forAll(gen, value -> {
                    if (value > 100) {
                        throw originalException;
                    }
                }).check(55L);
            });

            assertNotNull(error.getCause(), "Original exception must be preserved as cause");
            assertSame(originalException, error.getCause(), "Cause must be exact original exception instance");
        }

        @Test
        @DisplayName("Before/after section distinctly displays both original raw and shrunk values")
        void beforeAndAfterSectionsDistinct() {
            Generator<Integer> gen = Generators.integers();

            AssertionError error = assertThrows(AssertionError.class, () -> {
                Property.forAll(gen, value -> {
                    assertTrue(value < 1000000);
                }).check(42L);
            });

            String msg = error.getMessage();
            assertTrue(msg.contains("Falsifying (shrunk) value:"), "Distinct shrunk value section present");
            assertTrue(msg.contains("Original (unshrunk) value:"), "Distinct original value section present");

            // Extract shrunk value
            String shrunkPart = msg.substring(msg.indexOf("Falsifying (shrunk) value:") + 26, msg.indexOf("Original (unshrunk) value:")).trim();
            // Extract original value
            String origPart = msg.substring(msg.indexOf("Original (unshrunk) value:") + 26, msg.indexOf("Reproduction:")).trim();

            assertEquals("1000000", shrunkPart, "Shrunk value is 1000000");
            assertNotEquals(shrunkPart, origPart, "Original raw value differs from shrunk value");
        }

        @Test
        @DisplayName("Execution statistics (examples run, invalid runs, shrink attempts) are accurate")
        void executionStatisticsAccurate() {
            Generator<Integer> gen = Generators.integers();

            AssertionError error = assertThrows(AssertionError.class, () -> {
                Property.forAll(gen, value -> {
                    assertTrue(value < 1000000);
                }).check(42L);
            });

            String msg = error.getMessage();
            assertTrue(msg.contains("Execution Statistics:"), "Execution statistics section present");
            assertTrue(msg.contains("Examples run: 1"), "Examples run count accurate");
            assertTrue(msg.contains("Invalid runs: 0"), "Invalid runs count accurate");
            assertTrue(msg.contains("Shrink attempts:"), "Shrink attempts present");

            // Extract shrink attempts count
            String shrinkStr = msg.substring(msg.indexOf("Shrink attempts: ") + 17, msg.indexOf("\n\nOriginal Exception:")).trim();
            int shrinkAttempts = Integer.parseInt(shrinkStr);
            assertTrue(shrinkAttempts > 0, "Shrink attempts count should be greater than zero");
        }

        @Test
        @DisplayName("Failing property master seed reproduces the exact same property failure when re-run")
        void masterSeedReproductionRoundTrips() {
            Generator<Integer> gen = Generators.integers();
            long masterSeed = 777L;

            // First run
            AssertionError error1 = assertThrows(AssertionError.class, () -> {
                Property.forAll(gen, value -> assertTrue(value < 500)).check(masterSeed);
            });

            // Second run with identical master seed
            AssertionError error2 = assertThrows(AssertionError.class, () -> {
                Property.forAll(gen, value -> assertTrue(value < 500)).check(masterSeed);
            });

            assertEquals(error1.getMessage(), error2.getMessage(), 
                "Running check(masterSeed) twice must produce the exact identical failure report");
        }
    }
}
