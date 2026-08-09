# BUGS.md — JHusk Priority Fix List

This document consolidates all findings from the audit and adversarial testing phase into a single, actionable list for implementation. Items are ordered by priority. Each item includes the problem, why it matters, and the recommended technical solution.

---

## Fix Immediately

> **Status note:** Six items originally on this list are confirmed fixed in the current codebase and have been removed/marked done:
> - Windows atomic-write bug — `FailureStorage` now uses `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` with retry logic.
> - Configurable invalid-run budget — `Property.maxInvalidRuns(int)` now exists.
> - **No infinite-loop protection (was #1)** — `Property.timeoutPerExample(Duration)` added; wraps assertion execution in a bounded `Future.get(timeout)` on a daemon-threaded executor, throws `PropertyTimeoutException` (extends `PropertyExecutionException`) on timeout without entering the shrink path, defaults to `null` (no timeout, fully backward compatible). Verified via `mvn clean test`: 123 tests run, 0 failures, 0 errors.
> - **Generic exception message conflated three causes (was #1)** — split into
>   `FilterExhaustedException`, `GenerationBudgetExceededException`, and
>   `GeneratorCrashException`, all extending the existing `PropertyExecutionException`
>   base type so existing `catch (PropertyExecutionException e)` / `catch (RuntimeException e)`
>   callers remain unaffected. Message text for the budget-exhaustion cases is unchanged —
>   only the thrown exception TYPE now differs by cause. The mixed-cause case (some
>   invalid runs from filters, some from overruns) still throws the generic base
>   `PropertyExecutionException`, deliberately, since neither subtype accurately
>   describes a mixed cause. Verified via `mvn clean test`: 127 tests run, 0 failures,
>   0 errors (up from 123 — 4 new tests added in a new PropertyTest$ExceptionHierarchyTests
>   nested class).
> - **Undocumented `optionals()` null probability (was #4)** — the existing
>   single-argument `optionals(Generator<T>)` now has its exact null rate
>   (1/256 ≈ 0.39%) documented in Javadoc as a stable, committed guarantee (not
>   changed — its byte-encoding is untouched for backward compatibility with
>   stored `.jhusk/` failure buffers). A new overload,
>   `optionals(Generator<T> gen, double nullProbability)`, was added allowing
>   callers to configure the null rate explicitly (validated to `[0.0, 1.0]`,
>   throwing `IllegalArgumentException` immediately otherwise), implemented as an
>   independent encoding rather than a delegation. Verified via `mvn clean test`:
>   132 tests run, 0 failures, 0 errors (up from 127 — 5 new tests added in a new
>   GeneratorsTest$ConfigurableOptionalsTests nested class).
> - **Hardcoded 8KB buffer cap (was #4)** — `DataSource` gained two new constructor
>   overloads (`DataSource(long seed, int maxBufferSize)` and
>   `DataSource(byte[] buffer, int maxBufferSize)`); the original two constructors
>   now delegate to these with `MAX_BUFFER_SIZE` as the default, so existing callers
>   are byte-for-byte unaffected. `Property` gained a new
>   `withGenerationBudget(int bytes)` builder method (validated `> 0`) that threads
>   a custom cap through to both `DataSource` construction sites in `check()`. Not
>   yet exposed via the JUnit `@Property` annotation — direct `Property.forAll(...)`
>   usage only, by design (kept out of scope for this fix). Verified via
>   `mvn clean test`: 138 tests run, 0 failures, 0 errors (up from 132 — 6 new tests
>   added across DataSourceTest$ConfigurableBufferSizeTests and
>   PropertyTest$ConfigurableGenerationBudgetTests).

### 1. No stateful/model-based testing
**Problem:** JHusk has no equivalent to jqwik's `@StatefulProperty`, Hypothesis's `RuleBasedStateMachine`, or ScalaCheck's `Commands`. There is no way to test a *sequence* of operations against a model and shrink the failing sequence itself.

**Why it matters:** This is the single biggest feature gap versus jqwik. Real bugs in stateful systems (caches, data structures, connection pools, any object with mutable internal state across calls) are usually only found by sequences of operations, not one-shot property checks. Without this, JHusk cannot compete for a large category of real-world testing use cases.

**Solution:** Add a **Commands** abstraction: each command has a precondition (is this command valid given current model state), an execution against the real system, an execution against a simplified model, and a postcondition comparing the two. JHusk generates random *sequences* of commands, runs them against both, and on failure shrinks the sequence itself — dropping commands, not just individual arguments — down to a minimal failing sequence. Key design goal: encode the sequence on the same underlying byte-stream used elsewhere, so sequence-shrinking inherits the same free, generic quality as value-shrinking — a genuinely novel differentiator, since jqwik's stateful shrinking is still bound by per-generator shrink-tree limitations.

**Note:** This is significant feature work, not a patch. Treat as its own design/RFC process before implementation.

---

### 2. No exhaustive generation for small finite domains
**Problem:** Enums, booleans, and other small closed sets are handled via pure random sampling. There is no mode that detects a small domain and enumerates it exhaustively.

**Why it matters:** Pure random sampling wastes budget and can miss values that a mature library would simply enumerate — a real risk for enum-heavy real-world code, which is extremely common.

**Solution:** Add a generator mode that detects when a domain is provably small (e.g. `Generators.enumValues(MyEnum.class)`, `Generators.booleans()`, or `oneOf` with few alternatives) and switches from random sampling to full enumeration up to a configurable cutoff (e.g. domain size ≤ 1000 → exhaustive, else fall back to random). This should integrate with the trial-count budget so exhaustive domains don't waste the full example budget re-sampling already-covered values.

**Note:** Significant feature work — treat as its own design/RFC process before implementation.

---

### 3. No `Assume`/precondition primitive distinct from `filter()`
**Problem:** JHusk conflates "narrow the generated domain" with "reject a specific bad combination" under one mechanism (`filter()`). This is part of why issue #1 happens — there's no way to distinguish assumption-driven skips from filter rejections or crashes in bookkeeping.

**Why it matters:** jqwik's `Assume.that()` is a distinct conceptual primitive with its own statistics reporting. Without this, diagnostics stay muddled and the library doesn't match the mental model experienced property-testing users already have.

**Solution:** Add `Generators.assume(condition)` or a `Property`-level `.assuming(predicate)`, tracked and reported separately from filter rejections and crashes (e.g. "342 examples assumed away, 100 valid examples checked"). Directly supports the diagnostic clarity goals of fix #1.

---

## Secondary (Not Blocking, But Should Be Tracked)

- Naming clash between `io.github.nikhilvirdi.jhusk.Property` (builder) and `io.github.nikhilvirdi.jhusk.junit.Property` (annotation) — easy import mistake, IDE autocomplete ambiguity.
- Documentation gaps: default bounds for `strings()` (~100 chars) and `maps()` (~100 entries) are not stated in Javadoc, forcing users to reverse-engineer them to avoid budget exhaustion.
- `.jhusk/` stored-failure directory causes cross-run flakiness if not explicitly managed in CI (gitignored vs. cached) — needs explicit guidance in docs.
- Potential unpaired/lone surrogate characters from `Generators.characters()` — not confirmed broken, but a latent risk for downstream `String` construction; needs an explicit internal guarantee and test.
- No coverage-guided or statistically-informed generation (as in QuickTheories) — pure random sampling with no feedback loop toward unexplored code paths.
- Thin observability/reporting — no built-in way to inspect the actual distribution of generated values (e.g. how we found the `optionals()` null rate required manual instrumentation).

---

## Suggested Sequencing

| Order | Items | Scope |
|---|---|---|
| 1 (minor version) | #3 | Medium scope |
| 2 (design/RFC required) | #1, #2 | Significant feature work — get API shape review before implementation |

Everything under "Secondary" should be tracked but does not block the above sequencing.