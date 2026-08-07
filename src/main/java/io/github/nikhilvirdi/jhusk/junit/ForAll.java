package io.github.nikhilvirdi.jhusk.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a parameter in a {@link Property} method as one that should be populated
 * by JHusk using random generation.
 *
 * <p>By default, JHusk resolves a generator from the parameter's declared type, for exactly
 * these types (both the primitive and boxed form): {@code int}/{@code Integer} →
 * {@code Generators.integers()}, {@code boolean}/{@code Boolean} → {@code Generators.booleans()},
 * {@code long}/{@code Long} → {@code Generators.longs()}, {@code double}/{@code Double} →
 * {@code Generators.doubles()}, {@code char}/{@code Character} → {@code Generators.characters()},
 * and {@code String} → {@code Generators.strings()}. Any other type, including {@code float},
 * {@code short}, {@code byte}, and any generic or custom type, requires an explicit {@link
 * #value()} override.
 *
 * <p><b>Limitation:</b> Generic types (like {@code List<Integer>}) suffer from runtime type
 * erasure. You cannot rely on default type inference for generic collections. Instead,
 * you must explicitly supply a generator via the {@link #value()} override pointing to a
 * static method returning a {@code Generator<T>}.
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ForAll {
    /**
     * Optional explicit generator factory method name (e.g. "myCustomGen").
     * The referenced method must be a {@code static} method in the same class
     * taking no arguments and returning a {@code Generator}.
     */
    String value() default "";
}
