package io.github.nikhilvirdi.jhusk.junit;

import io.github.nikhilvirdi.jhusk.internal.ConsolidatedWarnings;
import io.github.nikhilvirdi.jhusk.internal.PropertyReporting;
import io.github.nikhilvirdi.jhusk.internal.TerminalFormat;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registered via {@code META-INF/services} so JUnit Platform's {@code Launcher} -- what Surefire,
 * Gradle, and IDE test runners actually use to execute JUnit 5 tests -- picks this up
 * automatically with no user configuration required.
 *
 * <p>Collects every {@code @Property}/{@code @ForAll} {@code check()} outcome reported through
 * {@link PropertyReporting} and prints JHusk's per-property output as each one finishes (grouped
 * under a class header when test execution is sequential, or as flat fully-qualified lines when
 * JUnit 5 parallel execution is active and completion order can't be relied on for grouping),
 * plus one final summary block once the whole test plan completes.
 *
 * <p>Plain {@code @Test} methods are counted toward the final pass/fail/skip totals (via this
 * listener's own {@link #executionFinished}/{@link #executionSkipped} callbacks, which see every
 * test in the plan) but are otherwise untouched -- their own output is entirely Surefire's / the
 * runner's concern.
 *
 * @since 1.2.0
 */
public final class JHuskSummaryListener implements TestExecutionListener {

    private static final String SEPARATOR = "--------------------------------------------------";

    /** Monotonic start timestamp in nanoseconds for calculating total test plan duration. */
    private volatile long planStartNanos;

    /** When true, formats each line with a fully-qualified name instead of grouping by class. */
    private volatile boolean parallelFallback;

    /** The sink registered before this test plan started, restored on completion for nested runners. */
    private volatile PropertyReporting.Sink previousSink;

    /** The simple class name currently printed as a section header during sequential execution. */
    private String currentGroupClass;

    private final AtomicInteger totalPassed = new AtomicInteger();
    private final AtomicInteger totalFailed = new AtomicInteger();
    private final AtomicInteger totalSkipped = new AtomicInteger();
    private final AtomicLong totalExamples = new AtomicLong();

    /** Collects failed property identifiers to reprint in the final summary block. */
    private final List<String> failedPropertyNames = Collections.synchronizedList(new ArrayList<>());

