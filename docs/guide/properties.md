# Guide: Properties

A **property** is a rule you assert should hold for any input a generator can produce. You write it as a plain method. JHusk runs it against a large batch of generated inputs, and any input that violates the rule counts as a genuine failure.

Beyond the default behavior, `Property`'s builder exposes a small set of configuration options for cases where the defaults don't fit.

## Naming a property

You can give a property an explicit name, which is useful for making failure-storage identity clear and stable, particularly if you refactor a test method and don't want JHusk to treat the refactored version as an entirely new, unrelated property:

```java
Property.forAll(generator, assertion).named("myCustomPropertyName").check();
```

## Controlling how many examples run

You can control how many examples are checked in a single run, if the default trial count doesn't suit a particular property — for instance a property you want checked more thoroughly because it covers something especially important, or less thoroughly because each individual check is expensive:

```java
Property.forAll(generator, assertion).examples(500).check();
```

`examples` must be positive. A value of zero or negative is rejected immediately with an `IllegalArgumentException`, rather than silently accepted — a property that never actually generates or checks any input would otherwise report as passing without having tested anything at all.

## Setting a per-example timeout

If an example might legitimately hang (for instance, a generator could produce input that triggers an infinite loop bug in the code under test), you can set a per-example timeout so JHusk fails fast with a `PropertyTimeoutException` instead of hanging the whole test run:

```java
Property.forAll(generator, assertion).timeoutPerExample(Duration.ofSeconds(2)).check();
```

The default is no timeout — each example runs on the current thread with no time limit. A non-null timeout must be positive; zero or negative durations are rejected immediately, since a timeout that fires before an example can realistically complete would only produce confusing, guaranteed failures rather than a meaningful safety net.

## Tolerating invalid runs

You can raise the maximum number of invalid runs (filter rejections or generator overruns) tolerated before `check()` aborts, if a generator is legitimately expected to reject a large fraction of draws:

```java
Property.forAll(generator, assertion).maxInvalidRuns(5000).check();
```

The default is 1000. Like `examples`, this must be positive.

## Raising the generation budget

Generators encode the values they produce as a stream of bytes, capped at 8KB per generated example by default. For the overwhelming majority of properties, this limit is invisible. But if you generate a very large collection or a very long string, you can raise the cap:

```java
Property.forAll(generator, assertion).withGenerationBudget(16384).check();
```

See [Troubleshooting](../troubleshooting.md) for what it looks like when you hit this limit, and how to decide between raising the budget versus lowering your collection's maximum size.

## Adding a precondition with `assuming`

`assuming` sets a property-level precondition checked against each successfully generated value, before the assertion runs. It's distinct from a generator's `filter`: a filter narrows what a generator can ever produce and is checked per-value as it's drawn, potentially deep inside a composed generator; an assumption is checked once, against the final fully-assembled value, right before the assertion — appropriate for preconditions that only make sense once the whole value exists (for example, "the two halves of this generated pair must not be equal"), rather than being expressible as a constraint on any single generator in isolation.

```java
Property.forAll(pairGenerator, assertion)
    .assuming(pair -> !pair.first().equals(pair.second()))
    .check();
```

A value rejected by this predicate counts as an invalid run, exactly like a filter rejection or buffer overrun — it does not count toward `examples`, and it counts against `maxInvalidRuns`.

Note: `assuming` and `withGenerationBudget` are currently only available via direct `Property.forAll(...)` usage, not through the `@Property` JUnit annotation. See [JUnit Integration](junit-integration.md) for the full set of options available there.

`Property<T>` is a mutable builder, so the pattern is always the same: configure whatever options you need on one thread, then call `check()`. Don't mutate a `Property` instance's configuration concurrently with a `check()` call already in progress, and don't call `check()` concurrently on the same instance from multiple threads — running `check()` concurrently across *different* `Property` instances is safe, even when those instances share a failure-storage directory and property identity.

## Next

- [Guide: Thread Safety & Concurrency](thread-safety.md) — what's safe to share across threads, and what isn't
- [Guide: JUnit Integration](junit-integration.md) — the `@Property`/`@ForAll` equivalent of these options
