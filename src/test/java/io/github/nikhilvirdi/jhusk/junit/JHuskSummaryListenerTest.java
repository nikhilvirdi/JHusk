package io.github.nikhilvirdi.jhusk.junit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Exercises {@link JHuskSummaryListener} directly through a real {@code Launcher}, since that is
 * what actually invokes a {@code TestExecutionListener} in practice (Surefire, Gradle, and IDE
 * runners all go through {@code Launcher} -- {@code EngineTestKit}, used by this project's other
 * junit-package tests, deliberately bypasses it and would never call these listener methods).
 *
 * <p>Each test builds its own {@code Launcher} with {@code enableTestExecutionListenerAutoRegistration(false)}
 * and registers a fresh {@link JHuskSummaryListener} instance explicitly, instead of relying on
 * the real {@code META-INF/services}-registered instance -- that keeps this self-test isolated
 * from (and invisible to) the outer, real test-plan run these tests execute inside of.
 * {@code PropertyReporting}'s sink save/restore (see its javadoc) is what makes this nesting safe:
 * each inner run restores whatever sink the outer real listener had active before it started.
 */
@DisplayName("JHuskSummaryListener: grouped output, parallel fallback, and final summary")
class JHuskSummaryListenerTest {

    private static final Path FAILURE_DIR = Path.of(".jhusk");

    @AfterEach
    void clearStoredFailures() throws IOException {
        if (Files.isDirectory(FAILURE_DIR)) {
            try (var files = Files.list(FAILURE_DIR)) {
                files.filter(f -> f.getFileName().toString().contains("JHuskSummaryListenerTest"))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
            }
        }
    }

    static class PassingProps {
        @Property(examples = 10)
        void firstPasses(@ForAll int x) {
            assertTrue(x == x);
        }

        @Property(examples = 10)
        void secondPasses(@ForAll int x) {
            assertTrue(x == x);
        }
    }

    static class FailingProps {
        @Property(examples = 5)
        void alwaysFails(@ForAll int x) {
            assertTrue(false, "always fails");
        }
    }

    static class FailingPropsTwo {
        @Property(examples = 5)
        void alsoAlwaysFails(@ForAll int x) {
            assertTrue(false, "also always fails");
        }
    }

    static class TimeoutProps {
        @Property(examples = 20, timeoutMillis = 2000)
        void quickPropertyWithTimeout(@ForAll int x) {
            assertTrue(x == x);
        }
    }

