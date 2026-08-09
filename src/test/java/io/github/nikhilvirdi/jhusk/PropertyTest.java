package io.github.nikhilvirdi.jhusk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

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
    @DisplayName("Filter exhaustion triggers invalid budget abort via PropertyExecutionException, not AssertionError")
    void filterExhaustionAborts() {
        // Generator that always marks itself invalid via an impossible filter
        Generator<Integer> gen = Generators.integers().filter(x -> false);

        // PropertyExecutionException (a RuntimeException), NOT AssertionError: exhausting the
        // invalid-run budget means the property couldn't be meaningfully checked at all -- it's a
        // setup problem (an over-restrictive filter here), not a finding that the code under test
        // is wrong. See Property's class Javadoc ("Exception semantics") for the full reasoning.
        // A caller doing the idiomatic catch (Exception e) / assertThrows(RuntimeException.class,
        // ...) must catch this -- AssertionError, a sibling of Exception under Throwable, would
        // silently evade both.
        PropertyExecutionException error = assertThrows(PropertyExecutionException.class, () -> {
            Property.forAll(gen, value -> {
                assertTrue(true);
            }).check(123L);
        });

        assertTrue(error.getMessage().contains("exhausted invalid budget"),
            "Should throw invalid budget exception, not silently pass");
        assertTrue(error.getMessage().contains("Too many invalid runs"),
            "Message should clarify too many invalid runs occurred");
    }

    @Test
    @DisplayName("A generator that crashes during data generation throws PropertyExecutionException, preserving the cause")
    void generatorCrashThrowsPropertyExecutionException() {
        RuntimeException generatorBug = new RuntimeException("bug inside a custom Generator");
        Generator<Integer> crashingGen = source -> {
            throw generatorBug;
        };

        PropertyExecutionException error = assertThrows(PropertyExecutionException.class, () ->
            Property.forAll(crashingGen, value -> { }).check(1L));

        assertTrue(error.getMessage().contains("Generator crashed during data generation"),
            "Message should identify a generator crash, distinct from a property falsification");
        assertSame(generatorBug, error.getCause(),
            "The generator's original exception must be preserved as the cause");
    }

    /**
     * Regression tests for an adversarially-reported "confirmed hang" in filter()-after-map()
     * composition (e.g. {@code lists(...).map(l -> dedupe(l)).filter(l -> l.size() >= 2)}, where
     * deduplication can collapse a list below the filter's threshold). Investigation (60+ direct
     * reproduction attempts across a systematic sweep of domain sizes and list-size bounds, plus a
     * mathematical trace of every loop involved: Property.check()'s Step 2 loop is bounded by
     * maxInvalidRuns, Generator.filter()'s retry loop is a fixed-bound for-loop, and
     * Generators.lists()'s while-loop strictly increases result.size() or breaks) found no
     * possible unbounded path and could not reproduce a hang under any parameterization. These
     * tests exist so that IF a future change to filter()/map()/lists() ever reintroduces an
     * unbounded path, CI fails loudly (via @Timeout) instead of hanging.
     */
    @Nested
    @DisplayName("filter()-after-map() composition never hangs (adversarial report follow-up)")
    class FilterAfterMapNeverHangs {

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("The exact reported repro completes quickly, every time")
        void reportedReproCompletesQuickly() {
            Generator<List<Integer>> listGen = Generators.lists(Generators.integers(0, 20), 2, 10);
            Generator<List<Integer>> dedupedGen = listGen.map(l -> {
                List<Integer> result = new ArrayList<>(new TreeSet<>(l));
                return result;
            });
            Generator<List<Integer>> gen = dedupedGen.filter(l -> l.size() >= 2);

            // Should complete well within the @Timeout; assertion body intentionally empty,
            // matching the original report -- the point is termination, not a specific outcome.
            Property.forAll(gen, list -> { }).check();
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("A guaranteed-always-reject filter()-after-map() throws PropertyExecutionException quickly, not hangs")
        void guaranteedRejectThrowsQuicklyNotHangs() {
            // integers(0, 0): every draw is exactly 0, so ANY list, after dedup, collapses to
            // size 1 -- filter(size >= 2) rejects every single attempt, with zero luck involved.
            // This is the worst case for the invalid-budget mechanism under this composition.
            Generator<List<Integer>> listGen = Generators.lists(Generators.integers(0, 0), 2, 2);
            Generator<List<Integer>> dedupedGen = listGen.map(l -> {
                List<Integer> result = new ArrayList<>(new TreeSet<>(l));
                return result;
            });
            Generator<List<Integer>> gen = dedupedGen.filter(l -> l.size() >= 2);

            PropertyExecutionException error = assertThrows(PropertyExecutionException.class, () ->
                Property.forAll(gen, list -> { }).check());

            assertTrue(error.getMessage().contains("exhausted invalid budget"),
                "filter()-after-map() must count toward and hit the SAME invalid-budget guard as a "
                    + "bare filter() on a primitive generator -- no bypass for composed generators");
        }
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

    @Nested
    @DisplayName("timeoutPerExample execution timeout tests")
    class TimeoutTests {

        @Test
        @DisplayName("timeoutPerExample defaults to no timeout (preserves current behavior)")
        void timeoutPerExampleDefaultsToNoTimeout() {
            Generator<Integer> gen = Generators.integers(1, 1);
            // By default, a slow assertion should complete normally without exception.
            // Using a short sleep to prove it doesn't get interrupted.
            Property.forAll(gen, value -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).check();
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("timeoutPerExample throws PropertyTimeoutException on genuine hang")
        void timeoutPerExampleThrowsOnGenuineHang() {
            Generator<Integer> gen = Generators.integers(42, 42);
            Property<Integer> prop = Property.forAll(gen, value -> {
                if (value == 42) {
                    while (true) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            // Daemon thread interrupted by shutdownNow(), exit cleanly
                            break;
                        }
                    }
                }
            }).timeoutPerExample(Duration.ofMillis(500));

            PropertyTimeoutException ex = assertThrows(PropertyTimeoutException.class, prop::check);
            assertTrue(ex.getMessage().contains("timed out"));
            assertTrue(ex.getMessage().contains("42"));
        }

        @Test
        @DisplayName("timeoutPerExample does not interfere with normal failure shrinking")
        void timeoutPerExampleDoesNotInterfereWithNormalFailureShrinking() {
            Generator<Integer> gen = Generators.integers();
            Property<Integer> prop = Property.forAll(gen, value -> {
                assertTrue(value < 100);
            }).timeoutPerExample(Duration.ofSeconds(5));

            AssertionError error = assertThrows(AssertionError.class, () -> prop.check(42L));
            String msg = error.getMessage();
            assertTrue(msg.contains("Falsifying (shrunk) value:"));
            assertTrue(msg.contains("100")); // Should still shrink to exactly 100
        }
    }
    @Nested
    @DisplayName("Exception hierarchy — FilterExhaustedException, GenerationBudgetExceededException, GeneratorCrashException")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("filter(v -> false) throws FilterExhaustedException, which is a PropertyExecutionException")
        void filterExhaustionThrowsFilterExhaustedException() {
            Generator<Integer> gen = Generators.integers().filter(v -> false);

            FilterExhaustedException ex = assertThrows(FilterExhaustedException.class, () ->
                Property.forAll(gen, v -> { }).check(1L));

            assertInstanceOf(PropertyExecutionException.class, ex,
                "FilterExhaustedException must be catchable as PropertyExecutionException");
            assertTrue(ex.getMessage().contains("exhausted invalid budget"));
        }

        @Test
        @DisplayName("100%-overrun generator throws GenerationBudgetExceededException, which is a PropertyExecutionException")
        void bufferOverrunThrowsGenerationBudgetExceededException() {
            // lists with minSize=40_000 guarantees every attempt exceeds the 8 KB DataSource buffer
            Generator<List<Integer>> gen = Generators.lists(Generators.integers(), 40_000, 40_000);

            GenerationBudgetExceededException ex = assertThrows(GenerationBudgetExceededException.class, () ->
                Property.forAll(gen, v -> { }).check(1L));

            assertInstanceOf(PropertyExecutionException.class, ex,
                "GenerationBudgetExceededException must be catchable as PropertyExecutionException");
            assertTrue(ex.getMessage().contains("exhausted invalid budget"));
        }

        @Test
        @DisplayName("crashing generator throws GeneratorCrashException with original cause preserved")
        void generatorCrashThrowsGeneratorCrashException() {
            RuntimeException generatorBug = new RuntimeException("simulated generator bug");
            Generator<Integer> crashingGen = source -> { throw generatorBug; };

            GeneratorCrashException ex = assertThrows(GeneratorCrashException.class, () ->
                Property.forAll(crashingGen, v -> { }).check(1L));

            assertInstanceOf(PropertyExecutionException.class, ex,
                "GeneratorCrashException must be catchable as PropertyExecutionException");
            assertTrue(ex.getMessage().contains("Generator crashed during data generation"));
            assertSame(generatorBug, ex.getCause(),
                "Original generator exception must be preserved as the cause");
        }

        @Test
        @DisplayName("mixed overrun+filter causes throw base PropertyExecutionException, not a specific subtype")
        void mixedInvalidCausesStillThrowsBasePropertyExecutionException() {
            // integers(0,0) guarantees every draw is 0, so every list collapses to size 1 after
            // dedup. filter(size >= 2) rejects every attempt — this is 100% filter rejection.
            // To get a genuinely MIXED scenario (some overruns, some filter rejections) we
            // combine a large-list generator (which overruns) with filter(false) on integers
            // by using a generator that alternates: we can't easily do that deterministically,
            // so instead we directly construct the scenario with maxInvalidRuns=1 and a generator
            // that first overruns then would filter — but the simplest provable mixed scenario is
            // to observe that the existing mixed-reason string path is triggered when
            // 0 < overrunRuns < invalidRuns. We approximate this by using a custom Generator
            // that overruns half the time and rejects the other half via DataSource status.
            //
            // Simpler approach: call check() against a generator that throws a known mix by
            // using integers() (never overruns) + a partial filter, then verify the base type
            // is thrown and neither specific subtype is thrown.
            //
            // Actually the cleanest provable mixed case: use a generator that on odd seeds
            // causes an overrun exception and on even seeds marks INVALID via filter. But we
            // can't control the internal seed parity. Instead we rely on: if we do NOT get
            // FilterExhaustedException and NOT get GenerationBudgetExceededException, we
            // confirm the mixed path threw the generic base.
            //
            // The simplest reliable approach: use a generator whose source we can manipulate.
            // We use a lambda Generator that throws DataSourceOverrunException directly ~50% and
            // calls source.markInvalid() the other ~50%, by using the draw value itself.
            Generator<Integer> mixedGen = source -> {
                // Draw one int to decide which failure mode to trigger.
                // drawInt() is the public DataSource API; this draw itself won't overrun (4 bytes).
                // We then deliberately trigger either mode based on parity to produce a reliable
                // mixed (overrun + filter-rejection) scenario.
                int drawn = source.drawInt();
                if (drawn % 2 == 0) {
                    // Simulate a filter rejection: mark the source invalid
                    source.markInvalid();
                    return -1;
                } else {
                    // Simulate a buffer overrun
                    throw new io.github.nikhilvirdi.jhusk.internal.DataSourceOverrunException("simulated overrun for mixed-cause test");
                }
            };

            PropertyExecutionException ex = assertThrows(PropertyExecutionException.class, () ->
                Property.forAll(mixedGen, v -> { }).check(1L));

            assertFalse(ex instanceof FilterExhaustedException,
                "Mixed-cause exhaustion must NOT throw FilterExhaustedException");
            assertFalse(ex instanceof GenerationBudgetExceededException,
                "Mixed-cause exhaustion must NOT throw GenerationBudgetExceededException");
            assertTrue(ex.getMessage().contains("exhausted invalid budget"));
        }
    }
    @Nested
    @DisplayName("ConfigurableGenerationBudgetTests \u2014 Property.withGenerationBudget(int)")
    class ConfigurableGenerationBudgetTests {

        @Test
        @DisplayName("withGenerationBudget allows 3000-element list that exceeds the default 8KB cap")
        void withGenerationBudgetAllowsLargerCollectionsToSucceed() {
            // lists(integers(), 3000, 3000) requires ~12000 bytes (4 bytes/int * 3000 elements),
            // which exceeds the default 8KB cap and throws GenerationBudgetExceededException
            // without withGenerationBudget. With 20KB budget it must complete successfully.
            assertDoesNotThrow(() ->
                Property.forAll(Generators.lists(Generators.integers(), 3000, 3000), list -> { })
                    .withGenerationBudget(20000)
                    .check(1L),
                "withGenerationBudget(20000) must allow a 3000-element integer list to generate"
            );
        }

        @Test
        @DisplayName("Default generation budget still throws GenerationBudgetExceededException for 3000-element list")
        void defaultGenerationBudgetStillMatchesEightKb() {
            // The same property WITHOUT withGenerationBudget must still throw, proving the default is unchanged.
            assertThrows(GenerationBudgetExceededException.class, () ->
                Property.forAll(Generators.lists(Generators.integers(), 3000, 3000), list -> { })
                    .check(1L),
                "Without withGenerationBudget, the default 8KB cap must still reject a 3000-element list"
            );
        }

        @Test
        @DisplayName("withGenerationBudget(0) and withGenerationBudget(-5) throw IllegalArgumentException eagerly")
        void invalidGenerationBudgetRejectedEagerly() {
            Generator<Integer> gen = Generators.integers();
            assertThrows(IllegalArgumentException.class,
                () -> Property.forAll(gen, v -> { }).withGenerationBudget(0),
                "withGenerationBudget(0) must throw IllegalArgumentException immediately");
            assertThrows(IllegalArgumentException.class,
                () -> Property.forAll(gen, v -> { }).withGenerationBudget(-5),
                "withGenerationBudget(-5) must throw IllegalArgumentException immediately");
        }
    }
}
