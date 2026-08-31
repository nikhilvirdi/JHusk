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
 */
public final class JHuskSummaryListener implements TestExecutionListener {

    private static final String SEPARATOR = "--------------------------------------------------";

    private volatile long planStartNanos;
    private volatile boolean parallelFallback;
    private volatile PropertyReporting.Sink previousSink;
    private String currentGroupClass;

    private final AtomicInteger totalPassed = new AtomicInteger();
    private final AtomicInteger totalFailed = new AtomicInteger();
    private final AtomicInteger totalSkipped = new AtomicInteger();
    private final AtomicLong totalExamples = new AtomicLong();
    private final List<String> failedPropertyNames = Collections.synchronizedList(new ArrayList<>());

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

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (testIdentifier.isTest()) {
            totalSkipped.incrementAndGet();
        }
    }

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

    private synchronized void printPass(String propertyId, int examples, long durationNanos) {
        totalExamples.addAndGet(examples);
        String stats = examples + " examples   " + TerminalFormat.formatSeconds(durationNanos);
        printLine(propertyId, TerminalFormat.label(true), stats, false);
    }

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
