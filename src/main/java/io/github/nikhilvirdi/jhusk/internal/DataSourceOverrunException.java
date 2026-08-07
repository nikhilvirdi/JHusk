package io.github.nikhilvirdi.jhusk.internal;

/**
 * Exception thrown when a {@link DataSource} draw operation would exceed the maximum
 * allowed buffer capacity ({@link DataSource#MAX_BUFFER_SIZE}).
 * <p>
 * Why this exception exists:
 * In property-based testing (modeled after Hypothesis conventions), byte generators are capped
 * at a fixed maximum buffer size (8192 bytes) to prevent infinite or runaway byte requests from
 * consuming unlimited memory. Throwing an explicit {@code DataSourceOverrunException} ensures
 * that generation overruns are detected cleanly rather than causing silent data truncation or memory crashes.
 */
public class DataSourceOverrunException extends RuntimeException {

    /**
     * Constructs a new {@code DataSourceOverrunException} with the specified detail message.
     *
     * @param message detailed message explaining why the buffer capacity was exceeded
     */
    public DataSourceOverrunException(String message) {
        super(message);
    }
}
