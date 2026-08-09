package io.github.nikhilvirdi.jhusk;

/**
 * Thrown by {@link Property#check()}/{@link Property#check(long)} when the invalid-run budget
 * is exhausted and every invalid run was a {@code DataSourceOverrunException} — i.e. the
 * generator's size/depth requirements exceed the internal byte-buffer cap
 * ({@code DataSource.MAX_BUFFER_SIZE}), not a filter predicate rejection.
 *
 * <p>This is a specific subtype of {@link PropertyExecutionException} and is still catchable
 * by any {@code catch (PropertyExecutionException e)} or {@code catch (RuntimeException e)}
 * block. It allows callers to distinguish a buffer-budget failure programmatically,
 * without parsing the message string.
 *
 * @see PropertyExecutionException
 * @see FilterExhaustedException
 * @see Property
 */
public class GenerationBudgetExceededException extends PropertyExecutionException {

    /**
     * Constructs a new {@code GenerationBudgetExceededException} with the specified detail message.
     *
     * @param message detailed message explaining what prevented the property from being checked
     */
    public GenerationBudgetExceededException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code GenerationBudgetExceededException} with the specified detail message
     * and cause.
     *
     * @param message detailed message explaining what prevented the property from being checked
     * @param cause the underlying exception
     */
    public GenerationBudgetExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
