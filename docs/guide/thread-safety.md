# Guide: Thread Safety & Concurrency

`Generator<T>`, including everything `Generators` returns, and anything built from `map`, `filter`, `flatMap`, or `combine`, holds no mutable state of its own and is safe to share and reuse across threads, as long as each thread supplies its own `DataSource`.

`DataSource` itself is not thread-safe and must never be shared across threads. This is rarely something you need to think about directly: `Property.check()` creates a fresh `DataSource` per generated example automatically, and you'd only construct one yourself if writing a custom `Generator` entirely from scratch, bypassing composition (see [Guide: Generators](generators.md)).

## `Property` as a mutable builder

`Property<T>` is a mutable builder. Configure an instance — `named`, `examples`, `withStorageDir`, and so on (see [Guide: Properties](properties.md)) — on one thread before calling `check()`. Don't mutate its configuration concurrently with a `check()` call already in progress, and don't call `check()` concurrently on the same instance from multiple threads.

## Running properties concurrently

Running `check()` concurrently across *different* `Property` instances is safe, even when those instances share a failure-storage directory and property identity.

`FailureStorage` writes atomically — temp file then rename — so a concurrent read can never observe a torn or partially-written buffer. What's still true is that concurrent writes to the same identity race for which one's failure ends up stored: one atomic rename simply wins over the other, but the file itself is never corrupted by the race. Distinct identities, or distinct storage directories, are entirely unaffected either way. See [Guide: Understanding Failures](failures.md) for more on how failure persistence works.

## Summary

- Share `Generator<T>` instances across threads freely — they hold no state.
- Never share a single `DataSource` across threads.
- Configure a `Property` instance fully before calling `check()`, on one thread.
- Never call `check()` concurrently on the *same* `Property` instance.
- Calling `check()` concurrently on *different* `Property` instances is safe, including when they share a failure-storage directory.
