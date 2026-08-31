# Troubleshooting & Common Mistakes

A few patterns come up often enough to be worth documenting directly, rather than leaving someone to rediscover them the hard way.

## A property throws `PropertyExecutionException` (or a subtype) instead of `AssertionError`

These mean different things, and the distinction is deliberate. `AssertionError` means JHusk actually generated a value, ran your assertion against it, and the assertion genuinely failed — a real property falsification. `PropertyExecutionException` and its subtypes (`FilterExhaustedException`, `GenerationBudgetExceededException`, `GeneratorCrashException`) mean something prevented JHusk from completing a normal check cycle in the first place. Which specific subtype you get tells you which of those it was — an over-restrictive filter, an exhausted generation budget, or a generator that threw while producing a value — without needing to parse the exception message to find out.

If you're catching exceptions around `check()`, remember that `AssertionError` does not extend `RuntimeException`, so a bare `catch (RuntimeException e)` will not catch a genuine property falsification, only the budget, filter, or crash cases. See [Architecture](design/architecture.md#exception-hierarchy) for why this split exists.

## A filter seems to hang or take a long time

This is almost always a filter that's too restrictive for the values the underlying generator tends to produce. If a filter only accepts, say, one value in ten thousand, JHusk has to draw and discard a huge number of candidates before finding one that passes, and eventually gives up with `FilterExhaustedException` rather than looping forever.

The fix is usually to restructure the generator so it produces valid values directly, rather than generating broadly and filtering down. For instance, instead of:

```java
Generators.integers().filter(n -> n % 2 == 0)
```

prefer:

```java
Generators.integers().map(n -> n * 2)
```

which produces only even numbers to begin with rather than discarding half of everything generated.

## `Generators.optionals()` isn't behaving like an `Optional`

`Generators.optionals(Generator<T>)` returns `Generator<T>`, not `Generator<Optional<T>>`. It produces a value that may be a plain Java `null`, rather than wrapping the value in an `Optional`. If your assertion is written assuming an `Optional` wrapper, that's the mismatch to check first.

## Large collections fail with a budget-exhaustion error

This is very likely the 8KB internal buffer cap described in [Architecture](design/architecture.md). Check whether the collection sizes involved could plausibly exceed that cap given the per-element encoding cost, and lower the maximum size if so, or raise the cap itself with `withGenerationBudget(int)` (`generationBudget` on `@Property`) if the large value is genuinely necessary.

## A stored failure keeps replaying even after you believe you've fixed the bug

Failure persistence replays known failures before generating new examples (see [Guide: Understanding Failures](guide/failures.md)). If you've genuinely fixed the underlying issue, the replay should now pass rather than fail. If it's still failing unexpectedly, or interfering with an unrelated test, check the `.jhusk` directory for stale entries, particularly after renaming a test method or property, since failure identity is tied to how the property is named and located.

## `examples`, `maxInvalidRuns`, or `timeoutPerExample` throws `IllegalArgumentException`

These builder methods validate their input immediately rather than accepting a value that would cause silent, confusing behavior later. `examples` and `maxInvalidRuns` must be positive — a zero or negative example count would otherwise let a property report as passing without ever actually checking anything. `timeoutPerExample` accepts `null` (meaning "no timeout") but rejects a non-null zero or negative `Duration`, since that would guarantee every example times out immediately rather than providing a meaningful safety margin.

## Known limitations

`Generators.optionals(Generator<T>)` returns `Generator<T>`, not `Generator<Optional<T>>` — this is the single most common point of confusion for anyone reaching for this method for the first time (see above).

Generators have an internal size limit, capped at 8KB per generated example by default. For the overwhelming majority of properties, this is completely invisible. If you hit it, you have two options: lower the collection's maximum size, or raise the buffer cap itself.

JHusk requires Java 17 as a floor. There's no compatibility path for earlier Java versions, since the library relies on language features introduced in that release.

`assuming` and `withGenerationBudget` are currently only available via direct `Property.forAll(...)` usage, not through the `@Property` JUnit annotation.
