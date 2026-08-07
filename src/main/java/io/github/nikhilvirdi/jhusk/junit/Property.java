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
     */
    int examples() default 100;

    /**
     * An explicit master seed (e.g. "12345L"). If empty, a random seed is used.
     */
    String seed() default "";

    /**
     * An explicit identity name for this property, used for storing and replaying
     * minimal shrunk failures persistently across refactors.
     */
    String name() default "";
}
