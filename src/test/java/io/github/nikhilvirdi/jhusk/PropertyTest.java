package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Property Runner tests (Generate-and-Check)")
class PropertyTest {

    @Test
    @DisplayName("Buggy property fails within N examples and reports raw falsifying value")
    void buggyPropertyFails() {
        Generator<Integer> gen = Generators.integers();

        // Property: "All integers are less than 1,000,000" (deliberately buggy)
        AssertionError error = assertThrows(AssertionError.class, () -> {
            Property.forAll(gen, value -> {
                assertTrue(value < 1000000, "Value " + value + " is >= 1,000,000");
            }).check(42L); // Fixed master seed for reproducible test
        });

        String message = error.getMessage();
        System.out.println("--- RAW FAILURE OUTPUT (DELIBERATELY UGLY) ---");
        System.out.println(message);
        System.out.println("----------------------------------------------");

        assertTrue(message.contains("Falsifying example found"), "Message should mention falsifying example");
        assertTrue(message.contains("Seed:"), "Message should contain the failing seed");
        assertTrue(message.contains("Value:"), "Message should contain the raw failing value");
        assertTrue(message.contains("Value ") && message.contains(" is >= 1,000,000"), "Message should contain the assertion error");
    }

    @Test
    @DisplayName("Correct property passes cleanly across N examples")
    void correctPropertyPasses() {
        Generator<List<Integer>> gen = Generators.lists(Generators.integers());

        // Property: "Reversing a list twice returns the original list"
        // Should pass cleanly, no exception thrown.
        Property.forAll(gen, list -> {
            List<Integer> copy = new ArrayList<>(list);
            Collections.reverse(copy);
            Collections.reverse(copy);
            assertEquals(list, copy);
        }).check(99L);
    }

    @Test
    @DisplayName("Filter exhaustion triggers invalid budget abort")
    void filterExhaustionAborts() {
        // Generator that always marks itself invalid via an impossible filter
        Generator<Integer> gen = Generators.integers().filter(x -> false);

        AssertionError error = assertThrows(AssertionError.class, () -> {
            Property.forAll(gen, value -> {
                // This block should never be reached for a valid element
                assertTrue(true);
            }).check(123L);
        });

        assertTrue(error.getMessage().contains("exhausted invalid budget"), 
            "Should throw invalid budget exception, not silently pass");
        assertTrue(error.getMessage().contains("Too many invalid runs"),
            "Message should clarify too many invalid runs occurred");
    }

    @Test
    @DisplayName("Failing buffer can be replayed to reproduce the exact same failing value")
    void failingBufferRoundTrips() {
        Generator<Integer> gen = Generators.integers();

        // Property that fails on specific negative numbers
        AssertionError error = assertThrows(AssertionError.class, () -> {
            Property.forAll(gen, value -> {
                assertTrue(value >= -100, "Value " + value + " is less than -100");
            }).check(456L);
        });

        // The error message format embeds the seed. Let's parse it out to replay.
        String msg = error.getMessage();
        String seedStr = msg.substring(msg.indexOf("Seed: ") + 6, msg.indexOf("L\n", msg.indexOf("Seed: ")));
        long failingSeed = Long.parseLong(seedStr);

        // Replay generation from that exact seed
        DataSource replaySource = new DataSource(failingSeed);
        int reproducedValue = gen.generate(replaySource);
        
        // Find what the original value was
        String valueStr = msg.substring(msg.indexOf("Value: ") + 7, msg.indexOf("\n\nException:"));
        int originalValue = Integer.parseInt(valueStr);

        assertEquals(originalValue, reproducedValue, 
            "Replaying from the captured seed must reproduce the exact same value");
            
        // And it should indeed fail the property check again
        assertThrows(AssertionError.class, () -> {
            assertTrue(reproducedValue >= -100, "Value " + reproducedValue + " is less than -100");
        });
    }
}
