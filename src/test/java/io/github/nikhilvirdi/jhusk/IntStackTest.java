package io.github.nikhilvirdi.jhusk;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Unit test suite demonstrating JUnit 5 capabilities on {@link IntStack}.
 * <p>
 * Demonstrates:
 * <ul>
 *   <li>{@code @Test} with assertions ({@code assertEquals}, {@code assertTrue})</li>
 *   <li>{@code assertThrows} for exception testing on empty pop</li>
 *   <li>{@code @BeforeEach} and {@code @AfterEach} lifecycle hooks with observable side effects</li>
 *   <li>{@code @DisplayName} annotations for human-readable test output</li>
 *   <li>{@code @Nested} test classes for pre-populated state contexts</li>
 *   <li>{@code @Disabled} annotations with reason strings</li>
 *   <li>{@code @ParameterizedTest} using {@code @ValueSource} and {@code @MethodSource}</li>
 * </ul>
 */
@DisplayName("IntStack behavior")
class IntStackTest {

    /** Fresh stack instance created before each test method execution. */
    private IntStack stack;

    /** Executes before each test to instantiate a clean IntStack. */
    @BeforeEach
    void setUp() {
        stack = new IntStack();
        System.out.println("BeforeEach: fresh stack created");
    }

    /** Executes after each test to demonstrate tearDown lifecycle execution. */
    @AfterEach
    void tearDown() {
        System.out.println("AfterEach: stack discarded");
    }

    /** Verifies that a newly constructed IntStack has size 0 and returns true for isEmpty(). */
    @Test
    @DisplayName("new stack is empty")
    void newStackIsEmpty() {
        assertTrue(stack.isEmpty());
    }

    /** Verifies that calling pop() on an empty stack throws an IllegalStateException. */
    @Test
    void popOnEmptyThrows() {
        assertThrows(IllegalStateException.class, () -> stack.pop());
    }

    /** Placeholder disabled test demonstrating @Disabled annotation with explanation string. */
    @Disabled("pushing null isn't handled yet — revisit after generics support")
    @Test
    void pushNullShouldThrow() {
        // placeholder test
    }

    /** Nested context testing behavior when elements have already been pushed onto the stack. */
    @Nested
    @DisplayName("when elements are pushed")
    class WhenPushed {
        /** Nested @BeforeEach runs after outer @BeforeEach, pushing elements 1 and 2. */
        @BeforeEach
        void pushTwo() {
            stack.push(1);
            stack.push(2);
        }

        /** Verifies size is 2 after two pushes. */
        @Test
        void sizeReflectsPushes() {
            assertEquals(2, stack.size());
        }

        /** Verifies LIFO order: last pushed value (2) is popped first. */
        @Test
        void popReturnsLastPushed() {
            assertEquals(2, stack.pop());
        }
    }

    /** Parameterized test verifying push-then-pop round trip across multiple integer inputs. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 100, -5, 0})
    void pushThenPopReturnsSameValue(int value) {
        stack.push(value);
        assertEquals(value, stack.pop());
    }

    /** Parameterized test supplying sequences of integers and asserting expected final stack size. */
    @ParameterizedTest
    @MethodSource("pushSequences")
    void sizeMatchesNumberOfPushes(List<Integer> values, int expectedSize) {
        values.forEach(stack::push);
        assertEquals(expectedSize, stack.size());
    }

    /** Factory method providing test arguments for sizeMatchesNumberOfPushes. */
    static Stream<Arguments> pushSequences() {
        return Stream.of(
                arguments(List.of(), 0),
                arguments(List.of(1), 1),
                arguments(List.of(1, 2, 3), 3)
        );
    }
}
