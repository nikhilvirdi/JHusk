# JHusk Adversarial Test Suite

This folder contains an independently-authored test suite written specifically to try to
break JHusk from the outside — as an external Maven dependency, with no access to JHusk's
internal implementation. It is not part of JHusk's own build, and it is not run as part of
`mvn clean test` in the main repository.

## Why this exists

Most of JHusk's own test suite (`mvn clean test` in the main repo) was written by the same
people who built the library, which means it can share the same blind spots as the
implementation. This suite was written separately, by an author with no knowledge of
JHusk's internals, deliberately trying to find:

- Boundary and edge-case values the generators might mishandle
- Deeply nested and composed generator structures
- Generators that are impossible or nearly impossible to satisfy
- Deliberately planted bugs in test-side code, to confirm JHusk's shrinking actually
  finds and reports them correctly
- Determinism and thread-safety under concurrent use
- Behavior under stress: very large collections, deep recursion, wide branching
- Generic type inference edge cases across `map`/`flatMap`/`combine`/`oneOf`
- Malformed or unusual API usage patterns

## What's included

Eight test files, organized by category:

| File | Focus |
|---|---|
| `BoundaryValueTests.java` | Integer/collection/string boundary and edge values |
| `NestedCompositeTests.java` | Deeply nested and composed generator structures |
| `ImpossibleGeneratorTests.java` | Filters and generators that can't be satisfied — confirms fast, clean failure rather than hanging |
| `PlantedBugTests.java` | Deliberately buggy code-under-test, to confirm shrinking finds and reports real failures |
| `DeterminismConcurrencyTests.java` | Seed-based reproducibility and thread-safety |
| `StressTests.java` | Large-scale generation: big collections, deep recursion, wide branching |
| `TypeCompositionTests.java` | Generic type inference across chained/composed generators |
| `MalformedUsageTests.java` | Unusual or edge-case API usage patterns |

## How JHusk actually performed

This suite went through several real iterations, and it's worth being upfront about what
happened rather than just reporting a final pass count.

**Real bugs found and fixed in JHusk during this process:**
- `Property.check()` originally threw `AssertionError` for every kind of failure, including
  invalid-budget exhaustion and generator crashes. Because `AssertionError` doesn't extend
  `RuntimeException`, code using `catch (Exception e)` around `check()` couldn't catch it.
  This was fixed by introducing `PropertyExecutionException extends RuntimeException` for
  budget/crash cases, keeping `AssertionError` reserved for genuine property falsification —
  mirroring JUnit's own `AssertionFailedError extends AssertionError` convention.

**Issues found that turned out to be in the test suite itself, not JHusk:**
- An apparent infinite loop was traced (via thread dumps) to a bug in the test's own planted
  "buggy" binary search helper, not JHusk.
- Several early test failures were due to incorrect assumptions in the tests — wrong expected
  exception types, a misunderstanding of `Generators.optionals()`'s return type (it returns a
  nullable `T`, not `Optional<T>`), and overly strict exact-size generators — rather than real
  defects in JHusk.
- One test (`plantedBug_palindromeCaseMismatch_shrinksToMixedCaseInput`) turned out to be
  inherently flaky: its filter condition sits right at the edge of what the default trial
  budget can reliably satisfy, so depending on the random seed drawn, it could either find the
  planted bug (`AssertionError`) or exhaust its budget first (`PropertyExecutionException`).
  Both are legitimate outcomes of the same underlying condition, so the test was rewritten to
  accept either.

**A known, intentional design constraint, not a bug:**
- JHusk's `DataSource` uses a fixed internal buffer cap (8KB) to hold the byte stream that
  generators interpret. Very large collections (roughly tens of thousands of elements, or very
  long strings) can require more bytes than this cap allows to encode, which surfaces as
  `PropertyExecutionException` ("invalid budget exhausted") rather than the collection
  actually being generated. This is confirmed, expected behavior arising from JHusk's
  byte-stream-based design (see the main README's "Concepts" section), not a defect. See the
  `Generators.lists()` / `Generators.sets()` / `Generators.maps()` Javadoc in the main library
  for details, and `StressTests.java` in this folder for tests that explicitly exercise and
  assert this boundary.

## Running this suite

```bash
# From the main JHusk repo, make the library available locally (until it's on Maven Central):
cd /path/to/JHusk
mvn clean install -P release -Dgpg.skip=true

# Then, from this folder:
cd adversarial-tests
mvn clean test
```

All tests are expected to pass on a clean run — including the three `StressTests` cases that
intentionally assert the buffer-cap behavior described above, and the planted-bug tests, which
assert that JHusk correctly detects and reports the deliberately introduced bugs.