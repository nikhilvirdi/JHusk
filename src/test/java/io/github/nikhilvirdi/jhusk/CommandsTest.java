package io.github.nikhilvirdi.jhusk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Commands} / {@link Command} (stateful/model-based testing),
 * exercised against {@link IntStack} as the real system under test and a
 * {@code List<Integer>} as the model.
 */
@DisplayName("Commands (stateful/model-based testing)")
class CommandsTest {

    private static final Path FAILURE_DIR = Path.of(FailureStorage.DEFAULT_FAILURE_DIR_NAME);
    private static final String FAILURE_PREFIX = "io.github.nikhilvirdi.jhusk.CommandsTest";

    @BeforeEach
    @AfterEach
    void clearStoredFailures() throws IOException {
        if (Files.isDirectory(FAILURE_DIR)) {
            try (var files = Files.list(FAILURE_DIR)) {
                files.filter(file -> file.getFileName().toString().startsWith(FAILURE_PREFIX))
                     .forEach(file -> {
                         try {
                             Files.deleteIfExists(file);
                         } catch (IOException e) {
                             throw new UncheckedIOException(e);
                         }
                     });
            }
        }
    }

    /** Pushes {@code value}; always applicable. */
    private static final class PushCommand implements Command<List<Integer>, IntStack> {
        private final int value;

        PushCommand(int value) {
            this.value = value;
        }

        @Override
        public boolean precondition(List<Integer> model) {
            return true;
        }

        @Override
        public List<Integer> nextModel(List<Integer> model) {
            List<Integer> copy = new ArrayList<>(model);
            copy.add(value);
            return copy;
        }

        @Override
        public void runAndVerify(List<Integer> modelBefore, IntStack real) {
            real.push(value);
            assertEquals(modelBefore.size() + 1, real.size());
        }
    }

    /** Pops the top value; only applicable when the model is non-empty. */
    private static final class PopCommand implements Command<List<Integer>, IntStack> {
        @Override
        public boolean precondition(List<Integer> model) {
            return !model.isEmpty();
        }

        @Override
        public List<Integer> nextModel(List<Integer> model) {
            List<Integer> copy = new ArrayList<>(model);
            copy.remove(copy.size() - 1);
            return copy;
        }

        @Override
        public void runAndVerify(List<Integer> modelBefore, IntStack real) {
            int expected = modelBefore.get(modelBefore.size() - 1);
            int actual = real.pop();
            assertEquals(expected, actual);
            assertEquals(modelBefore.size() - 1, real.size());
        }
    }

    /** Deliberately buggy real system: push(value) stores value + 1 instead of value. */
    private static final class OffByOneIntStack extends IntStack {
        @Override
        public void push(int value) {
            super.push(value + 1);
        }
    }

    private static Generator<Command<List<Integer>, IntStack>> pushCommandGen() {
        return Generators.integers().map(PushCommand::new);
    }

    private static Generator<Command<List<Integer>, IntStack>> popCommandGen() {
        return Generators.just(new PopCommand());
    }

    @Test
    @DisplayName("correct IntStack passes cleanly across generated push/pop sequences")
    void correctStackPasses() {
        Commands.<List<Integer>, IntStack>startingWith(ArrayList::new, IntStack::new)
            .withCommand(Generators.oneOf(pushCommandGen(), popCommandGen()))
            .asProperty()
            .check();
    }

    @Test
    @DisplayName("buggy IntStack is falsified and shrinks to a minimal push/pop sequence")
    void buggyStackShrinksToMinimalFailure() {
        Property<List<Command<List<Integer>, IntStack>>> property =
            Commands.<List<Integer>, IntStack>startingWith(ArrayList::new, OffByOneIntStack::new)
                .withCommand(Generators.oneOf(pushCommandGen(), popCommandGen()))
                .asProperty();

        AssertionError error = assertThrows(AssertionError.class, property::check);

        String message = error.getMessage();
        String marker = "Falsifying (shrunk) value:\n  ";
        int start = message.indexOf(marker) + marker.length();
        int end = message.indexOf("\n\nOriginal (unshrunk) value:", start);
        assertTrue(start >= marker.length() && end > start,
            "could not locate shrunk value in report:\n" + message);
        String shrunkValueText = message.substring(start, end);

        int commandCount = countOccurrences(shrunkValueText, "Command@");
        assertTrue(commandCount <= 3,
            "expected a minimal shrunk sequence (<=3 commands), but shrunk value was:\n" + shrunkValueText);
        assertTrue(shrunkValueText.contains("PushCommand") && shrunkValueText.contains("PopCommand"),
            "expected shrunk sequence to contain both a push and a pop, but shrunk value was:\n" + shrunkValueText);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    @DisplayName("preconditions silently skip inapplicable commands")
    void popsOnEmptyStackAreSkippedWithoutFailure() {
        assertDoesNotThrow(() ->
            Commands.<List<Integer>, IntStack>startingWith(ArrayList::new, IntStack::new)
                .withCommand(Generators.oneOf(popCommandGen()))
                .asProperty()
                .check());
    }

    @Test
    @DisplayName("asProperty() requires withCommand(...) to have been called")
    void asPropertyWithoutCommandThrows() {
        Commands<List<Integer>, IntStack> commands =
            Commands.startingWith(ArrayList::new, IntStack::new);

        assertThrows(IllegalStateException.class, commands::asProperty);
    }

    @Test
    @DisplayName("withCommand(Generator, int, int) validates minSize and maxSize bounds eagerly")
    void withCommandValidatesBoundsEagerly() {
        Generator<Command<List<Integer>, IntStack>> cmdGen = pushCommandGen();
        assertThrows(IllegalArgumentException.class,
            () -> Commands.<List<Integer>, IntStack>startingWith(ArrayList::new, IntStack::new)
                .withCommand(cmdGen, -1, 10),
            "withCommand with minSize < 0 must throw IllegalArgumentException immediately");
        assertThrows(IllegalArgumentException.class,
            () -> Commands.<List<Integer>, IntStack>startingWith(ArrayList::new, IntStack::new)
                .withCommand(cmdGen, 10, 5),
            "withCommand with minSize > maxSize must throw IllegalArgumentException immediately");
    }
}
