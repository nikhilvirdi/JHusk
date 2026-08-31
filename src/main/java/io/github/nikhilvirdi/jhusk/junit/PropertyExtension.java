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
 * <p><b>Not intended for direct use.</b> {@code @Property} already applies this extension via
 * {@code @ExtendWith(PropertyExtension.class)}; user code should never construct or reference
 * this class directly. It is {@code public} only because JUnit 5's extension SPI requires
 * classes named in {@code @ExtendWith} to be public with a public no-arg constructor.
 *
 * <p>This extension provides a single test template context that internally runs the
 * entire property check loop (generation, invalid budgeting, shrinking, persistence).
 * This ensures that failures are reported cleanly as a single JUnit test failure,
 * complete with a shrunk report and a persistence identity.
 */
public class PropertyExtension implements TestTemplateInvocationContextProvider {

    /**
     * Constructs a new {@code PropertyExtension} instance.
     * Required by JUnit 5's extension SPI.
     */
    public PropertyExtension() {
    }

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
            // Property's failure report calls String.valueOf(value), and raw Object[]
            // produces "[Ljava.lang.Object;@hash". ArgsHolder.toString() delegates to
            // Arrays.deepToString(), giving e.g. "[1000000]".
            Generator<ArgsHolder> holderGen = source -> {
                Object[] args = new Object[generators.length];
                for (int i = 0; i < generators.length; i++) {
                    args[i] = generators[i].generate(source);
                }
                return new ArgsHolder(args);
            };

            // 3. Configure the JHusk Property runner.
            //
            // Property identity MUST be derived here, from the reflective Method, rather than
            // left blank to fall back on Property.resolvePropertyId()'s stack-walk. That fallback
            // finds the first stack frame whose class isn't Property itself -- but every @Property
            // method is invoked through this exact call site, so the "first non-Property frame"
            // is ALWAYS this PropertyInterceptor, never the user's test method. Left blank, every
            // unnamed @Property method in an entire project collides onto one shared
            // ".jhusk/io.github.nikhilvirdi.jhusk.junit.PropertyExtension...bytes" file, silently
            // overwriting each other's stored failures. Deriving the identity directly from the
            // reflected method is both the fix and strictly more reliable than a stack walk ever
            // was for this path.
            String propName = propAnnotation.name().isBlank()
                ? method.getDeclaringClass().getName() + "." + method.getName()
                : propAnnotation.name();
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

            if (propAnnotation.generationBudget() != -1) {
                runner.withGenerationBudget(propAnnotation.generationBudget());
            }

            if (propAnnotation.timeoutMillis() != -1) {
                runner.timeoutPerExample(java.time.Duration.ofMillis(propAnnotation.timeoutMillis()));
            }

            // 4. Execute the property loop.
            //
            // Reporting is enabled on this thread ONLY for the duration of this exact check()
            // call, so that a direct Property.forAll(...).check() made from inside a plain @Test
            // body -- JHusk's own white-box tests of Property's failure-reporting behavior do
            // exactly this -- is never mistaken for a real @Property result: that call runs on
            // the same thread, but never goes through this enable/disable pair, so
            // PropertyReporting.activeSink() correctly returns null for it regardless of whether
            // a summary listener is registered for the run. See PropertyReporting's javadoc.
            io.github.nikhilvirdi.jhusk.internal.PropertyReporting.beginReporting();
            try {
                if (propAnnotation.seed().isBlank()) {
                    runner.check();
                } else {
                    long masterSeed = Long.parseLong(propAnnotation.seed().replaceAll("L$", ""));
                    runner.check(masterSeed);
                }
            } finally {
                io.github.nikhilvirdi.jhusk.internal.PropertyReporting.endReporting();
            }

            // Skip the default invocation.proceed() because we already reflectively invoked the method N times.
            invocation.skip();
        }

        @SuppressWarnings("unchecked")
        private Generator<Object> resolveGenerator(Parameter param, Object testInstance) throws Exception {
            ForAll forAll = param.getAnnotation(ForAll.class);
            if (!forAll.value().isBlank()) {
                // Explicit override via factory method. Every failure mode here is rewrapped with
                // a JHusk-specific message instead of letting a raw reflection exception surface,
                // since this is exactly the "did I wire up @ForAll correctly" misuse scenario a
                // user is most likely to hit while first writing a custom generator factory.
                String methodName = forAll.value();
                Method factory;
                try {
                    factory = testInstance.getClass().getDeclaredMethod(methodName);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(
                        "@ForAll(\"" + methodName + "\") on a parameter of '"
                        + param.getDeclaringExecutable().getName() + "' could not find a no-argument method "
                        + "named '" + methodName + "' in " + testInstance.getClass().getName()
                        + ". The referenced method must be static, take no arguments, and return a Generator<T>.",
                        e);
                }
                factory.setAccessible(true);

                Object result;
                try {
                    result = factory.invoke(testInstance);
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException(
                        "The generator factory method '" + methodName + "' referenced by @ForAll(\"" + methodName
                        + "\") threw an exception while building its Generator.", e.getCause());
                }
                if (!(result instanceof Generator)) {
                    throw new IllegalStateException(
                        "The generator factory method '" + methodName + "' referenced by @ForAll(\"" + methodName
                        + "\") must return a Generator<T>, but returned "
                        + (result == null ? "null" : result.getClass().getName()) + ".");
                }
                return (Generator<Object>) result;
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
     * <p>Property's failure report calls {@code String.valueOf(value)} on the
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