    private String runUnderFreshListener(Class<?>... testClasses) {
        JHuskSummaryListener listener = new JHuskSummaryListener();
        DiscoverySelector[] selectors = Arrays.stream(testClasses)
            .map(c -> selectClass(c))
            .toArray(DiscoverySelector[]::new);
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors)
            .build();
        Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
            .enableTestExecutionListenerAutoRegistration(false)
            .addTestExecutionListeners(listener)
            .build());

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            launcher.execute(request);
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    @Test
    @DisplayName("Passing properties are grouped under one class header, printed as they complete")
    void groupedPassingOutput() {
        String output = runUnderFreshListener(PassingProps.class);

        String header = "JHuskSummaryListenerTest$PassingProps";
        int firstHeaderIndex = output.indexOf(header);
        assertTrue(firstHeaderIndex >= 0, "Class header must appear");
        int secondHeaderIndex = output.indexOf(header, firstHeaderIndex + header.length());
        assertEquals(-1, secondHeaderIndex, "Class header must appear exactly once, not once per property");

        assertTrue(output.contains("PASS  firstPasses"), "First property's PASS line must appear");
        assertTrue(output.contains("PASS  secondPasses"), "Second property's PASS line must appear");
        assertTrue(output.contains("10 examples"), "Example count must be shown");
        assertTrue(output.matches("(?s).*\\d+\\.\\d{2}s.*"), "A per-property duration must be shown");

        assertTrue(output.contains("Summary: 2 passed, 0 failed, 0 skipped"));
        assertTrue(output.contains("Examples: 20"), "Total examples across both properties must be 20");
    }

    @Test
    @DisplayName("A falsification prints an immediate failure detail block and is listed at the summary's end")
    void immediateFailureDetailBlock() {
        String output = runUnderFreshListener(FailingProps.class);

        assertTrue(output.contains("JHuskSummaryListenerTest$FailingProps"), "Class header must appear");
        assertTrue(output.contains("FAIL  JHuskSummaryListenerTest$FailingProps.alwaysFails"),
            "FAIL line must name its class inline, not just the bare method, so it's unambiguous on its own");
        assertTrue(output.contains("Falsified after"), "Shrink attempt count must be reported");
        assertTrue(output.contains("Value:"), "Falsifying value must be reported");
        assertTrue(output.contains("Seed:"), "Reproduction seed must be reported");
        assertTrue(output.contains("reproduce with check("), "Reproduction hint must be present");
        assertTrue(output.contains("Cause:"), "Underlying cause must be reported");
        assertTrue(output.contains("AssertionFailedError") || output.contains("AssertionError"),
            "Cause line must name the underlying exception type");

        assertTrue(output.contains("Summary: 0 passed, 1 failed, 0 skipped"));
        assertTrue(output.contains("alwaysFails"),
            "The failing property's name must be listed again after the summary block");
    }

    @Test
    @DisplayName("Parallel execution fallback: flat 'Class.method' lines, no class header")
    void parallelExecutionFallbackIsFlat() {
        System.setProperty("junit.jupiter.execution.parallel.enabled", "true");
        String output;
        try {
            output = runUnderFreshListener(PassingProps.class);
        } finally {
            System.clearProperty("junit.jupiter.execution.parallel.enabled");
        }

        assertFalse(output.contains("JHuskSummaryListenerTest$PassingProps\n"),
            "No standalone class header line when parallel execution is detected");
        assertTrue(output.contains("PASS  JHuskSummaryListenerTest$PassingProps.firstPasses"),
            "Flat fallback line must inline the (package-stripped) class name with the method");
        assertTrue(output.contains("PASS  JHuskSummaryListenerTest$PassingProps.secondPasses"));
    }

    @Test
    @DisplayName("The final Summary sums pass/fail counts and examples across multiple classes in one Launcher run")
    void crossClassAggregation() {
        // A single class can't prove aggregation works across classes -- both totalPassed/
        // totalFailed (from executionFinished) and totalExamples (from the sink) must be summed
        // correctly when TWO distinct top-level classes run through the SAME Launcher.execute().
        String output = runUnderFreshListener(PassingProps.class, FailingProps.class);

        assertTrue(output.contains("JHuskSummaryListenerTest$PassingProps"), "PassingProps header must appear");
        assertTrue(output.contains("JHuskSummaryListenerTest$FailingProps"), "FailingProps header must appear");
        assertTrue(output.contains("PASS  firstPasses"));
        assertTrue(output.contains("PASS  secondPasses"));
        assertTrue(output.contains("FAIL  JHuskSummaryListenerTest$FailingProps.alwaysFails"),
            "FAIL line must be class-qualified even under its own header, for at-a-glance disambiguation");

        // 2 passed (PassingProps) + 1 failed (FailingProps) = 3 leaf tests total, summed across
        // both classes, not reset or overwritten between them.
        assertTrue(output.contains("Summary: 2 passed, 1 failed, 0 skipped"),
            "Pass/fail counts must be summed across both classes");
        assertTrue(output.contains("Examples: 20"),
            "Example total must be summed across classes too (FailingProps contributes none, since it never passes)");
    }

    @Test
    @DisplayName("A @Property using timeoutMillis(...) still reports correctly, even though each example's "
        + "assertion runs on a separate timeout-worker thread")
    void timeoutConfiguredPropertyStillReportsCorrectly() {
        // Property.runAssertion() delegates each example's assertion to a fresh single-thread
        // executor when timeoutPerExample is set (Property.java's "JHusk-timeout-worker" thread),
        // blocking the calling thread on future.get() until it returns. PropertyReporting's
        // begin/endReporting() bracket the ENTIRE runner.check() call on the calling thread (see
        // PropertyExtension), and Property's own sink-reporting calls all happen back on that same
        // calling thread after runAssertion() returns -- never on the worker thread itself, which
        // only ever executes the reflected @Property method body, not any PropertyReporting call.
        // This test proves that empirically end-to-end, through the real Launcher, rather than
        // relying on that reasoning alone.
        String output = runUnderFreshListener(TimeoutProps.class);

        assertTrue(output.contains("JHuskSummaryListenerTest$TimeoutProps"), "Class header must appear");
        assertTrue(output.contains("PASS  quickPropertyWithTimeout"),
            "PASS line must appear despite each example running on a temporary timeout-worker thread");
        assertTrue(output.contains("20 examples"), "Example count must be reported correctly");
        assertTrue(output.contains("Summary: 1 passed, 0 failed, 0 skipped"));
        assertTrue(output.contains("Examples: 20"));
    }

    @Test
    @DisplayName("Failure blocks across multiple classes are each unambiguous about their source class")
    void multiClassFailuresAreUnambiguous() {
        // A single failing class can't prove disambiguation matters -- this drives TWO distinct
        // classes, EACH with its own failure, through one Launcher run, and confirms every FAIL
        // line names its own class rather than just a bare method name that could belong to either.
        String output = runUnderFreshListener(FailingProps.class, FailingPropsTwo.class);

        assertTrue(output.contains("FAIL  JHuskSummaryListenerTest$FailingProps.alwaysFails"),
            "First class's failure must be identifiable by its own FAIL line, not just its (possibly distant) header");
        assertTrue(output.contains("FAIL  JHuskSummaryListenerTest$FailingPropsTwo.alsoAlwaysFails"),
            "Second class's failure must likewise name its own class inline");

        // The bare, unqualified method name must never appear as a standalone FAIL line -- that's
        // exactly the ambiguity this fix closes ("no way to tell them apart from the failure block
        // alone"). "FAIL  alwaysFails" is not a substring of "FAIL  JHuskSummaryListenerTest$FailingProps
        // .alwaysFails" (the qualified line has the class name immediately after the two label
        // spaces, not "alwaysFails" directly), so this only passes once every FAIL line is truly
        // class-qualified.
        assertFalse(output.contains("FAIL  alwaysFails\n"),
            "FAIL line must never show just the bare method name");
        assertFalse(output.contains("FAIL  alsoAlwaysFails\n"),
            "FAIL line must never show just the bare method name");

        assertTrue(output.contains("Summary: 0 passed, 2 failed, 0 skipped"),
            "Both classes' failures must be counted");
    }
}
