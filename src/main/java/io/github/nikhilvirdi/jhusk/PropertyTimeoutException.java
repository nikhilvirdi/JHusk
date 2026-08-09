package io.github.nikhilvirdi.jhusk;

/**
 * Thrown by {@link Property#check()} when a property execution times out on a single example.
 */
public class PropertyTimeoutException extends PropertyExecutionException {

    /**
     * Constructs a new {@code PropertyTimeoutException} with the specified detail message.
     *
     * @param message detailed message explaining what timed out
     */
    public PropertyTimeoutException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code PropertyTimeoutException} with the specified detail message and cause.
     *
     * @param message detailed message explaining what timed out
     * @param cause the underlying exception
     */
    public PropertyTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
