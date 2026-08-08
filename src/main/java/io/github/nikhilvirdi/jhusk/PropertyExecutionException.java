package io.github.nikhilvirdi.jhusk;

/**
 * Thrown by {@link Property#check()}/{@link Property#check(long)} when a property
 * <em>could not be meaningfully checked at all</em> — as opposed to being checked and found
 * falsified.
 *
 * <p>Two scenarios throw this:
 * <ul>
 *   <li>The invalid-run budget was exhausted (see {@link Property#maxInvalidRuns(int)}) — the
 *       generator rejected too many draws (via {@code filter(...)} or a buffer overrun) to reach
 *       the required number of successful examples.</li>
 *   <li>The generator itself threw an unexpected exception while producing an example — a bug in
 *       a custom {@link Generator}, not a finding about the code under test.</li>
 * </ul>
 *
 * <p>See {@link Property}'s class Javadoc ("Exception semantics") for why this is a {@code
 * RuntimeException} rather than an {@code AssertionError}, unlike a genuine property falsification.
 *
 * @see Property
 */
public class PropertyExecutionException extends RuntimeException {

    /**
     * Constructs a new {@code PropertyExecutionException} with the specified detail message.
     *
     * @param message detailed message explaining what prevented the property from being checked
     */
    public PropertyExecutionException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code PropertyExecutionException} with the specified detail message and cause.
     *
     * @param message detailed message explaining what prevented the property from being checked
     * @param cause the underlying exception that prevented the property from being checked
     */
    public PropertyExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
