package io.github.nikhilvirdi.jhusk;

/**
 * Thrown by {@link Property#check()}/{@link Property#check(long)} when the {@link Generator}
 * itself throws an unexpected exception while producing an example — i.e. a bug in a custom
 * {@link Generator} implementation (or in a {@code map()}/{@code flatMap()}/{@code combine()}
 * function), not a finding about the code under test.
 *
 * <p>This is a specific subtype of {@link PropertyExecutionException} and is still catchable
 * by any {@code catch (PropertyExecutionException e)} or {@code catch (RuntimeException e)}
 * block. It allows callers to distinguish a generator-crash from budget exhaustion
 * programmatically, without parsing the message string. The original exception thrown by
 * the generator is always preserved as the {@link #getCause() cause}.
 *
 * @see PropertyExecutionException
 * @see Property
 */
public class GeneratorCrashException extends PropertyExecutionException {

    /**
     * Constructs a new {@code GeneratorCrashException} with the specified detail message.
     *
     * @param message detailed message identifying which seed triggered the crash
     */
    public GeneratorCrashException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code GeneratorCrashException} with the specified detail message and cause.
     *
     * @param message detailed message identifying which seed triggered the crash
     * @param cause the exception thrown by the generator
     */
    public GeneratorCrashException(String message, Throwable cause) {
        super(message, cause);
    }
}
