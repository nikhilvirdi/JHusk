package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import io.github.nikhilvirdi.jhusk.internal.DataSourceOverrunException;

import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * A property runner that binds a {@link Generator} to a test assertion,
 * executing the property multiple times with random byte sequences.
 * 
 * <p>It enforces an invalid budget to ensure properties don't silently pass
 * by rejecting too many examples (e.g. via aggressive filtering).
 * 
 * <p>Currently, on failure, it simply reports the raw unshrunk counterexample.
 * Future phases will add byte-level shrinking to this reporting flow.
 */
public final class Property<T> {

    private final Generator<T> generator;
    private final Consumer<T> assertion;
    
    private int examples = 100;
    
    // Budget: maximum number of invalid runs before aborting
    // We allow up to 10 invalid runs for every 1 required example, capped or flat threshold.
    // For now, a flat threshold of 1000 invalid runs total is reasonable for 100 examples.
    private int maxInvalidRuns = 1000;

    private Property(Generator<T> generator, Consumer<T> assertion) {
        this.generator = generator;
        this.assertion = assertion;
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
     * The master seed deterministically derives individual seeds for each example.
     * 
     * @param masterSeed the master seed
     * @throws AssertionError if a falsifying example is found, or if the invalid budget is exhausted
     */
    public void check(long masterSeed) {
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
                // Example was too large and exceeded buffer capacity; count as invalid and try again
                invalidRuns++;
                continue;
            } catch (Throwable t) {
                // An exception during generation itself (not a test failure, but a bug in the generator or the property setup)
                // We'll treat this as a direct failure because generators shouldn't crash.
                throw new AssertionError("Generator crashed during data generation (seed=" + exampleSeed + ")", t);
            }

            if (source.getStatus() == DataSource.Status.INVALID) {
                invalidRuns++;
                continue;
            }

            // Valid generation, now apply the property assertion
            try {
                assertion.accept(value);
                // If it returns normally, the property passed for this example
                successfulRuns++;
            } catch (Throwable failure) {
                // The property failed! 
                // Report immediately with the raw unshrunk output.
                byte[] buffer = source.getRecordedBuffer();
                
                String report = String.format(
                    "\n\n======================================================================\n" +
                    "Falsifying example found (Unshrunk):\n" +
                    "Seed: %dL\n" +
                    "Buffer size: %d bytes\n" +
                    "Value: %s\n\n" +
                    "Exception: %s\n" +
                    "======================================================================\n",
                    exampleSeed, buffer.length, String.valueOf(value), failure.toString()
                );
                
                AssertionError error = new AssertionError(report);
                error.initCause(failure);
                throw error;
            }
        }
        
        // If we get here, all N examples passed successfully
        // System.out.println("Property passed: " + successfulRuns + " examples run, " + invalidRuns + " invalid runs.");
    }
}
