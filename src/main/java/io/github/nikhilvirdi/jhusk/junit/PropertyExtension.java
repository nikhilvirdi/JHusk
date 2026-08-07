package io.github.nikhilvirdi.jhusk.junit;

import io.github.nikhilvirdi.jhusk.Generator;
import io.github.nikhilvirdi.jhusk.Generators;
import org.junit.jupiter.api.extension.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * JUnit 5 Extension that powers the {@link Property} and {@link ForAll} annotations.
 * 
 * <p>This extension provides a single test template context that internally runs the
 * entire property check loop (generation, invalid budgeting, shrinking, persistence).
 * This ensures that failures are reported cleanly as a single JUnit test failure,
 * complete with Phase 13's shrunk report and Phase 14's persistence identity.
 */
public class PropertyExtension implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return context.getTestMethod().isPresent() &&
               context.getTestMethod().get().isAnnotationPresent(Property.class);
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        return Stream.of(new PropertyInvocationContext());
    }

    private static class PropertyInvocationContext implements TestTemplateInvocationContext {
        @Override
        public String getDisplayName(int invocationIndex) {
            return "Property Check";
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
            return List.of(new PropertyInterceptor(), new ForAllParameterResolver());
        }
    }

    private static class PropertyInterceptor implements InvocationInterceptor {
        @Override
        public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                                ReflectiveInvocationContext<Method> invocationContext,
                                                ExtensionContext extensionContext) throws Throwable {
            
            Method method = invocationContext.getExecutable();
            Property propAnnotation = method.getAnnotation(Property.class);
            Object testInstance = invocationContext.getTarget().orElse(null);

            // 1. Resolve generators for all @ForAll parameters
            Parameter[] parameters = method.getParameters();
            @SuppressWarnings("unchecked")
            Generator<Object>[] generators = new Generator[parameters.length];
            
            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                if (!param.isAnnotationPresent(ForAll.class)) {
                    throw new IllegalStateException("All parameters of a @Property method must be annotated with @ForAll");
                }
                generators[i] = resolveGenerator(param, testInstance);
            }

            // 2. Create the args generator using ArgsHolder for human-readable toString().
            // Property's Phase 13 failure report calls String.valueOf(value), and raw Object[]
            // produces "[Ljava.lang.Object;@hash". ArgsHolder.toString() delegates to
            // Arrays.deepToString(), giving e.g. "[1000000]".
            Generator<ArgsHolder> holderGen = source -> {
                Object[] args = new Object[generators.length];
                for (int i = 0; i < generators.length; i++) {
                    args[i] = generators[i].generate(source);
                }
                return new ArgsHolder(args);
            };

            // 3. Configure the JHusk Property runner
            String propName = propAnnotation.name().isBlank() ? null : propAnnotation.name();
            io.github.nikhilvirdi.jhusk.Property<ArgsHolder> runner = io.github.nikhilvirdi.jhusk.Property.forAll(
                propName, holderGen, holder -> {
                    try {
                        method.setAccessible(true);
                        method.invoke(testInstance, holder.args);
                    } catch (InvocationTargetException e) {
                        sneakyThrow(e.getCause());
                    } catch (Exception e) {
                        sneakyThrow(e);
                    }
                }
            ).examples(propAnnotation.examples());

            // 4. Execute the property loop
            if (propAnnotation.seed().isBlank()) {
                runner.check();
            } else {
                long masterSeed = Long.parseLong(propAnnotation.seed().replaceAll("L$", ""));
                runner.check(masterSeed);
            }

            // Skip the default invocation.proceed() because we already reflectively invoked the method N times.
            invocation.skip();
        }

        @SuppressWarnings("unchecked")
        private Generator<Object> resolveGenerator(Parameter param, Object testInstance) throws Exception {
            ForAll forAll = param.getAnnotation(ForAll.class);
            if (!forAll.value().isBlank()) {
                // Explicit override via factory method
                String methodName = forAll.value();
                Method factory = testInstance.getClass().getDeclaredMethod(methodName);
                factory.setAccessible(true);
                return (Generator<Object>) factory.invoke(testInstance);
            }

            // Type-based inference fallback
            Class<?> type = param.getType();
            if (type == int.class || type == Integer.class) {
                return (Generator) Generators.integers();
            } else if (type == boolean.class || type == Boolean.class) {
                return (Generator) Generators.booleans();
            } else if (type == long.class || type == Long.class) {
                return (Generator) Generators.longs();
            } else if (type == double.class || type == Double.class) {
                return (Generator) Generators.doubles();
            } else if (type == char.class || type == Character.class) {
                return (Generator) Generators.characters();
            } else if (type == String.class) {
                return (Generator) Generators.strings();
            }

            throw new IllegalStateException("Cannot infer default generator for type " + type.getName() + 
                ". Use @ForAll(\"methodName\") to specify an explicit generator factory.");
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
            throw (T) t;
        }
    }

    /**
     * Satisfies JUnit's parameter resolution lifecycle for {@code @ForAll} parameters.
     *
     * <p><b>Important:</b> JUnit validates the resolved value's type against the parameter's
     * declared type <i>before</i> the {@link InvocationInterceptor} runs. Therefore this
     * resolver must return a value assignment-compatible with the parameter type. These
     * placeholder values are never used in practice — the interceptor calls
     * {@code invocation.skip()} and runs the full property loop via {@code Property.check()}
     * instead, which generates real values via JHusk's generators.
     *
     * <p>For primitive types, returns the zero-value (correctly boxed via autoboxing).
     * For reference types, returns {@code null} (which is assignment-compatible with any
     * reference type per JLS §5.2 and accepted by JUnit's resolution validation).
     */
    private static class ForAllParameterResolver implements ParameterResolver {
        @Override
        public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            return parameterContext.isAnnotated(ForAll.class);
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            Class<?> type = parameterContext.getParameter().getType();

            // Primitive types require correctly-typed boxed zero-values.
            // JUnit checks: resolvedValue instanceof parameterType (boxed for primitives).
            if (type == int.class)     return 0;
            if (type == long.class)    return 0L;
            if (type == boolean.class) return false;
            if (type == double.class)  return 0.0;
            if (type == float.class)   return 0.0f;
            if (type == short.class)   return (short) 0;
            if (type == byte.class)    return (byte) 0;
            if (type == char.class)    return '\0';

            // Reference types (String, Integer, List, custom classes, etc.):
            // null is assignment-compatible with any reference type.
            return null;
        }
    }

    /**
     * Thin wrapper around {@code Object[]} that provides a human-readable
     * {@link #toString()} via {@link Arrays#deepToString(Object[])}.
     *
     * <p>Property's Phase 13 failure report calls {@code String.valueOf(value)} on the
     * generated value. Raw {@code Object[]} produces the useless default
     * {@code "[Ljava.lang.Object;@hash"}. This wrapper ensures the report displays
     * the actual argument values, e.g. {@code "[1000000]"}.
     */
    static final class ArgsHolder {
        final Object[] args;

        ArgsHolder(Object[] args) {
            this.args = args;
        }

        @Override
        public String toString() {
            return Arrays.deepToString(args);
        }
    }
}
