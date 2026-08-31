# Changelog

All notable changes to JHusk are documented here, most recent release first.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/).

## [1.2.0] - 2026-08-31

A feature release adding formal terminal reporting and a `@Property` timeout attribute, on top of several real correctness fixes found during an expanded internal adversarial review. Nothing here breaks any documented, intended usage from 1.1.1.

### Added

- **Formal terminal output reporting.** Running under JUnit 5, JHusk now groups results by test class and prints a clean `PASS`/`FAIL` summary as each property completes, followed by one final summary once the whole run finishes (total passed/failed/skipped, cumulative examples, elapsed duration). A failing property expands with its full shrunk report immediately, rather than waiting for the run to end. The startup banner now defaults to off (`-Djhusk.banner=true` re-enables it), and output color is auto-detected based on whether output is going to a real terminal, respects the `NO_COLOR` environment variable, and can be forced with `-Djhusk.color=always|never|auto`.
- **`timeoutMillis` on `@Property`.** `Property.timeoutPerExample(Duration)` was previously reachable only through direct `Property.forAll(...)` usage. `@Property(timeoutMillis = 2000)` now sets the same per-example timeout directly from the JUnit annotation.

### Fixed

- **`examples(0)` silently passing without testing anything.** `Property.examples(int)` accepted any value with no validation. Since the check loop is `while (successfulRuns < examples)`, a value of zero or negative meant the loop never ran at all, and the property reported as passing having generated nothing. `examples(int)` now rejects non-positive values immediately with `IllegalArgumentException`. `maxInvalidRuns(int)` had the same gap and is fixed the same way.
- **`timeoutPerExample(Duration)` accepting a timeout that could never succeed.** A zero or negative duration guaranteed every example would time out immediately, with no upfront indication anything was misconfigured. Non-null zero or negative durations are now rejected immediately with `IllegalArgumentException`; `null` (meaning "no timeout") remains valid.
- **`Commands.withCommand(Generator, int, int)` deferring invalid bounds to `asProperty()` time.** A negative `minSize` or `minSize > maxSize` was only caught later, inside `Generators.lists()`, once `asProperty()` was eventually called, far from where the actual mistake was made. `withCommand(...)` now validates its bounds immediately, at the call site.
- **A null pointer exception on a static `@Property` method using a static `@ForAll` factory.** `PropertyExtension.resolveGenerator` looked up the referenced generator factory method via the test instance's class, which is `null` for a static test method under JUnit 5's own semantics, crashing with a bare `NullPointerException` before reaching this method's other, well-formed error messages. It now uses the test method's declaring class instead, which works correctly for both static and instance methods, and reports a clear `IllegalStateException` in the one genuinely invalid case: a static test method referencing a non-static factory.
- **`IntStack` removed from the published jar.** `IntStack` was a leftover teaching fixture from this project's earliest bootstrapping, already documented in its own package's javadoc as not part of the public API despite being `public`. It has been moved to this project's own test sources, so it no longer ships in the jar at all. Anyone who was, against that documentation, depending on it directly will need to supply their own equivalent.

Verified against an expanded internal adversarial suite consolidating and extending four independent test efforts: 210 scenarios spanning boundary values, malformed usage, concurrency, deep generator composition, stateful command sequences, and 17 deliberately planted bugs, all correctly caught.

## [1.1.1] - 2026-08-10

A bug-fix release. No new features, no breaking changes; everything
that worked in 1.1.0 continues to work exactly as before.

### Fixed

- **Generator.flatMap() crashing on a non-VALID upstream draw.**
  flatMap() invoked its function on whatever the upstream generator
  returned without checking its status first -- including a filter()
  that had exhausted its retry budget and returned null. On the
  deterministic edge-case corpus introduced in 1.1.0, a
  filter().flatMap() chain where the filter rejects the value a
  shrink-target buffer decodes to crashed deterministically on every
  single check() call, misreported as GeneratorCrashException instead
  of an ordinary invalid run. flatMap() now checks source status
  before invoking its function, exactly like every other loop already
  does for every other generator. Found by an independent 50-scenario
  adversarial test suite run against the published 1.1.0 jar; see
  https://github.com/nikhilvirdi/jhusk-adversarial-tests for the full
  writeup.

## [1.1.0] - 2026-08-10

A feature release adding stateful testing, a distinct precondition mechanism, better shrinking on large collections, and a handful of smaller additions, on top of one real bug fix. Nothing here breaks anything that worked in 1.0.1.

### Added

