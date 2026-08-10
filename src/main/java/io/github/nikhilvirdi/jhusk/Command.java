package io.github.nikhilvirdi.jhusk;

/**
 * A single state-transitioning operation in a {@link Commands} sequence.
 *
 * <p>Each command is checked and applied in three steps, in order, each
 * time its turn comes up in a generated sequence:
 * <ol>
 *   <li>{@link #precondition(Object)} -- is this command valid given the
 *       model's current state? If false, this command is silently
 *       skipped: not a filter rejection, not an invalid run, just a
 *       no-op for this position in the sequence.</li>
 *   <li>{@link #nextModel(Object)} -- compute what the model's state
 *       should become after this command runs, WITHOUT touching the real
 *       system yet. Must return a new value, not mutate the model
 *       in place -- {@code modelBefore} is relied on by
 *       {@link #runAndVerify} to represent the state immediately prior
 *       to this command.</li>
 *   <li>{@link #runAndVerify(Object, Object)} -- actually execute
 *       against the real system, and assert (e.g. via JUnit's
 *       {@code assertEquals}) that its resulting state or return value
 *       matches what the model predicts. Throwing from here (a normal
 *       assertion failure) is how a genuine property falsification is
 *       reported, exactly like any other JHusk property.</li>
 * </ol>
 *
 * @param <Model> a simplified representation of expected state
 * @param <Real> the actual system under test
 */
public interface Command<Model, Real> {

    /**
     * @param model the model's current state, before this command runs
     * @return whether this command is applicable given that state
     */
    boolean precondition(Model model);

    /**
     * @param model the model's current state, before this command runs
     * @return the model's new state after this command conceptually runs
     */
    Model nextModel(Model model);

    /**
     * Executes this command against the real system and verifies its
     * behavior matches the model.
     *
     * @param modelBefore the model's state immediately prior to this command
     * @param real the real system under test
     */
    void runAndVerify(Model modelBefore, Real real);
}
