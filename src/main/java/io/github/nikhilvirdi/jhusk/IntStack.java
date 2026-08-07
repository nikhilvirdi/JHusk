package io.github.nikhilvirdi.jhusk;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal integer stack implementation backed by an {@link ArrayList}.
 * <p>
 * This class serves as a simple data structure under test in JHusk's test suite,
 * demonstrating stateful stack operations like push, pop, size, and emptiness checks.
 */
public class IntStack {

    /** Internal list storing stack elements in LIFO (last-in, first-out) order. */
    private final List<Integer> data = new ArrayList<>();

    /**
     * Pushes an integer value onto the top of the stack.
     *
     * @param value the integer value to push onto the stack
     */
    public void push(int value) {
        data.add(value);
    }

    /**
     * Removes and returns the integer value from the top of the stack.
     *
     * @return the integer value at the top of the stack
     * @throws IllegalStateException if the stack is empty (pop operation cannot succeed without elements)
     */
    public int pop() {
        if (data.isEmpty()) {
            // An empty stack has no elements to remove; throw IllegalStateException to signal invalid state
            throw new IllegalStateException("stack is empty");
        }
        // Remove and return the element at the last index (top of stack)
        return data.remove(data.size() - 1);
    }

    /**
     * Checks whether the stack contains no elements.
     *
     * @return {@code true} if the stack is empty; {@code false} otherwise
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * Returns the total number of integer elements currently on the stack.
     *
     * @return the current number of elements on the stack
     */
    public int size() {
        return data.size();
    }
}