- **Deterministic edge-case corpus.** Before generating any random examples, check() now runs two fixed byte buffers: one that decodes to every generator's minimum/false/empty shrink target, and one that decodes to the opposite boundary. Every property exercises these values on every run now, not only when a random draw happens to land on them.
- **assuming() as a distinct precondition.** Property gets a new assuming(Predicate<T>) method that sits between generation and the assertion, separate from Generator.filter(). A rejected value counts as an invalid run with its own counter, reported separately from filter and buffer-overrun causes when the invalid budget runs out.
- **Exhaustive generators.** Generators.exhaustive(T...) picks among a small, explicit set of values and guarantees the first and last are exercised through the edge-case corpus above. booleans() already gets this coverage for free now that the corpus exists, since an all-zero buffer decodes to false and an all-0xFF buffer decodes to true.
- **generationBudget() on @Property.** The existing withGenerationBudget(int) builder method, previously reachable only through direct Property.forAll(...) usage, is now available on the annotation directly.
- **Better shrinking on large collections.** A delta-debugging pass now tries removing whole chunks of a list before falling back to one element at a time, cutting both the number of shrink attempts and the size of the final result on failures involving many elements. Sequence shrinking through Commands, below, inherits this for free, since a command sequence is just a list underneath.
- **Stateful testing with Commands.** A new Command<Model, Real> interface and Commands<Model, Real> builder generate sequences of operations, run each one against a simplified model and the real system side by side, and shrink the failing sequence when something disagrees. It's built entirely on existing generator composition; no changes to the core generation or shrinking machinery were needed.
- **Pass stats and a startup banner.** A one-line summary prints for every property that passes, breaking down how many examples ran, how many came from the edge-case corpus versus random generation, and how any invalid runs split between overrun, filter, and assumption causes. A small banner prints once per test run. Both can be turned off with -Djhusk.banner=false.

### Fixed

- **Timeout coverage on stored-failure replay.** timeoutPerExample(Duration) protected the edge-case corpus and the random generation loop, but not the path that replays a previously stored failure on the next run. A property with a configured timeout could hang indefinitely if the stored failure itself triggered the same kind of hang. Replay now runs through the same timeout-guarded path as everything else.

## [1.0.1] - 2026-08-09

A bug-fix release addressing gaps found during an independent adversarial review. No new features, no breaking API removals; everything that worked in 1.0.0 continues to work exactly as before.

### Fixed

- **Windows atomic-write hardening.** FailureStorage's atomic rename now retries up to five times to handle transient NTFS file-lock contention on Windows, closing a real gap where a concurrent write could occasionally fail to land.
- **Exception hierarchy split.** Property.check() previously threw a single generic PropertyExecutionException for every kind of non-assertion failure. It now throws a specific subtype depending on what actually went wrong: FilterExhaustedException for an over-restrictive filter, GenerationBudgetExceededException for exhausted generation budget, and GeneratorCrashException for a generator that throws during execution. Existing code catching PropertyExecutionException continues to work unchanged, since these are subtypes of it.
- **Timeout protection.** Property now supports timeoutPerExample(Duration), guarding against a hung generator or an infinite flatMap chain blocking a test run indefinitely. A run that exceeds the configured timeout fails cleanly with PropertyTimeoutException instead of hanging.
- **Documented and configurable optionals() null rate.** Generators.optionals()'s null probability, previously an undocumented implementation detail, is now documented explicitly, and a new overload lets you configure that probability directly.
- **Configurable generation budget.** The previously fixed 8KB internal buffer cap on DataSource is now configurable through withGenerationBudget(int), for properties that genuinely need to generate very large values. The 8KB default is unchanged for everyone who doesn't need this.

## [1.0.0] - 2026-08-08

The first published release of JHusk, a property-based testing library for Java built on internal, byte-stream-based shrinking, the same family of approach Hypothesis uses in Python, rather than the tree-based integrated shrinking most JVM libraries use.

### Added

- Composable generators for primitives, collections, and custom types, built from map, filter, flatMap, and combine.
- Internal shrinking that works generically across every generator, including custom ones, without any shrinking logic needing to be hand-written.
- Native JUnit 5 integration through @Property and @ForAll.
- Deterministic, reproducible failures through seed-based replay.
- A persistent local failure database that replays known failing cases on every subsequent run.
- Rich failure reports: shrunk value, original value, reproduction seed, and execution statistics.

### Known Limitations

DataSource used a fixed 8KB internal buffer to encode generated values. Very large collections, tens of thousands of elements, or very long strings, could exceed this cap and surface as PropertyExecutionException. This was a known, intentional constraint of the design rather than a defect; it became configurable in 1.0.1.