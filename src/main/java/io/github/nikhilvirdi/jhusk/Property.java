package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.DataSourceOverrunException;
import io.github.nikhilvirdi.jhusk.internal.FailureStorage;
import io.github.nikhilvirdi.jhusk.internal.ShrinkHarness;
import io.github.nikhilvirdi.jhusk.internal.Shrinker;

import java.nio.file.Path;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Consumer;

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
 */
public final class Property<T> {

    private final Generator<T> generator;
    private final Consumer<T> assertion;
    
    private String name;
    private int examples = 100;
    private int maxInvalidRuns = 1000;
    private FailureStorage failureStorage = new FailureStorage();

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
     * Recommended over auto-detection to survive code refactoring.
     */
    public Property<T> named(String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the storage directory for failure persistence (primarily for testing).
     */
    public Property<T> withStorageDir(Path storageDir) {
        this.failureStorage = new FailureStorage(storageDir);
        return this;
    }

    /**
     * Sets the FailureStorage instance (primarily for testing).
     */
    public Property<T> withFailureStorage(FailureStorage storage) {
        this.failureStorage = storage;
        return this;
    }

    /**
     * Sets the number of successful examples required for the property to pass.
     * Default is 100.
     */
    public Property<T> examples(int examples) {
        this.examples = examples;
        return this;
    }

    /**
     * Checks the property using a randomly chosen master seed.
     */
    public void check() {
        check(new SplittableRandom().nextLong());
    }

    /**
     * Checks the property using the specified master seed.
     * 
     * <p>Replays stored failures from {@code .jhusk/} first before generating random examples.
     * If a stored failure still fails, it reports immediately without generating new examples.
     * If a stored failure passes, it is automatically pruned from disk.
     * 
     * @param masterSeed the master seed
     * @throws AssertionError if a falsifying example is found, or if the invalid budget is exhausted
     */
    public void check(long masterSeed) {
        String propertyId = resolvePropertyId();

        // -------------------------------------------------------------------
        // Step 1: Replay stored failure from .jhusk/ if present
        // -------------------------------------------------------------------
        Optional<byte[]> stored = failureStorage.loadFailure(propertyId);
        if (stored.isPresent()) {
            byte[] storedBuffer = stored.get();
            DataSource storedSource = new DataSource(storedBuffer);
            T storedValue = null;
            boolean stillFails = false;
            Throwable storedFailure = null;

            try {
                storedValue = generator.generate(storedSource);
                storedSource.freeze();
                if (storedSource.getStatus() != DataSource.Status.INVALID) {
                    assertion.accept(storedValue);
                }
            } catch (Throwable failure) {
                stillFails = true;
                storedFailure = failure;
            }

            if (stillFails) {
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
        // Step 2: Generate random examples
        // -------------------------------------------------------------------
        SplittableRandom masterPrng = new SplittableRandom(masterSeed);
        int successfulRuns = 0;
        int invalidRuns = 0;
        
        while (successfulRuns < examples) {
            if (invalidRuns > maxInvalidRuns) {
                throw new AssertionError(String.format(
                    "Property exhausted invalid budget. " +
                    "Too many invalid runs (%d) were attempted while only completing %d valid runs. " +
                    "This usually means a filter(x -> ...) predicate is too restrictive and rejecting most generated values.",
                    invalidRuns, successfulRuns
                ));
            }

            long exampleSeed = masterPrng.nextLong();
            DataSource source = new DataSource(exampleSeed);
            
            T value = null;
            try {
                value = generator.generate(source);
                source.freeze();
            } catch (DataSourceOverrunException e) {
                invalidRuns++;
                continue;
            } catch (Throwable t) {
                throw new AssertionError("Generator crashed during data generation (seed=" + exampleSeed + ")", t);
            }

            if (source.getStatus() == DataSource.Status.INVALID) {
                invalidRuns++;
                continue;
            }

            // Valid generation, now apply the property assertion
            try {
                assertion.accept(value);
                successfulRuns++;
            } catch (Throwable failure) {
                // The property failed! Perform shrinking to find the minimal counterexample.
                byte[] rawBuffer = source.getRecordedBuffer();

                ShrinkHarness<T> harness = new ShrinkHarness<>(generator, assertion, failure.getClass());
                byte[] shrunkBuffer = new Shrinker<>(harness).shrink(rawBuffer, source.getRootSpans());

                // Save the minimal shrunk buffer to disk for future runs
                failureStorage.saveFailure(propertyId, shrunkBuffer);

                // Replay shrunk buffer to decode the minimal falsifying value
                DataSource shrunkSource = new DataSource(shrunkBuffer);
                T shrunkValue = generator.generate(shrunkSource);

                int examplesRun = successfulRuns + 1;
                int shrinkAttempts = harness.getAttempts();

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
                    String.valueOf(value),
                    masterSeed,
                    masterSeed,
                    examplesRun,
                    invalidRuns,
                    shrinkAttempts,
                    failure.toString()
                );

                AssertionError error = new AssertionError(report, failure);
                throw error;
            }
        }
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
     * <p>Providing an explicit property name via {@code .named("my-property")} or
     * {@code Property.forAll("my-property", ...)} is the recommended practice for stable regression testing.
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
