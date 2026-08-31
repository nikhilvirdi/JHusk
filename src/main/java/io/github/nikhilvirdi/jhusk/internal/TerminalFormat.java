package io.github.nikhilvirdi.jhusk.internal;

import java.util.Locale;

/**
 * Not a compatibility promise -- see this package's javadoc.
 *
 * <p>Shared PASS/FAIL label and color handling for the {@code junit} package's terminal
 * reporting, and for the raw (non-JUnit) {@code Property.check()} fallback line, so both paths
 * format output identically.
 *
 * <p><b>Color precedence:</b> {@code -Djhusk.color=always|never|auto} (explicit) beats the
 * {@code NO_COLOR} environment variable (see https://no-color.org), which beats auto-detection
 * via {@link System#console()}. If color is enabled, only the {@code PASS}/{@code FAIL} label
 * itself is tinted (green/red) -- nothing else changes color.
 *
 * @since 1.2.0
 */
public final class TerminalFormat {

    private TerminalFormat() {
    }

    private static final String ANSI_GREEN = "[32m";
    private static final String ANSI_RED = "[31m";
    private static final String ANSI_RESET = "[0m";

    /**
     * Resolves whether PASS/FAIL labels should be color-tinted, reading the real
     * {@code jhusk.color} system property, {@code NO_COLOR} environment variable, and
     * {@link System#console()} state.
     *
     * @return {@code true} if labels should be tinted
     */
    public static boolean colorEnabled() {
        return colorEnabled(System.getProperty("jhusk.color", "auto"), System.getenv("NO_COLOR"),
            System.console() != null);
    }

    /**
     * Same precedence as {@link #colorEnabled()}, but with every input supplied explicitly --
     * package-visible so tests can exercise the precedence order deterministically without
     * touching real environment variables or console attachment state.
     */
    static boolean colorEnabled(String colorFlag, String noColorEnv, boolean consolePresent) {
        return switch (colorFlag) {
            case "always" -> true;
            case "never" -> false;
            default -> noColorEnv == null && consolePresent;
        };
    }

    /**
     * Returns the plain or color-tinted {@code PASS}/{@code FAIL} text label.
     *
     * @param passed whether to return the passing or failing label
     * @return {@code "PASS"}/{@code "FAIL"}, ANSI-tinted green/red if color is enabled
     */
    public static String label(boolean passed) {
        if (!colorEnabled()) {
            return passed ? "PASS" : "FAIL";
        }
        return passed ? ANSI_GREEN + "PASS" + ANSI_RESET : ANSI_RED + "FAIL" + ANSI_RESET;
    }

    /**
     * Formats a duration in nanoseconds as seconds with two decimal places, e.g. {@code "0.16s"}.
     *
     * @param durationNanos the duration, in nanoseconds
     * @return the formatted duration string
     */
    public static String formatSeconds(long durationNanos) {
        return String.format(Locale.ROOT, "%.2fs", durationNanos / 1_000_000_000.0);
    }
}