    /**
     * Initializes listener state at the start of a JUnit test plan execution.
     *
     * <p>Resets all counters, records the start timestamp, inspects configuration properties to
     * detect whether parallel test execution is active, and registers this listener's sink with
     * {@link PropertyReporting} to begin capturing property execution results.
     *
     * @param testPlan the test plan being executed
     * @see PropertyReporting#setSink(PropertyReporting.Sink)
     */
    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        planStartNanos = System.nanoTime();
        totalPassed.set(0);
        totalFailed.set(0);
        totalSkipped.set(0);
        totalExamples.set(0);
        failedPropertyNames.clear();
        currentGroupClass = null;
        parallelFallback = isParallelExecutionEnabled();
        previousSink = PropertyReporting.setSink(new SinkImpl());
    }

    /**
     * Records when a test execution is skipped by the test engine.
     *
     * <p>Increments the skipped test counter if the identifier represents a test method (filtering
     * out test containers and class-level identifiers) so skipped tests are reflected in the final
     * summary totals.
     *
     * @param testIdentifier identifier of the skipped test or container
     * @param reason human-readable reason the execution was skipped, or empty if none provided
     */
    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (testIdentifier.isTest()) {
            totalSkipped.incrementAndGet();
        }
    }

    /**
     * Records the final execution status of an individual test.
     *
     * <p>Tracks test completion across both property-based tests and traditional tests in the plan,
     * updating the respective pass, fail, or aborted/skipped counters. Ignores container-level
     * notifications to ensure counts reflect individual test invocations.
     *
     * @param testIdentifier identifier of the test or container that finished
     * @param result the execution outcome (SUCCESSFUL, FAILED, or ABORTED)
     */
    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        if (!testIdentifier.isTest()) {
            return;
        }
        switch (result.getStatus()) {
            case SUCCESSFUL -> totalPassed.incrementAndGet();
            case FAILED -> totalFailed.incrementAndGet();
            case ABORTED -> totalSkipped.incrementAndGet();
        }
    }

    /**
     * Concludes test plan execution, prints the final summary, and cleans up listener resources.
     *
     * <p>Restores the previous reporting sink in {@link PropertyReporting} (preserving nesting
     * isolation), flushes all accumulated persistence warnings through
     * {@link ConsolidatedWarnings#flush()}, and outputs the aggregate summary banner displaying
     * total passed/failed/skipped tests, cumulative property examples tested, elapsed wall-clock
     * duration, and the list of any failed property names.
     *
     * @param testPlan the executed test plan
     * @see PropertyReporting#restoreSink(PropertyReporting.Sink)
     * @see ConsolidatedWarnings#flush()
     */
    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        PropertyReporting.restoreSink(previousSink);
        ConsolidatedWarnings.flush();

        long durationNanos = System.nanoTime() - planStartNanos;
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("Summary: " + totalPassed.get() + " passed, " + totalFailed.get()
            + " failed, " + totalSkipped.get() + " skipped");
        System.out.println("Examples: " + String.format(Locale.US, "%,d", totalExamples.get()));
        System.out.println("Duration: " + String.format(Locale.US, "%.1fs", durationNanos / 1_000_000_000.0));
        System.out.println(SEPARATOR);

        synchronized (failedPropertyNames) {
            for (String name : failedPropertyNames) {
                System.out.println(name);
            }
        }
    }

    /**
     * Detects whether JUnit 5 parallel test execution is configured, the same way JUnit itself
     * resolves the setting: an explicit system property first, falling back to a
     * {@code junit-platform.properties} classpath resource. {@code TestPlan} does not expose the
     * engine's resolved {@code ConfigurationParameters} to listeners, so this is the closest a
     * listener can get without engine-internal access.
     */
    private static boolean isParallelExecutionEnabled() {
        String sysProp = System.getProperty("junit.jupiter.execution.parallel.enabled");
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }
        try (InputStream in = JHuskSummaryListener.class.getClassLoader()
                .getResourceAsStream("junit-platform.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return Boolean.parseBoolean(props.getProperty("junit.jupiter.execution.parallel.enabled", "false"));
            }
        } catch (IOException ignored) {
            // Fall through -- treat as sequential when the config file can't be read.
        }
        return false;
    }

    /**
     * Splits an auto-generated {@code "FQCN.method"} property id (what {@code PropertyExtension}
     * always derives for an unnamed {@code @Property}) into its simple, nested-class-aware class
     * name and method name. Returns {@code null} for a custom {@code @Property(name = ...)} with
     * no dot to split on -- those print as an ungrouped flat line instead.
     */
    private static String[] splitClassAndMethod(String propertyId) {
        int lastDot = propertyId.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        String className = propertyId.substring(0, lastDot);
        String method = propertyId.substring(lastDot + 1);
        int classPackageDot = className.lastIndexOf('.');
        String simpleClassName = classPackageDot < 0 ? className : className.substring(classPackageDot + 1);
        return new String[]{simpleClassName, method};
    }

    /**
     * Prints a formatted passing line for a single property check.
     *
     * <p>Increments the aggregate example counter and formats the execution duration via
     * {@link TerminalFormat#formatSeconds(long)}.
     *
     * @param propertyId the property identifier
     * @param examples number of successful random examples completed
     * @param durationNanos wall-clock duration in nanoseconds
     */
    private synchronized void printPass(String propertyId, int examples, long durationNanos) {
        totalExamples.addAndGet(examples);
        String stats = examples + " examples   " + TerminalFormat.formatSeconds(durationNanos);
        printLine(propertyId, TerminalFormat.label(true), stats, false);
    }

    /**
     * Prints detailed diagnostic output for a falsified property check.
     *
     * <p>Records the property identifier for inclusion in the final summary list, outputs a
     * class-qualified failure header, and prints the falsified value, reproduction seed, and
     * triggering cause.
     *
     * @param propertyId the property identifier
     * @param shrinkAttempts number of shrink iterations attempted (0 if replayed from storage)
     * @param shrunkValue string representation of the minimal failing input
     * @param seed master seed for reproducing this exact failure
     * @param cause description of the underlying assertion or exception failure
     */
    private synchronized void printFail(String propertyId, int shrinkAttempts, String shrunkValue, long seed,
                                         String cause) {
        failedPropertyNames.add(propertyId);
        // Unlike a PASS line, a FAIL line always names its class inline (not just via the header
        // above it): a failure is exactly the line a developer scans a long log for or greps out
        // in isolation, and with several classes' failures in one run, the header alone -- which
        // may be many lines above by the time a second or third property in the same class group
        // fails -- isn't enough to tell them apart "from the failure block alone."
        printLine(propertyId, TerminalFormat.label(false), null, true);
        System.out.println("        Falsified after " + shrinkAttempts + " shrink attempts");
        System.out.println("        Value:  " + shrunkValue);
        System.out.println("        Seed:   " + seed + "L (reproduce with check(" + seed + "L))");
        System.out.println("        Cause:  " + cause);
    }

    /**
     * Prints a formatted status line for a property result to standard output.
     *
     * <p>Handles grouping by class name during sequential runs or prints flat fully-qualified
     * identifiers when parallel execution is enabled.
     *
     * @param propertyId the property identifier
     * @param label the colored or plain PASS/FAIL label
     * @param trailingStats optional trailing statistics (e.g. example count and duration), or {@code null}
     * @param qualifyMemberName whether to include the simple class name before the method name
     *                          ({@code SimpleClass.methodName}); {@code true} for failures so they
     *                          remain unambiguously identifiable when scanned in isolation, and
     *                          {@code false} for grouped passing lines to avoid repetitive output
     */
    private void printLine(String propertyId, String label, String trailingStats, boolean qualifyMemberName) {
        String[] parts = splitClassAndMethod(propertyId);
        String suffix = trailingStats == null ? "" : "   " + trailingStats;

        if (parallelFallback) {
            currentGroupClass = null;
            String flatName = parts == null ? propertyId : parts[0] + "." + parts[1];
            System.out.println(label + "  " + flatName + suffix);
            return;
        }

        if (parts == null) {
            // Custom, ungroupable property name -- print flat and break the current group so the
            // next grouped property re-prints its own header.
            currentGroupClass = null;
            System.out.println(label + "  " + propertyId + suffix);
            return;
        }

        String simpleClassName = parts[0];
        String method = parts[1];
        if (!simpleClassName.equals(currentGroupClass)) {
            if (currentGroupClass != null) {
                System.out.println();
            }
            System.out.println(simpleClassName);
            currentGroupClass = simpleClassName;
        }
        String memberName = qualifyMemberName ? simpleClassName + "." + method : method;
        System.out.println("  " + label + "  " + memberName + suffix);
    }

    /**
     * Bridges {@link PropertyReporting.Sink} callbacks to this listener's print methods.
     */
    private final class SinkImpl implements PropertyReporting.Sink {
        @Override
        public void reportPass(String propertyId, int examples, long durationNanos) {
            printPass(propertyId, examples, durationNanos);
        }

        @Override
        public void reportFail(String propertyId, int shrinkAttempts, String shrunkValue, long seed, String cause) {
            printFail(propertyId, shrinkAttempts, shrunkValue, seed, cause);
        }
    }
}
