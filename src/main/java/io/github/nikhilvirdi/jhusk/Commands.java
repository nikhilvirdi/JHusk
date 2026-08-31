package io.github.nikhilvirdi.jhusk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds a {@link Property} that generates sequences of {@link Command}s,
 * running each against both a simplified model and the real system under
 * test, and shrinking the failing sequence itself on falsification.
 *
 * <p>Structurally, a command sequence is just a {@code List<Command>} --
 * this class adds no new generation or shrinking machinery. It composes
 * entirely out of {@link Generators#lists}, {@link Generators#oneOf} (for
 * combining multiple command types -- compose that yourself before
 * calling {@link #withCommand}, the same way you would for any other
 * {@code oneOf}-based generator), and {@link Property#forAll}. Because
 * the sequence is generated as an ordinary list, it inherits JHusk's
 * existing list shrinking (span deletion, chunk removal) with no
 * command-specific shrink logic needed.
 *
 * <p>Preconditions are checked at EXECUTION time, inside the generated
 * assertion, not at generation time: a command whose
 * {@link Command#precondition} fails when its turn comes is silently
 * skipped for that position in the sequence. This is not an invalid run
 * and has no interaction with {@link Property}'s invalid-run budget.
 *
 * @param <Model> a simplified representation of expected state
 * @param <Real> the actual system under test
 */
public final class Commands<Model, Real> {

    private final Supplier<Model> initialModel;
    private final Supplier<Real> initialReal;
    private final Generator<Command<Model, Real>> commandGen;
    private final int minSize;
    private final int maxSize;

    private Commands(Supplier<Model> initialModel, Supplier<Real> initialReal,
                      Generator<Command<Model, Real>> commandGen, int minSize, int maxSize) {
        this.initialModel = initialModel;
        this.initialReal = initialReal;
        this.commandGen = commandGen;
        this.minSize = minSize;
        this.maxSize = maxSize;
    }

    /**
     * Starts building a {@code Commands} sequence, given fresh-instance
     * suppliers for the model and the real system (each example gets its
     * own freshly-created model and real instance).
     *
     * @param initialModel supplies a fresh initial model for each example
     * @param initialReal supplies a fresh initial real-system instance for each example
     * @param <M> the model type
     * @param <R> the real system type
     * @return a {@code Commands} builder with no command generator set yet
     */
    public static <M, R> Commands<M, R> startingWith(Supplier<M> initialModel, Supplier<R> initialReal) {
        return new Commands<>(initialModel, initialReal, null, 0, Generators.DEFAULT_LIST_MAX_SIZE);
    }

    /**
     * Sets the generator used to produce each command in the sequence,
     * using {@link Generators#lists(Generator)}'s default length bound
     * ({@code [0, 100]}). If you need multiple kinds of commands, compose
     * them with {@link Generators#oneOf} before passing the result here.
     *
     * @param commandGen generator for a single command
     * @return a new {@code Commands} instance with this command generator set
     */
    public Commands<Model, Real> withCommand(Generator<Command<Model, Real>> commandGen) {
        return new Commands<>(initialModel, initialReal, commandGen, 0, Generators.DEFAULT_LIST_MAX_SIZE);
    }

    /**
     * Sets the generator used to produce each command in the sequence,
     * with an explicit sequence-length bound, mirroring
     * {@link Generators#lists(Generator, int, int)}.
     *
     * @param commandGen generator for a single command
     * @param minSize minimum sequence length (inclusive)
     * @param maxSize maximum sequence length (inclusive)
     * @return a new {@code Commands} instance with this command generator set
     * @throws IllegalArgumentException if {@code minSize < 0} or {@code minSize > maxSize}
     */
    public Commands<Model, Real> withCommand(Generator<Command<Model, Real>> commandGen, int minSize, int maxSize) {
        if (minSize < 0) {
            throw new IllegalArgumentException("minSize cannot be negative: " + minSize);
        }
        if (minSize > maxSize) {
            throw new IllegalArgumentException("minSize (" + minSize + ") must be <= maxSize (" + maxSize + ")");
        }
        return new Commands<>(initialModel, initialReal, commandGen, minSize, maxSize);
    }

    /**
     * Builds the {@link Property} that generates and checks command
     * sequences. The returned {@code Property} is a completely ordinary
     * {@code Property<List<Command<Model, Real>>>} -- {@link
     * Property#examples(int)}, {@link Property#assuming}, {@link
     * Property#withGenerationBudget(int)}, and every other existing
     * builder method work on it exactly as they would on any other
     * property, since sequence shrinking needs no special handling.
     *
     * @return a runnable {@code Property} over generated command sequences
     * @throws IllegalStateException if {@link #withCommand} was never called
     */
    public Property<List<Command<Model, Real>>> asProperty() {
        if (commandGen == null) {
            throw new IllegalStateException(
                "Commands.withCommand(...) must be called before asProperty()");
        }
        Generator<List<Command<Model, Real>>> sequenceGen =
            Generators.lists(commandGen, minSize, maxSize);

        return Property.forAll(sequenceGen, sequence -> {
            Model model = initialModel.get();
            Real real = initialReal.get();
            for (Command<Model, Real> cmd : sequence) {
                if (!cmd.precondition(model)) {
                    continue;
                }
                Model before = model;
                model = cmd.nextModel(model);
                cmd.runAndVerify(before, real);
            }
        });
    }
}
