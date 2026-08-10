# Changelog

All notable changes to JHusk are documented here, most recent release first.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/).

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