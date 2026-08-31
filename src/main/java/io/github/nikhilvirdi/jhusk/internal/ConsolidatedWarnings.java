package io.github.nikhilvirdi.jhusk.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Not a compatibility promise -- see this package's javadoc.
 *
 * <p>Collapses repeated {@code FailureStorage} warnings into one line per (operation, property)
 * pair reporting how many times it fired this run, instead of printing the full underlying error
 * (which typically includes a local temp file path) on every single occurrence. The warnings
 * themselves are never suppressed -- {@link #flush()} still prints one line per distinct pair
 * that occurred, just once, with a count.
 *
 * @since 1.2.0
 */
public final class ConsolidatedWarnings {

    private ConsolidatedWarnings() {
    }

    private static final ConcurrentHashMap<String, Counted> COUNTS = new ConcurrentHashMap<>();

    static {
        // Fallback for raw (non-JUnit) usage: the junit package's summary listener flushes
        // explicitly at test-plan-finish, but a plain main() has no such hook.
        Runtime.getRuntime().addShutdownHook(new Thread(ConsolidatedWarnings::flush, "JHusk-warning-flush"));
    }

    private static final class Counted {
        final AtomicInteger count = new AtomicInteger();
        volatile String lastMessage;
    }

    /**
     * Records one warning occurrence. Does not print anything itself -- see {@link #flush()}.
     *
     * @param operation short verb describing what failed, e.g. {@code "save"}, {@code "load"}, {@code "prune"}
     * @param propertyId the property identity the warning is about
     * @param message the underlying error's message
     */
    public static void record(String operation, String propertyId, String message) {
        Counted counted = COUNTS.computeIfAbsent(operation + ":" + propertyId, key -> new Counted());
        counted.count.incrementAndGet();
        counted.lastMessage = message;
    }

    /**
     * Prints one consolidated line per distinct (operation, property) pair warned since the last
     * flush, then clears the recorded counts. Safe to call more than once; a flush with nothing
     * recorded prints nothing.
     */
    public static void flush() {
        COUNTS.forEach((key, counted) -> {
            int separator = key.indexOf(':');
            String operation = key.substring(0, separator);
            String propertyId = key.substring(separator + 1);
            int times = counted.count.get();
            System.err.println("[JHusk Warning] Failed to " + operation + " failure buffer for property '"
                + propertyId + "' (" + times + (times == 1 ? " time" : " times") + " this run); most recent error: "
                + counted.lastMessage);
        });
        COUNTS.clear();
    }
}
