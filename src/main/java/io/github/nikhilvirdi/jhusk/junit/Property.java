package io.github.nikhilvirdi.jhusk.junit;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a JHusk property-based test.
 *
 * <p>This annotation makes the method a JUnit 5 {@link TestTemplate} powered by
 * {@link PropertyExtension}. It appears as a single JUnit test — one "Property Check"
 * invocation, not one per example — which internally runs JHusk's own generate/check/shrink
 * loop, invoking the annotated method up to {@link #examples()} times with generated arguments.
 * A failure at any point is reported as that one JUnit test failing, with the Phase 13 shrunk
 * report (falsifying value, reproduction seed, execution statistics) as the failure message.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith(PropertyExtension.class)
public @interface Property {
    /**
     * The number of successful random examples required for the property to pass.
     *
     * @return the required number of successful examples (default 100)
     */
    int examples() default 100;

    /**
     * An explicit master seed (e.g. "12345L"). If empty, a random seed is used.
     *
     * @return the explicit master seed string, or empty string for a random seed
     */
    String seed() default "";

    /**
     * An explicit identity name for this property, used for storing and replaying
     * minimal shrunk failures persistently across refactors.
     *
     * @return the explicit property identity name, or empty string for auto-detected identity
     */
    String name() default "";

    /**
     * An explicit per-example generation buffer capacity in bytes, overriding the
     * default {@link io.github.nikhilvirdi.jhusk.internal.DataSource#MAX_BUFFER_SIZE}
     * (8192 bytes). Use this when a property's generators (large collections, long
     * strings, deep nesting) legitimately need more than 8KB per example and would
     * otherwise throw {@link io.github.nikhilvirdi.jhusk.GenerationBudgetExceededException}.
     *
     * @return the generation budget in bytes, or {@code -1} to use the default
     */
    int generationBudget() default -1;
}
