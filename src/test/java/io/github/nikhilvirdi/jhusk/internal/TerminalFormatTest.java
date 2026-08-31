package io.github.nikhilvirdi.jhusk.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TerminalFormat color precedence and label/duration formatting")
class TerminalFormatTest {

    // colorEnabled(flag, noColorEnv, consolePresent) is exercised directly with every input
    // supplied explicitly, rather than through the real System.getenv("NO_COLOR")/System.console()
    // state -- NO_COLOR can't be set at runtime without reflection hacks, and System.console() is
    // reliably null under Surefire's forked JVM (no attached console), making the real end-to-end
    // path untestable for the "auto-detect true" branch specifically.

    @Test
    @DisplayName("explicit -Djhusk.color=always wins over NO_COLOR being set")
    void explicitAlwaysBeatsNoColor() {
        assertTrue(TerminalFormat.colorEnabled("always", "1", false),
            "always must force color on even with NO_COLOR set and no console");
    }

    @Test
    @DisplayName("explicit -Djhusk.color=never wins over console being present")
    void explicitNeverBeatsConsolePresent() {
        assertFalse(TerminalFormat.colorEnabled("never", null, true),
            "never must force color off even with a console attached and NO_COLOR unset");
    }

    @Test
    @DisplayName("NO_COLOR set wins over a present console when no explicit flag overrides it")
    void noColorBeatsConsolePresent() {
        assertFalse(TerminalFormat.colorEnabled("auto", "1", true),
            "NO_COLOR must suppress color even when a console is attached, absent an explicit flag");
    }

    @Test
    @DisplayName("auto with no NO_COLOR falls through to console detection")
    void autoFallsThroughToConsoleDetection() {
        assertTrue(TerminalFormat.colorEnabled("auto", null, true),
            "auto with NO_COLOR unset and a console present must enable color");
        assertFalse(TerminalFormat.colorEnabled("auto", null, false),
            "auto with NO_COLOR unset and no console must disable color");
    }

    @Test
    @DisplayName("label() text is PASS/FAIL regardless of color, with color codes only wrapping it when enabled")
    void labelTextAndColorWrapping() {
        System.setProperty("jhusk.color", "never");
        try {
            assertEquals("PASS", TerminalFormat.label(true));
            assertEquals("FAIL", TerminalFormat.label(false));
        } finally {
            System.clearProperty("jhusk.color");
        }

        System.setProperty("jhusk.color", "always");
        try {
            assertTrue(TerminalFormat.label(true).contains("PASS"));
            assertTrue(TerminalFormat.label(true).startsWith("["), "Colored PASS must start with an ANSI escape");
            assertTrue(TerminalFormat.label(false).contains("FAIL"));
        } finally {
            System.clearProperty("jhusk.color");
        }
    }

    @Test
    @DisplayName("formatSeconds renders nanoseconds as two-decimal seconds")
    void formatSecondsRendersTwoDecimals() {
        assertEquals("0.16s", TerminalFormat.formatSeconds(160_000_000L));
        assertEquals("0.00s", TerminalFormat.formatSeconds(0L));
        assertEquals("13.50s", TerminalFormat.formatSeconds(13_500_000_000L));
    }
}
