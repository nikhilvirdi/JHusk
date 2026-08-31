package io.github.nikhilvirdi.jhusk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records a contiguous byte range within a {@link DataSource} buffer, forming a tree
 * structure that maps semantic generator boundaries onto the flat byte stream.
 *
 * <p><b>Why Spans Exist in JHusk Architecture:</b><br>
 * A flat byte buffer has no innate structure. In JHusk's property-based testing workflow,
 * the shrinker needs to reduce failing test cases by deleting specific components
 * (such as "one element from a generated list" or "a composite object field"). Without recorded
 * structure, shrinking would be limited to deleting random raw byte ranges, which almost always
 * produces invalid or unparseable byte streams. Spans capture {@code [start, end)} byte boundaries
 * around every generator's execution, forming a nested tree that lets the shrinker delete
 * semantically coherent byte regions.
 *
 * <p><b>Nesting Discipline:</b><br>
 * Spans strictly nest: a span opened while another span is open becomes a child of the enclosing
 * parent span, never an overlapping sibling.
 *
 * <p><b>Label Design Choice:</b><br>
 * The label is nullable ({@code String}). Generators typically supply descriptive labels (e.g.
 * {@code "int"}, {@code "list-element"}) to aid debugging and targeted shrinking, but internal or
 * lightweight draws may omit them. Using a nullable string avoids {@code java.util.Optional}
 * allocation overhead on hot generation paths while conforming to Java standards.
 */
public final class Span {

    /** The starting byte offset in the buffer (inclusive) where this span was opened. */
    private final int start;

    /**
     * The ending byte offset in the buffer (exclusive) where this span was closed.
     * Initialized to {@code -1} as a sentinel value while the span is still open.
     */
    private int end;

    /** Optional descriptive label for debugging and shrinking context (may be {@code null}). */
    private final String label;

    /** Ordered list of nested child spans created within the duration of this span. */
    private final List<Span> children;

    /**
     * Creates a new open span starting at the specified byte cursor position.
     *
     * @param start byte offset where this span begins (inclusive)
     * @param label optional descriptive label identifying the generator or structure (may be {@code null})
     */
    Span(int start, String label) {
        this.start = start;
        // Sentinel value -1 indicates that this span is currently open and has not yet been closed
        this.end = -1;
        this.label = label;
        this.children = new ArrayList<>();
    }

    /**
     * Closes this span by setting its ending byte offset (exclusive).
     *
     * @param end byte offset where this span ends (exclusive), captured when {@code endSpan()} is called
     * @throws IllegalStateException if this span has already been closed (prevents double-closing bugs)
     */
    void close(int end) {
        if (this.end != -1) {
            // A non-sentinel end value indicates the span was already closed previously
            throw new IllegalStateException("Span '" + label + "' is already closed");
        }
        // Set the exclusive ending byte offset
        this.end = end;
    }

    /**
     * Adds a completed, closed child span to this span's list of nested children.
     *
     * @param child the child span to attach to this parent span
     */
    void addChild(Span child) {
        children.add(child);
    }

    /**
     * Package-private check to determine whether this span is still open.
     *
     * @return {@code true} if the span has not been closed yet; {@code false} otherwise
     */
    boolean isOpen() {
        // -1 sentinel value confirms the span is still open
        return end == -1;
    }

    /**
     * Returns the byte offset in the buffer where this span begins (inclusive).
     *
     * @return starting byte index
     */
    public int getStart() {
        return start;
    }

    /**
     * Returns the byte offset in the buffer where this span ends (exclusive).
     *
     * @return ending byte index, or {@code -1} if the span is still open
     */
    public int getEnd() {
        return end;
    }

    /**
     * Returns the optional descriptive label for this span.
     *
     * @return the string label, or {@code null} if no label was provided
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns an unmodifiable view of this span's nested child spans.
     *
     * @return unmodifiable list of child spans
     */
    public List<Span> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * Calculates the total number of bytes encompassed by this span ({@code end - start}).
     *
     * @return number of bytes in this span's range
     * @throws IllegalStateException if queried while the span is still open (end offset not yet determined)
     */
    public int size() {
        if (end == -1) {
            // Cannot compute size for an open span because its exclusive end position is not yet known
            throw new IllegalStateException("Cannot query size of an open span");
        }
        // Exclusive end index minus inclusive start index equals exact total byte length
        return end - start;
    }
}
