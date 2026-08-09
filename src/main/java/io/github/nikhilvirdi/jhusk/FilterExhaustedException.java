package io.github.nikhilvirdi.jhusk;

/**
 * Thrown by {@link Property#check()}/{@link Property#check(long)} when the invalid-run budget
 * is exhausted and the dominant cause was explicit filter/assume rejections
 * ({@code source.getStatus() == INVALID}), not buffer overruns.
 *
 * <p>This is a specific subtype of {@link PropertyExecutionException} and is still catchable
 * by any {@code catch (PropertyExecutionException e)} or {@code catch (RuntimeException e)}
 * block. It allows callers to distinguish a filter-exhaustion failure programmatically,
 * without parsing the message string.
 *
 * @see PropertyExecutionException
 * @see GenerationBudgetExceededException
 * @see Property
 */
public class FilterExhaustedException extends PropertyExecutionException {

    /**
     * Constructs a new {@code FilterExhaustedException} with the specified detail message.
     *
     * @param message detailed message explaining what prevented the property from being checked
     */
    public FilterExhaustedException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code FilterExhaustedException} with the specified detail message and cause.
     *
     * @param message detailed message explaining what prevented the property from being checked
     * @param cause the underlying exception
     */
    public FilterExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
