package io.github.nikhilvirdi.jhusk;

import io.github.nikhilvirdi.jhusk.internal.FailureStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 14 Failure Persistence tests")
class FailurePersistenceTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jhusk_persistence_test_");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted((a, b) -> b.compareTo(a))
                      .forEach(path -> {
                          try {
                              Files.deleteIfExists(path);
                          } catch (IOException ignored) {}
                      });
            }
        }
    }

    @Test
    @DisplayName("A failure is saved to disk after a property run fails")
    void failureIsSavedToDisk() {
        Generator<Integer> gen = Generators.integers(0, 1000);
        String propId = "test-saved-failure";

        assertThrows(AssertionError.class, () -> {
            Property.forAll(propId, gen, val -> assertTrue(val < 50))
                    .withStorageDir(tempDir)
                    .check(12345L);
        });

        FailureStorage storage = new FailureStorage(tempDir);
        assertTrue(storage.loadFailure(propId).isPresent(), "Shrunk failure buffer must be saved to disk");
    }

    @Test
    @DisplayName("Fresh Property instance replays stored failure first, reporting immediately with 0 new examples")
    void replaysStoredFailureFirst() {
        Generator<Integer> gen = Generators.integers(0, 1000);
        String propId = "test-replay-first";

        // First run fails and saves stored buffer
        assertThrows(AssertionError.class, () -> {
            Property.forAll(propId, gen, val -> assertTrue(val < 50))
                    .withStorageDir(tempDir)
                    .check(12345L);
        });

        // Fresh Property instance simulating new JVM run
        AssertionError replayedError = assertThrows(AssertionError.class, () -> {
            Property.forAll(propId, gen, val -> assertTrue(val < 50))
                    .withStorageDir(tempDir)
                    .check(99999L); // Different master seed!
        });

        String msg = replayedError.getMessage();
        assertTrue(msg.contains("Replayed Stored Failure"), "Report should state it replayed stored failure");
        assertTrue(msg.contains("Examples run: 0"), "Should report 0 new examples run");
    }

    @Test
    @DisplayName("A fixed property prunes stored failure from disk and allows subsequent runs to proceed")
    void fixedPropertyPrunesStoredFailure() {
        Generator<Integer> gen = Generators.integers(0, 1000);
        String propId = "test-auto-prune";
        AtomicBoolean bugFixed = new AtomicBoolean(false);

        // 1. Initial run fails and saves to disk
        assertThrows(AssertionError.class, () -> {
            Property.forAll(propId, gen, val -> {
                if (!bugFixed.get()) {
                    assertTrue(val < 50);
                }
            }).withStorageDir(tempDir).check(12345L);
        });

        FailureStorage storage = new FailureStorage(tempDir);
        assertTrue(storage.loadFailure(propId).isPresent(), "Stored failure should exist initially");

        // 2. Fix the bug!
        bugFixed.set(true);

        // 3. Re-run property. The stored failure will pass and be pruned, then 100 new examples pass.
        assertDoesNotThrow(() -> {
            Property.forAll(propId, gen, val -> {
                if (!bugFixed.get()) {
                    assertTrue(val < 50);
                }
            }).withStorageDir(tempDir).check(99999L);
        });

        // 4. Verify stored failure was pruned
        assertFalse(storage.loadFailure(propId).isPresent(), "Stored failure must be pruned from disk once bug is fixed");
    }

    @Test
    @DisplayName("Two different property identities do not collide or overwrite each other")
    void distinctIdentitiesDoNotCollide() {
        Generator<Integer> gen = Generators.integers(0, 1000);
        String propA = "property-alpha";
        String propB = "property-beta";

        // Fail property A
        assertThrows(AssertionError.class, () -> {
            Property.forAll(propA, gen, val -> assertTrue(val < 50))
                    .withStorageDir(tempDir)
                    .check(111L);
        });

        // Fail property B
        assertThrows(AssertionError.class, () -> {
            Property.forAll(propB, gen, val -> assertTrue(val < 100))
                    .withStorageDir(tempDir)
                    .check(222L);
        });

        FailureStorage storage = new FailureStorage(tempDir);
        assertTrue(storage.loadFailure(propA).isPresent(), "Property A failure stored");
        assertTrue(storage.loadFailure(propB).isPresent(), "Property B failure stored");

        assertNotEquals(
            storage.loadFailure(propA).get(),
            storage.loadFailure(propB).get(),
            "Stored failure buffers for distinct properties should be distinct"
        );
    }
}
