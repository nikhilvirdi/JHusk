package io.github.nikhilvirdi.jhusk.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConsolidatedWarnings collapses repeated FailureStorage warnings by count")
class ConsolidatedWarningsTest {

    @Test
    @DisplayName("Multiple occurrences for the same operation+property collapse into exactly one line reporting the count")
    void multipleOccurrencesCollapseIntoOneLine() {
        ConsolidatedWarnings.record("save", "my.property", "disk full");
        ConsolidatedWarnings.record("save", "my.property", "disk full again");
        ConsolidatedWarnings.record("save", "my.property", "disk full a third time");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured));
            ConsolidatedWarnings.flush();
        } finally {
            System.setErr(originalErr);
        }

        String[] lines = captured.toString().split("\\R");
        long warningLines = java.util.Arrays.stream(lines)
            .filter(line -> line.contains("my.property"))
            .count();
        assertEquals(1, warningLines, "Three occurrences must collapse into exactly one line, not one per occurrence");
        assertTrue(captured.toString().contains("3 times"), "The consolidated line must report the count");
        assertTrue(captured.toString().contains("disk full a third time"),
            "The consolidated line should still surface the most recent error for diagnosability");
    }

    @Test
    @DisplayName("Distinct properties produce distinct consolidated lines")
    void distinctPropertiesProduceDistinctLines() {
        ConsolidatedWarnings.record("load", "property-a", "boom-a");
        ConsolidatedWarnings.record("load", "property-b", "boom-b");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured));
            ConsolidatedWarnings.flush();
        } finally {
            System.setErr(originalErr);
        }

        String output = captured.toString();
        assertTrue(output.contains("property-a"));
        assertTrue(output.contains("property-b"));
    }

    @Test
    @DisplayName("flush() clears recorded state so a second flush with no new warnings prints nothing")
    void flushIsIdempotent() {
        ConsolidatedWarnings.record("prune", "once-only", "gone");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream firstCapture = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(firstCapture));
            ConsolidatedWarnings.flush();
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(firstCapture.toString().contains("once-only"));

        ByteArrayOutputStream secondCapture = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(secondCapture));
            ConsolidatedWarnings.flush();
        } finally {
            System.setErr(originalErr);
        }
        assertEquals("", secondCapture.toString(), "A flush with nothing newly recorded must print nothing");
    }
}
