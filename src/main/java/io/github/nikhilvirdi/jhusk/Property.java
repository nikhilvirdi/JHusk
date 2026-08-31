package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.DataSourceOverrunException;
import io.github.nikhilvirdi.jhusk.internal.PropertyReporting;
import io.github.nikhilvirdi.jhusk.internal.ShrinkHarness;
import io.github.nikhilvirdi.jhusk.internal.Shrinker;
import io.github.nikhilvirdi.jhusk.internal.TerminalFormat;

import java.nio.file.Path;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A property runner that binds a {@link Generator} to a test assertion,
 * executing the property multiple times with random byte sequences.
 * 
 * <p>It enforces an invalid budget to ensure properties don't silently pass
 * by rejecting too many examples (e.g. via aggressive filtering).
 * 
 * <p>Supports failure persistence: on failure, the minimal shrunk byte buffer is saved
 * to {@code .jhusk/}. Subsequent property runs replay stored failures first, surfacing
 * regressions immediately before generating new random examples.
 *
 * <p><b>Thread-safety:</b> {@code Property<T>} is a mutable builder — {@link #named}, {@link
 * #examples(int)}, {@link #withStorageDir(Path)}, and {@link #withFailureStorage} all mutate
 * instance state with no synchronization. Build and configure a {@code Property} instance on a
 * single thread, then call {@link #check()}/{@link #check(long)}; do not mutate its configuration
 * concurrently with a {@code check()} call, and do not call {@code check()} concurrently from
 * multiple threads on the same instance. {@code check()} itself creates a fresh, thread-local
 * {@link DataSource} for every example, so it does not race against itself internally. Failure
 * persistence writes atomically (see {@link FailureStorage}'s own thread-safety note), so two
 * {@code check()} calls that share both a storage directory and a property identity can't corrupt
 * each other's stored buffer — but they can still race for which one's failure ends up stored,
 * since one write's atomic rename simply wins outright over the other's. Distinct identities, or
 * distinct storage directories, are entirely unaffected.
 *
 * <p><b>Exception semantics:</b> {@code check()} distinguishes two fundamentally different kinds
 * of "this call did not return normally," deliberately using two different exception types:
 * <ul>
 *   <li><b>{@link AssertionError}</b> — a falsifying example was genuinely found: the code under
 *       test violated the property, whether freshly discovered (with the shrunk report as
 *       the message) or replayed from a previously stored failure. This mirrors a plain failed
 *       assertion on purpose — the entire point of a property check is to behave like an assertion
 *       that ran against many inputs instead of one, and {@code AssertionError} is exactly what a
 *       reader expects to see for "the thing being tested is wrong." This also matches JUnit
 *       Jupiter's own convention: {@code org.opentest4j.AssertionFailedError} (thrown by {@code
 *       assertEquals}, {@code assertTrue}, etc.) is itself an {@code AssertionError} subclass.</li>
 *   <li><b>{@link PropertyExecutionException}</b> (a {@code RuntimeException}) — the property
 *       could not be meaningfully checked at all: the invalid-run budget was exhausted (an
 *       over-restrictive {@code filter(...)}, most commonly), or the generator itself crashed
 *       while producing an example. Neither is a finding about the code under test — they're
 *       problems with the test's own setup or a bug in a custom {@link Generator} — so they use an
 *       ordinary {@code RuntimeException}, catchable by the idiomatic {@code catch (Exception e)}
 *       or {@code catch (RuntimeException e)}, unlike {@code AssertionError} (a sibling of {@code
 *       Exception} under {@code Throwable}, deliberately excluded from both). Concretely: {@code
 *       assertThrows(RuntimeException.class, ...)} around a {@code check()} call will <em>not</em>
 *       catch a genuine falsification, but <em>will</em> catch invalid-budget exhaustion or a
 *       generator crash — that split is intentional, not an oversight.</li>
 * </ul>
 *
 * @param <T> the type of value generated and tested
 */
public final class Property<T> {

    private static final byte[] EDGE_CASE_FILL_BYTES = { (byte) 0x00, (byte) 0xFF };
    private static final AtomicBoolean BANNER_PRINTED = new AtomicBoolean(false);

    /**
     * Resets the one-time banner gate. Package-private and test-only --
     * not part of the public API surface. Callable from PropertyTest.java
     * since it's in the same package.
     */
    static void resetBannerStateForTesting() {
        BANNER_PRINTED.set(false);
    }

    private static final String BANNER_ART = """
        .....## ##...## ##...## .#####. ##...##
        .....## ##...## ##...## ##..... ##..##.
        .....## ##...## ##...## ##..... ##.##..
        .....## ####### ##...## .#####. ####...
        .....## ##...## ##...## .....## ####...
        ##...## ##...## ##...## .....## ##.##..
        ##...## ##...## ##...## .....## ##..##.
        .#####. ##...## .#####. .#####. ##...##""";

    private static void maybePrintBanner() {
        if (BANNER_PRINTED.compareAndSet(false, true)) {
            if (Boolean.parseBoolean(System.getProperty("jhusk.banner", "false"))) {
                System.out.println(BANNER_ART.replace('.', ' '));
            }
        }
    }

    private final Generator<T> generator;
    private final Consumer<T> assertion;
    
    private String name;
    private int examples = 100;
    private int maxInvalidRuns = 1000;
    private FailureStorage failureStorage = new FailureStorage();
    private Duration timeoutPerExample = null;
    private int generationBudgetBytes = DataSource.MAX_BUFFER_SIZE;
    private Predicate<T> assumption = null;

    private Property(String name, Generator<T> generator, Consumer<T> assertion) {
        this.name = name;
        this.generator = generator;
        this.assertion = assertion;
    }

    private Property(Generator<T> generator, Consumer<T> assertion) {
        this(null, generator, assertion);
    }

    /**
     * Creates a new property binding a generator to an assertion block.
     *
     * @param generator the generator supplying test data
     * @param assertion the property logic; should throw an AssertionError (or Exception) if the property fails
     * @param <T> the type of data generated
     * @return a runnable Property instance
     */
    public static <T> Property<T> forAll(Generator<T> generator, Consumer<T> assertion) {
        return new Property<>(generator, assertion);
    }

    /**
     * Creates a named property binding a generator to an assertion block.
     *
     * @param name explicit property identity name for stable failure persistence
     * @param generator the generator supplying test data
     * @param assertion the property logic
     * @param <T> the type of data generated
     * @return a runnable Property instance
     */
    public static <T> Property<T> forAll(String name, Generator<T> generator, Consumer<T> assertion) {
        return new Property<>(name, generator, assertion);
    }

    /**
     * Assigns an explicit identity name to this property for persistent failure tracking.
     * Recommended over auto-detection (see {@link #resolvePropertyId()}) to survive refactoring.
     *
     * @param name the explicit property identity, used as the {@code .jhusk/<name>.bytes} filename
     * @return this instance, for fluent chaining
     */
    public Property<T> named(String name) {
        this.name = name;
        return this;
    }

    /**
     * Points failure persistence at a custom directory instead of the default {@code .jhusk/}.
     *
     * <p>Not reachable from the {@code @Property} JUnit annotation, which always uses the
     * default directory — this is for direct {@code Property.forAll(...)} usage, most commonly
     * to isolate a test's failure storage into a temporary directory (see {@code FailurePersistenceTest}
     * for the pattern).
     *
     * @param storageDir the directory where failure buffers will be read from and written to
     * @return this instance, for fluent chaining
     */
    public Property<T> withStorageDir(Path storageDir) {
        this.failureStorage = new FailureStorage(storageDir);
        return this;
    }

    /**
     * Sets the exact {@link FailureStorage} instance to use for failure persistence.
     *
     * <p>{@link #withStorageDir(Path)} covers the common case (redirecting storage location, e.g.
     * for test isolation) and only requires a {@link Path}; reach for this overload when you need
     * to reuse a specific, already-configured {@code FailureStorage} instance across multiple
     * {@code Property} runs.
     *
     * @param storage the failure storage instance to use
     * @return this instance, for fluent chaining
     */
    public Property<T> withFailureStorage(FailureStorage storage) {
        this.failureStorage = storage;
        return this;
    }

    /**
     * Sets the number of successful examples required for the property to pass.
     * Default is 100.
     *
     * @param examples the number of successful examples to require
     * @return this instance, for fluent chaining
     */
    public Property<T> examples(int examples) {
        this.examples = examples;
        return this;
    }

    /**
     * Sets the maximum number of invalid runs (filter rejections or generator overruns) tolerated
     * before {@link #check()}/{@link #check(long)} aborts with an invalid-budget {@link
     * AssertionError}. Default is 1000.
     *
     * <p>Raise this if a generator is legitimately expected to reject a large fraction of draws
     * (e.g. a narrow {@code filter(...)} over a wide domain) and 1000 rejected attempts isn't
     * enough headroom to reach {@link #examples(int)} successful ones.
     *
     * @param maxInvalidRuns the maximum number of invalid runs to tolerate
     * @return this instance, for fluent chaining
     */
    public Property<T> maxInvalidRuns(int maxInvalidRuns) {
        this.maxInvalidRuns = maxInvalidRuns;
        return this;
    }

    /**
     * Sets a property-level precondition checked against each successfully
     * generated value, before the assertion runs. Distinct from
     * {@link Generator#filter}: a filter narrows what a generator can ever
     * produce and is checked per-value as it's drawn (potentially deep
     * inside a composed generator); an assumption is checked once, against
     * the final fully-assembled value, right before the assertion --
     * appropriate for preconditions that only make sense once the whole
     * value exists (e.g. "the two halves of this generated pair must not be
     * equal"), rather than being expressible as a constraint on any single
     * generator in isolation.
     *
     * <p>A value rejected by this predicate counts as an invalid run,
     * exactly like a filter rejection or buffer overrun -- it does not
     * count toward {@link #examples(int)}, and it counts against
     * {@link #maxInvalidRuns(int)}. It is reported separately in
     * diagnostics from filter/overrun causes so an over-restrictive
     * assumption is distinguishable from an over-restrictive filter.
     *
     * <p>Not currently reachable from the {@code @Property} JUnit
     * annotation -- direct {@link #forAll} usage only, matching
     * {@link #withGenerationBudget(int)}'s current scope.
     *
     * @param assumption the precondition a generated value must satisfy to
     *                    be checked against the assertion
     * @return this instance, for fluent chaining
     */
    public Property<T> assuming(Predicate<T> assumption) {
        this.assumption = assumption;
        return this;
    }

    /**
     * Sets a per-example execution timeout.
     * If an example takes longer than this duration, a PropertyTimeoutException is thrown.
     * Default is null (no timeout, executes on the current thread).
     *
     * @param timeout the maximum duration to allow for a single example
     * @return this instance, for fluent chaining
     */
    public Property<T> timeoutPerExample(Duration timeout) {
        this.timeoutPerExample = timeout;
        return this;
    }

    /**
     * Sets the per-example generation buffer capacity (in bytes).
     * Default is {@link DataSource#MAX_BUFFER_SIZE} (8192 bytes).
     *
     * <p>Raising this limit allows generators to produce larger values — bigger collections,
     * longer strings, more deeply nested structures — that would otherwise exhaust the default
     * 8KB buffer and cause a {@link GenerationBudgetExceededException}. The tradeoff is a larger
     * possible shrink search space: shrink time can scale with buffer size, so only raise this
     * as far as your actual data needs require.
     *
     * <p><b>Note:</b> This setting is not currently threaded through the JUnit
     * {@code @Property} annotation. It is only available via direct
     * {@code Property.forAll(...)} usage.
     *
     * @param bytes the maximum number of bytes the {@link DataSource} generation buffer may hold;
     *              must be &gt; 0
     * @return this instance, for fluent chaining
     * @throws IllegalArgumentException if {@code bytes} is not positive
     */
    public Property<T> withGenerationBudget(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException(
                "generationBudgetBytes must be positive but was: " + bytes);
        }
        this.generationBudgetBytes = bytes;
        return this;
    }

    /**
     * Checks the property using a randomly chosen master seed.
     *
     * <p>Before generating random examples, also runs two deterministic edge cases (all-zero and
     * all-0xFF byte buffers) covering every generator's shrink-target boundaries.
     *
     * <p>Equivalent to {@code check(new SplittableRandom().nextLong())}: replays any stored
     * failure first (see {@link #check(long)}), then generates fresh random examples if none is
     * stored or the stored one no longer reproduces.
     *
     * @throws AssertionError if a falsifying example is found (see this class's "Exception semantics")
     * @throws PropertyExecutionException if the invalid-run budget is exhausted or the generator crashes
     */
    public void check() {
        check(new SplittableRandom().nextLong());
    }

    /**
     * Checks the property using the specified master seed.
     * 
     * <p>Before generating random examples, also runs two deterministic edge cases (all-zero and
     * all-0xFF byte buffers) covering every generator's shrink-target boundaries.
     * 
     * <p>Replays stored failures from {@code .jhusk/} first before generating random examples.
     * If a stored failure still fails, it reports immediately without generating new examples.
     * If a stored failure passes, it is automatically pruned from disk.
     * 
     * @param masterSeed the master seed
     * @throws AssertionError if a falsifying example is found (see this class's "Exception semantics")
     * @throws PropertyExecutionException if the invalid-run budget is exhausted or the generator crashes
     */
    public void check(long masterSeed) {
        long startNanos = System.nanoTime();
        maybePrintBanner();
        String propertyId = resolvePropertyId();

        // -------------------------------------------------------------------
        // Step 1: Replay stored failure from .jhusk/ if present
        // -------------------------------------------------------------------
        Optional<byte[]> stored = failureStorage.loadFailure(propertyId);
        if (stored.isPresent()) {
            byte[] storedBuffer = stored.get();
            DataSource storedSource = new DataSource(storedBuffer, generationBudgetBytes);
            T storedValue = null;
            boolean stillFails = false;
            Throwable storedFailure = null;

            try {
                storedValue = generator.generate(storedSource);
                storedSource.freeze();
                // R4: only a VALID replay can determine pass/fail. A stored buffer can OVERRUN if
                // the generator's shape changed since it was saved (e.g. a refactor added a
                // parameter or a composed generator now consumes more bytes) -- replay then reads
                // zero-padded garbage for whatever ran off the end. Checking "!= INVALID" alone
                // let OVERRUN slip through as if it were a normal run: the garbage value would
                // silently satisfy the assertion, and the stored failure -- a real, never actually
                // re-validated regression -- would be pruned as "fixed" below.
                if (storedSource.getStatus() == DataSource.Status.VALID) {
                    runAssertion(storedValue, masterSeed);
                }
            } catch (Throwable failure) {
                // A timeout is not a falsification -- propagate it unwrapped rather than
                // wrapping it in the "Replayed Stored Failure" AssertionError below, matching
                // how the edge-case and random loops treat PropertyTimeoutException.
                if (failure instanceof PropertyTimeoutException) {
                    throw (PropertyTimeoutException) failure;
                }
                stillFails = true;
                storedFailure = failure;
            }

            if (stillFails) {
                PropertyReporting.Sink sink = PropertyReporting.activeSink();
                if (sink != null) {
                    String cause = storedFailure.getClass().getSimpleName() + ": " + storedFailure.getMessage();
                    sink.reportFail(propertyId, 0, String.valueOf(storedValue), masterSeed, cause);
                }

                // Stored failure STILL FAILS! Report immediately without running new examples.
                String report = String.format(
                    "\n\n======================================================================\n" +
                    "Property Falsified! (Replayed Stored Failure)\n" +
                    "======================================================================\n\n" +
                    "Falsifying (shrunk) value:\n" +
                    "  %s\n\n" +
                    "Original (unshrunk) value:\n" +
                    "  %s\n\n" +
                    "Reproduction:\n" +
                    "  Stored failure replayed from .jhusk/ for property '%s'\n" +
                    "  To reproduce, run: check(%dL) (Seed: %dL)\n\n" +
                    "Execution Statistics:\n" +
                    "  Examples run: 0 (Stored failure replayed)\n" +
                    "  Invalid runs: 0\n" +
                    "  Shrink attempts: 0 (Previously shrunk)\n\n" +
                    "Original Exception:\n" +
                    "  %s\n" +
                    "======================================================================\n",
                    String.valueOf(storedValue),
                    String.valueOf(storedValue),
                    propertyId,
                    masterSeed,
                    masterSeed,
                    storedFailure.toString()
                );
                throw new AssertionError(report, storedFailure);
            } else {
                // Stored failure now passes or is invalid/overrun (the bug was fixed!)
                // Automatically prune the stored failure file.
                failureStorage.pruneFailure(propertyId);
            }
        }

        // -------------------------------------------------------------------
        // Step 1.5: Deterministic edge cases
        // -------------------------------------------------------------------
        int successfulRuns = 0;
        int invalidRuns = 0;
        int overrunRuns = 0;
        int assumptionRejections = 0;

        for (byte fillByte : EDGE_CASE_FILL_BYTES) {
            byte[] edgeBuffer = buildEdgeCaseBuffer(fillByte);
            DataSource edgeSource = new DataSource(edgeBuffer, generationBudgetBytes);
            T value;
            try {
                value = generator.generate(edgeSource);
                edgeSource.freeze();
            } catch (io.github.nikhilvirdi.jhusk.internal.DataSourceOverrunException e) {
                invalidRuns++;
                overrunRuns++;
                continue;
            } catch (Throwable t) {
                throw new GeneratorCrashException(
                    "Generator crashed during data generation (edge-case fillByte=0x"
                    + String.format("%02X", fillByte) + ")", t);
            }
            if (edgeSource.getStatus() == DataSource.Status.OVERRUN) {
                invalidRuns++;
                overrunRuns++;
                continue;
            } else if (edgeSource.getStatus() == DataSource.Status.INVALID) {
                invalidRuns++;
                continue;
            }

            if (assumption != null && !assumption.test(value)) {
                invalidRuns++;
                assumptionRejections++;
                continue;
            }

            try {
                runAssertion(value, masterSeed);
                successfulRuns++;
            } catch (Throwable failure) {
                if (failure instanceof PropertyTimeoutException) {
                    throw (PropertyTimeoutException) failure; // should not occur here since
                    // edge cases don't use the timeoutPerExample path, but preserve the same
                    // guard as the random loop for consistency if this code is ever touched later
                }
                throw reportFalsification(
                    edgeBuffer, edgeSource.getRootSpans(), value, failure,
                    propertyId, masterSeed, successfulRuns + 1, invalidRuns);
            }
        }

        // -------------------------------------------------------------------
        // Step 2: Generate random examples
        // -------------------------------------------------------------------
        SplittableRandom masterPrng = new SplittableRandom(masterSeed);

        while (successfulRuns < examples) {
            if (invalidRuns > maxInvalidRuns) {
                // PropertyExecutionException, not AssertionError: this is "the property couldn't
                // be meaningfully checked" (a setup problem), not "the property was falsified" --
                // see this class's "Exception semantics".
                //
                // The cause is distinguished so the message doesn't blame filter(...) when every
                // invalid run was actually a buffer overrun (DataSource.MAX_BUFFER_SIZE, 8192
                // bytes) -- e.g. Generators.lists(gen, minSize, maxSize) with a minSize/maxSize
                // and per-element byte width whose product exceeds the buffer cap. That failure
                // mode has nothing to do with filtering and the old one-size-fits-all message was
                // actively misleading for it.
                int filterRejections = invalidRuns - overrunRuns - assumptionRejections;
                String reason;
                if (overrunRuns == invalidRuns) {
                    reason = String.format(
                        "Every invalid run exceeded the internal %d-byte generation buffer "
                        + "(DataSource.MAX_BUFFER_SIZE), not a filter rejection. This means the "
                        + "generator's minSize/maxSize (or nesting depth) require more bytes per "
                        + "example than the buffer allows -- e.g. Generators.lists(gen, minSize, maxSize) "
                        + "where minSize elements alone need more than %d bytes, since the mandatory "
                        + "prefix cannot stop early. Reduce the bounds, or for genuinely large/bulk "
                        + "collections write a custom Generator that draws a small seed and fills the "
                        + "collection with a local PRNG instead of drawing one span per element.",
                        DataSource.MAX_BUFFER_SIZE, DataSource.MAX_BUFFER_SIZE
                    );
                } else if (assumptionRejections == invalidRuns) {
                    reason = "This usually means Property.assuming(...) is too restrictive for the values this generator tends to produce.";
                } else if (overrunRuns == 0 && assumptionRejections == 0) {
                    reason = "This usually means a filter(x -> ...) predicate is too restrictive and rejecting most generated values.";
                } else {
                    reason = String.format(
                        "%d of those were buffer overruns (DataSource.MAX_BUFFER_SIZE, %d bytes), %d were "
                        + "filter rejections (filter(...)), and %d were assumption rejections (assuming(...)). "
                        + "Check bounds and predicates.",
                        overrunRuns, DataSource.MAX_BUFFER_SIZE, filterRejections, assumptionRejections
                    );
                }
                String budgetMessage = String.format(
                    "Property exhausted invalid budget. " +
                    "Too many invalid runs (%d) were attempted while only completing %d valid runs. " + reason,
                    invalidRuns, successfulRuns
                );
                if (overrunRuns == invalidRuns) {
                    throw new GenerationBudgetExceededException(budgetMessage);
                } else if (assumptionRejections == invalidRuns) {
                    throw new FilterExhaustedException(budgetMessage);
                } else if (overrunRuns == 0 && assumptionRejections == 0) {
                    throw new FilterExhaustedException(budgetMessage);
                } else {
                    throw new PropertyExecutionException(budgetMessage);
                }
            }

            long exampleSeed = masterPrng.nextLong();
            DataSource source = new DataSource(exampleSeed, generationBudgetBytes);

            T value = null;
            try {
                value = generator.generate(source);
                source.freeze();
            } catch (DataSourceOverrunException e) {
                invalidRuns++;
                overrunRuns++;
                continue;
            } catch (Throwable t) {
                // PropertyExecutionException, not AssertionError: a generator crashing is a bug in
                // the generator (or a custom Generator a caller wrote), not a finding about the
                // code under test -- see this class's "Exception semantics".
                throw new GeneratorCrashException("Generator crashed during data generation (seed=" + exampleSeed + ")", t);
            }

            if (source.getStatus() == DataSource.Status.INVALID) {
                invalidRuns++;
                continue;
            }

            if (assumption != null && !assumption.test(value)) {
                invalidRuns++;
                assumptionRejections++;
                continue;
            }

            // Valid generation, now apply the property assertion
            try {
                runAssertion(value, masterSeed);
                successfulRuns++;
            } catch (Throwable failure) {
                // A timeout is not a falsification — bypass shrinking entirely.
                // Sending a PropertyTimeoutException into the shrinker would re-invoke
                // the hanging assertion on every shrink attempt, re-hanging indefinitely.
                if (failure instanceof PropertyTimeoutException) {
                    throw (PropertyTimeoutException) failure;
                }

                // The property failed! Perform shrinking to find the minimal counterexample.
                byte[] rawBuffer = source.getRecordedBuffer();
                throw reportFalsification(
                    rawBuffer, source.getRootSpans(), value, failure,
                    propertyId, masterSeed, successfulRuns + 1, invalidRuns);
            }
        }

        long durationNanos = System.nanoTime() - startNanos;
        PropertyReporting.Sink sink = PropertyReporting.activeSink();
        if (sink != null) {
            sink.reportPass(propertyId, successfulRuns, durationNanos);
        } else {
            // Raw (non-JUnit) usage: no summary listener is collecting results, so print a single
            // flat PASS line directly, matching the same PASS/FAIL format the junit package uses.
            System.out.println(TerminalFormat.label(true) + "  " + propertyId + "   "
                + successfulRuns + " examples   " + TerminalFormat.formatSeconds(durationNanos));
        }
    }

    private void runAssertion(T value, long masterSeed) throws Throwable {
        if (timeoutPerExample == null) {
            assertion.accept(value);
        } else {
            ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("JHusk-timeout-worker");
                    return t;
                }
            });
            
            final T valueForTimeout = value;
            Future<?> future = executor.submit(() -> assertion.accept(valueForTimeout));
            try {
                future.get(timeoutPerExample.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new PropertyTimeoutException(String.format(
                    "Property example timed out after %s. Seed: %d. Value: %s",
                    timeoutPerExample, masterSeed, String.valueOf(value)), e);
            } catch (ExecutionException e) {
                throw e.getCause(); // Unwrap real assertion failures
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PropertyExecutionException("Thread was interrupted while waiting for property execution", e);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private byte[] buildEdgeCaseBuffer(byte fillByte) {
        byte[] buffer = new byte[generationBudgetBytes];
        java.util.Arrays.fill(buffer, fillByte);
        return buffer;
    }

    private AssertionError reportFalsification(
        byte[] rawBuffer, java.util.List<io.github.nikhilvirdi.jhusk.internal.Span> rootSpans,
        T unshrunkValue, Throwable failure, String propertyId,
        long masterSeed, int examplesRun, int invalidRuns) {

        ShrinkHarness<T> harness = new ShrinkHarness<>(generator, assertion, failure.getClass());
        byte[] shrunkBuffer = new Shrinker<>(harness).shrink(rawBuffer, rootSpans);

        // Save the minimal shrunk buffer to disk for future runs
        failureStorage.saveFailure(propertyId, shrunkBuffer);

        // Replay shrunk buffer to decode the minimal falsifying value
        DataSource shrunkSource = new DataSource(shrunkBuffer);
        T shrunkValue = generator.generate(shrunkSource);

        int shrinkAttempts = harness.getAttempts();

        PropertyReporting.Sink sink = PropertyReporting.activeSink();
        if (sink != null) {
            String cause = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            sink.reportFail(propertyId, shrinkAttempts, String.valueOf(shrunkValue), masterSeed, cause);
        }

        String report = String.format(
            "\n\n======================================================================\n" +
            "Property Falsified!\n" +
            "======================================================================\n\n" +
            "Falsifying (shrunk) value:\n" +
            "  %s\n\n" +
            "Original (unshrunk) value:\n" +
            "  %s\n\n" +
            "Reproduction:\n" +
            "  To reproduce this exact failure, run: check(%dL) (Seed: %dL)\n\n" +
            "Execution Statistics:\n" +
            "  Examples run: %d\n" +
            "  Invalid runs: %d\n" +
            "  Shrink attempts: %d\n\n" +
            "Original Exception:\n" +
            "  %s\n" +
            "======================================================================\n",
            String.valueOf(shrunkValue),
            String.valueOf(unshrunkValue),
            masterSeed,
            masterSeed,
            examplesRun,
            invalidRuns,
            shrinkAttempts,
            failure.toString()
        );

        return new AssertionError(report, failure);
    }

    /**
     * Resolves the property identity name for failure storage.
     * Uses explicit name if set, otherwise auto-detects from the stack trace.
     *
     * <p><b>Property Identity &amp; Auto-Detection Limitations:</b>
     * Deriving a fallback property identity from the caller's stack trace (class, method name, line number)
     * allows zero-config failure persistence for quick tests. However, it is brittle across refactors:
     * renaming the test method or moving lines changes the auto-detected identity and orphans stored failure files.
     *
     * <p>More severe than orphaning: if multiple properties are checked from the <em>same call
     * site</em> — most commonly, a shared helper method that wraps {@code Property.forAll(...).check(...)}
     * and is reused across several distinct properties — every one of them resolves to the exact
     * same auto-detected identity and silently overwrites the others' stored failures, since the
     * identity is derived from where {@code check()} was called, not which property it belongs to.
     * (The {@code @Property}/{@code @ForAll} JUnit annotations avoid this entirely: {@link
     * io.github.nikhilvirdi.jhusk.junit.PropertyExtension} always derives an explicit name from the
     * reflected test method itself rather than relying on this stack walk.)
     *
     * <p>Providing an explicit property name via {@code .named("my-property")} or
     * {@code Property.forAll("my-property", ...)} is the recommended practice for stable regression
     * testing, and the only way to avoid both failure modes when calling {@code check()} directly.
     */
    private String resolvePropertyId() {
        if (this.name != null && !this.name.isBlank()) {
            return this.name;
        }

        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!className.equals(Property.class.getName())
                    && !className.equals(Thread.class.getName())
                    && !className.startsWith("java.lang.")) {
                return className + "." + frame.getMethodName() + "_L" + frame.getLineNumber();
            }
        }
        return "unnamed_property";
    }
}
