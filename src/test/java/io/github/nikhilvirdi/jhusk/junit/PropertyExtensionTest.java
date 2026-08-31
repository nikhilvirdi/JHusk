package io.github.nikhilvirdi.jhusk.junit;

import io.github.nikhilvirdi.jhusk.Generator;
import io.github.nikhilvirdi.jhusk.Generators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

@DisplayName("JUnit 5 Extension tests")
class PropertyExtensionTest {

    // FailingTestCases.failingProperty is deliberately always-failing so it can assert on the
    // report format. PropertyExtension has no way to redirect FailureStorage away from
    // the default ".jhusk/" directory (unlike Property.withStorageDir(), used by PropertyTest and
    // FailurePersistenceTest), so every run of that property persists its shrunk buffer to disk
    // under this property's explicit name. Without cleanup, the *second* run onward replays that
    // stored failure instead of failing fresh -- a completely different report format/branch in
    // Property.check() ("Property Falsified! (Replayed Stored Failure)", "To reproduce, run:"
    // instead of "To reproduce this exact failure, run:") -- which is what made the report
    // assertions below appear to change out from under previous fix attempts.
    private static final Path STORED_FAILURE_FILE = Path.of(".jhusk", "test-junit-failure.bytes");

    @BeforeEach
    @AfterEach
    void clearStoredFailure() throws IOException {
        Files.deleteIfExists(STORED_FAILURE_FILE);
    }

    static class PassingTestCases {
        @Property(examples = 50)
        void passingProperty(@ForAll int x) {
            // A trivial passing property
            assertTrue(x == x);
        }

        static Generator<String> customGen() {
            return Generators.just("constant");
        }

        @Property(examples = 10)
        void customGeneratorProperty(@ForAll("customGen") String value) {
            assertEquals("constant", value);
        }

        @Property(examples = 20)
        void typeInferredDoubleAndCharProperty(@ForAll double d, @ForAll char c) {
            // Exercises PropertyExtension's default type-based generator inference for
            // double/char, which previously fell through to "Cannot infer default generator"
            // even though Generators.doubles()/characters() have existed for a while.
            // d has no meaningful invariant to assert (doubles() can produce NaN/Infinity via
            // raw IEEE 754 bits); reaching this line at all proves resolution succeeded.
            assertTrue(c >= ' ' && c <= '~', "characters() must stay within printable ASCII");
        }
    }

    static class FailingTestCases {
        @Property(name = "test-junit-failure", examples = 100)
        void failingProperty(@ForAll int x) {
            assertTrue(x < 1000000);
        }
    }

    // Regression test for a severe bug found during the final pre-release audit: every @Property
    // method that didn't set an explicit name() funneled through Property.resolvePropertyId()'s
    // stack-walk, which finds the first stack frame whose class isn't Property itself. But EVERY
    // @Property method is invoked through this exact interceptor call site, so that "first
    // non-Property frame" was ALWAYS PropertyExtension's own internal frame, never the user's test
    // method -- meaning every unnamed @Property method in an entire project collided onto one
    // shared ".jhusk/...PropertyExtension...bytes" file, silently overwriting each other's stored
    // failures. Confirmed empirically before the fix (two distinct properties produced exactly one
    // shared file); the fix derives the identity directly from the reflected Method instead.
    static class TwoUnnamedFailingProperties {
        @Property(examples = 3)
        void propertyOne(@ForAll int x) {
            assertTrue(false, "always fails A");
        }

        @Property(examples = 3)
        void propertyTwo(@ForAll int x) {
            assertTrue(false, "always fails B");
        }
    }

    static class GenerationBudgetTestCases {
        @Property(generationBudget = 2, examples = 10)
        void budgetExceededProperty(@ForAll int x) {
            // ints require 4 bytes. A budget of 2 will cause a GenerationBudgetExceededException.
            assertTrue(true);
        }
    }

