package io.github.nikhilvirdi.jhusk.internal;

/**
 * Not a compatibility promise -- see this package's javadoc.
 *
 * <p>Lets the {@code junit} package's {@code TestExecutionListener}-based summary collect
 * {@code Property.check()} outcomes without {@code Property} printing them directly. Raw
 * (non-JUnit) {@code Property.check()} usage never has reporting enabled here, so it keeps its
 * own simple, unchanged direct-print fallback -- see {@link #activeSink()}.
 *
 * <p><b>Two-layer design.</b> {@link #activeSink()} only returns non-{@code null} when BOTH of
 * these are true:
 * <ol>
 *   <li>a sink is currently registered ({@link #setSink}) -- a run-scoped, not per-thread, fact:
 *       "is JHusk's JUnit summary infrastructure active for this run at all," set once by the
 *       summary listener when its test plan starts and restored when it finishes; and</li>
 *   <li>reporting is enabled on the <em>calling thread</em> ({@link #beginReporting}) -- a
 *       narrowly-scoped fact set by {@code PropertyExtension} immediately before it reflectively
 *       invokes the {@code @Property} method's {@code runner.check()} call, and cleared in a
 *       {@code finally} immediately after.</li>
 * </ol>
 * The second layer is what stops a plain {@code @Test} method's own direct
 * {@code Property.forAll(...).check()} call (JHusk's own white-box tests of {@code Property}'s
 * failure-reporting behavior do exactly this) from being mistaken for a real {@code @Property}
 * result: that call runs on the same thread as the surrounding JUnit test, but
 * {@code PropertyExtension} never touched that thread's gate for it, so {@link #activeSink()}
 * correctly returns {@code null} there regardless of whether a listener is registered for the run.
 *
 * @since 1.2.0
 */
public final class PropertyReporting {

    private PropertyReporting() {
    }

    /** Receives one {@code Property.check()} outcome as it completes. */
    public interface Sink {
        /**
         * Reports a successful check.
         *
         * @param propertyId the property identity
         * @param examples the number of successful examples run
         * @param durationNanos wall-clock time spent in {@code check()}, in nanoseconds
         */
        void reportPass(String propertyId, int examples, long durationNanos);

        /**
         * Reports a falsification.
         *
         * @param propertyId the property identity
         * @param shrinkAttempts the number of shrink attempts performed (0 if replayed from a
         *                       previously-shrunk stored failure)
         * @param shrunkValue the minimal falsifying value, already stringified
         * @param seed the master seed to reproduce this failure with
         * @param cause a short "SimpleExceptionName: message" summary of the underlying failure
         */
        void reportFail(String propertyId, int shrinkAttempts, String shrunkValue, long seed, String cause);
    }

    private static volatile Sink registeredSink;
    private static final ThreadLocal<Boolean> REPORTING_ENABLED = ThreadLocal.withInitial(() -> false);

    /**
     * Registers the sink for the current run, returning whatever sink was previously registered.
     * Called by the {@code junit} package's summary listener when a test plan starts.
     *
     * <p>Returning the previous sink (rather than this being a fire-and-forget setter) lets a
     * nested {@code Launcher} execution -- e.g. a listener's own test suite exercising itself via
     * {@code LauncherFactory.create(...)} -- restore exactly what was registered before it ran
     * (via {@link #restoreSink}) instead of unconditionally nulling out an outer, still-running
     * listener's registration when the nested run finishes.
     *
     * @param sink the sink to register
     * @return the sink that was registered before this call, or {@code null} if none was
     */
    public static Sink setSink(Sink sink) {
        Sink previous = registeredSink;
        registeredSink = sink;
        return previous;
    }

    /**
     * Restores a previously registered sink (or {@code null}), as returned by {@link #setSink}.
     *
     * @param previous the sink to restore
     */
    public static void restoreSink(Sink previous) {
        registeredSink = previous;
    }

    /**
     * Enables reporting on the calling thread. Called by {@code PropertyExtension} immediately
     * before invoking {@code runner.check()} for an {@code @Property} method.
     *
     * <p>Always pair with {@link #endReporting()} in a {@code finally} block -- an unmatched call
     * would leave reporting incorrectly enabled for whatever else runs on this thread afterward.
     */
    public static void beginReporting() {
        REPORTING_ENABLED.set(true);
    }

    /**
     * Disables reporting on the calling thread. Call from a {@code finally} block matching a
     * {@link #beginReporting()} call.
     */
    public static void endReporting() {
        REPORTING_ENABLED.remove();
    }

    /**
     * Returns the sink to report this thread's {@code Property.check()} outcome to, or
     * {@code null} if this call should keep its raw, direct-print behavior -- either because no
     * sink is registered for this run, or because this call did not originate from
     * {@code PropertyExtension} invoking an {@code @Property} method (see this class's javadoc).
     *
     * @return the sink to report to, or {@code null}
     */
    public static Sink activeSink() {
        return REPORTING_ENABLED.get() ? registeredSink : null;
    }
}
