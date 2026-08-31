package io.github.nikhilvirdi.jhusk.junit;

import io.github.nikhilvirdi.jhusk.Generator;
import io.github.nikhilvirdi.jhusk.Generators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.Events;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EngineTestKit.engine;

/**
 * Compiles and runs the exact code samples shown in README.md, checking that every code sample
 * compiles and behaves as shown. This is not a permanent fixture of the public API surface; it
 * exists purely so the README can never silently drift from the real API again without a test
 * failure catching it.
 */
@DisplayName("README usage examples compile and behave as documented")
class ReadmeExamplesTest {

    // ---- README "A minimal property" sample ----
    // List<Integer> can't be default-inferred (generic type erasure, per @ForAll's own javadoc),
    // so the README example explicitly supplies a generator via @ForAll("integerLists").
    static class ListReverseExample {
        static Generator<List<Integer>> integerLists() {
            return Generators.lists(Generators.integers());
        }

        @Property
        void reversingTwiceReturnsTheOriginalList(@ForAll("integerLists") List<Integer> list) {
            List<Integer> reversed = new ArrayList<>(list);
            Collections.reverse(reversed);
            Collections.reverse(reversed);
            assertEquals(list, reversed);
        }
    }

    @Test
    @DisplayName("the minimal-property README sample runs cleanly")
    void minimalPropertySampleRuns() {
        // EngineTestKit bypasses the real Launcher entirely, so this nested fixture run never
        // reaches the outer real test plan's own TestExecutionListener -- but it still runs on the
        // same thread and still goes through the real PropertyExtension, which would otherwise
        // report this fixture's result to whatever sink is registered for the OUTER test run this
        // method is running inside of, inflating its example/pass counts with a result the outer
        // run's own executionFinished-based bookkeeping never actually counted.
        io.github.nikhilvirdi.jhusk.internal.PropertyReporting.Sink suspended =
            io.github.nikhilvirdi.jhusk.internal.PropertyReporting.setSink(null);
        Events events;
        try {
            events = engine("junit-jupiter")
                    .selectors(selectClass(ListReverseExample.class))
                    .execute()
                    .testEvents();
        } finally {
            io.github.nikhilvirdi.jhusk.internal.PropertyReporting.restoreSink(suspended);
        }

        events.assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
    }

    // ---- README "A shrunk failure, before and after" sample ----
    // Captures the real Property.check() failure report for a list-based property so the README's
    // prose ("a forty-element mess might shrink down to two elements") can quote genuine output
    // instead of an invented format.
    @Test
    @DisplayName("captures a real shrink report for a list property, for README accuracy")
    void capturesRealShrinkReportForReadme(@TempDir Path tempDir) {
        // withStorageDir(tempDir) keeps this deliberately-always-failing property from persisting
        // its shrunk buffer to the real .jhusk/ -- the same class of self-pollution bug fixed in
        // PropertyExtensionTest (a stored failure changes which report branch a later run takes).
        // This test doesn't exercise persistence at all, so isolating it away from the default
        // directory is strictly simpler than adding cleanup hooks.
        Generator<List<Integer>> gen = Generators.lists(Generators.integers(0, 100_000), 0, 50);

        assertThrows(AssertionError.class, () ->
                io.github.nikhilvirdi.jhusk.Property.forAll(gen, list -> {
                    for (int value : list) {
                        assertTrue(value <= 10, "no element may exceed 10");
                    }
                }).withStorageDir(tempDir).check(7L)
        );
    }
}