    @Test
    @DisplayName("Two different unnamed @Property methods get distinct stored-failure identities, not a shared one")
    void unnamedPropertiesDoNotCollideOnAutoDetectedIdentity() throws IOException {
        Path jhuskDir = Path.of(".jhusk");
        List<Path> before = listJhuskFiles(jhuskDir);

        runIsolated(TwoUnnamedFailingProperties.class);

        List<Path> after = listJhuskFiles(jhuskDir);
        List<Path> created = after.stream().filter(p -> !before.contains(p)).collect(Collectors.toList());

        try {
            assertEquals(2, created.size(),
                    "propertyOne and propertyTwo must produce two distinct stored-failure files, not collide "
                            + "onto a single shared one derived from PropertyExtension's own internal call site");
        } finally {
            for (Path p : created) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Runs {@code testClass} through {@code EngineTestKit}, with {@code PropertyReporting}'s
     * registered sink (see its javadoc) temporarily suspended.
     *
     * <p>{@code EngineTestKit} bypasses the real {@code Launcher} entirely, so these nested
     * fixture executions never reach the outer real test plan's own {@code TestExecutionListener}
     * -- but they still run on the same thread and still go through the real
     * {@code PropertyExtension}, which would otherwise report these intentionally-failing
     * fixtures to whatever sink happens to be registered for the OUTER test run this method is
     * running inside of, misattributing them as real {@code @Property} results there.
     */
    private static Events runIsolated(Class<?> testClass) {
        io.github.nikhilvirdi.jhusk.internal.PropertyReporting.Sink suspended =
            io.github.nikhilvirdi.jhusk.internal.PropertyReporting.setSink(null);
        try {
            return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(testClass))
                .execute()
                .testEvents();
        } finally {
            io.github.nikhilvirdi.jhusk.internal.PropertyReporting.restoreSink(suspended);
        }
    }

    private static List<Path> listJhuskFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.collect(Collectors.toList());
        }
    }

    @Test
    @DisplayName("A method annotated @Property with @ForAll runs successfully when property holds")
    void passingPropertyRunsSuccessfully() {
        Events events = runIsolated(PassingTestCases.class);

        events.failed().list().forEach(System.out::println);
        events.assertStatistics(stats -> stats.started(3).succeeded(3).failed(0));
    }

    @Test
    @DisplayName("generationBudget is respected when set via @Property annotation")
    void generationBudgetIsRespected() {
        Events events = runIsolated(GenerationBudgetTestCases.class);

        events.assertStatistics(stats -> stats.started(1).succeeded(0).failed(1));

        List<Event> failedEvents = events.failed().list();
        assertEquals(1, failedEvents.size());

        Throwable error = ((org.junit.platform.engine.TestExecutionResult) failedEvents.get(0).getPayload().get()).getThrowable().get();
        String msg = error.getMessage();
        Throwable cause = error.getCause();
        
        boolean hasBudgetExceeded = error.getClass().getSimpleName().equals("GenerationBudgetExceededException") ||
            msg.contains("GenerationBudgetExceededException") || 
            (cause != null && cause.getClass().getSimpleName().equals("GenerationBudgetExceededException"));
            
        assertTrue(hasBudgetExceeded, "Expected GenerationBudgetExceededException to be thrown due to budget=2");
    }

    @Test
    @DisplayName("A failing @Property produces a JUnit failure containing the shrunk report")
    void failingPropertyProducesJUnitFailureWithReport() {
        Events events = runIsolated(FailingTestCases.class);

        events.assertStatistics(stats -> stats.started(1).succeeded(0).failed(1));

        List<Event> failedEvents = events.failed().list();
        assertEquals(1, failedEvents.size());

        Event failureEvent = failedEvents.get(0);
        org.junit.platform.engine.TestExecutionResult result = 
            (org.junit.platform.engine.TestExecutionResult) failureEvent.getPayload().get();
        Throwable error = result.getThrowable().get();

        String msg = error.getMessage();
        
        // Verify report components are properly wired through the extension
        assertTrue(msg.contains("Property Falsified!"), "Report header present");
        assertTrue(msg.contains("Falsifying (shrunk) value:"), "Shrunk value header present");
        assertTrue(msg.contains("1000000"), "Minimal shrunk value present");
        assertTrue(msg.contains("To reproduce this exact failure"), "Reproduction instructions present");

        assertNotNull(error.getCause(), "Original exception must be preserved as cause");
        assertTrue(error.getCause() instanceof org.opentest4j.AssertionFailedError ||
                   error.getCause() instanceof AssertionError, 
                   "Cause is the actual failed assertion");
    }
}
