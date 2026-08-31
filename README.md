# JHusk

<p align="center"> <img width="400" height="100" alt="JHusk LTL" src="https://github.com/user-attachments/assets/009397db-9358-48fe-9a3d-3beaf4d2cdf7" /> <img width="400" height="100" alt="JHusk DTL" src="https://github.com/user-attachments/assets/694aac97-97dd-4bee-be49-1ffae0e896a5" /> </p>

## What Is JHusk?

Husk is a property-based testing library for Java. Its formal, Java-specific name is JHusk, and that's the name you'll see in the Maven coordinates, the package structure, and anywhere the library needs to be referenced precisely. Husk and JHusk refer to the same library throughout this document.

This library is for Java developers writing unit tests who want their tests to check more than the three or four examples they happened to think of. If you're testing anything with a rule that should hold across a range of inputs, a sorting function, a parser, a serializer, a custom data structure, a numeric algorithm, JHusk generates a wide spread of inputs, including the ones you wouldn't think to write by hand, and checks that rule against every one of them.

JHusk was built by a single developer as a way of bringing Hypothesis-style, internally-shrunk property-based testing to the JVM, an approach that, as far as this project's author is aware, no existing Java library takes in quite the same way.

## Documentation

This README covers what JHusk is and who it's for. For everything else, installation, a full quickstart walkthrough, generator composition, configuring properties, JUnit integration, understanding failures, thread safety, architecture, and comparisons to other libraries, see the full documentation site: **[nikhilvirdi.github.io/JHusk](https://nikhilvirdi.github.io/JHusk/)**.

## Who Is JHusk For?

1. **Students and developers new to testing.** If you're still building the instinct for what a "good test" looks like, JHusk gives you a different way to think about it: instead of picking a handful of examples by hand, you state a rule the code should always satisfy, and JHusk does the example-picking for you.

2. **DSA learners and competitive programmers.** If you're solving data structure and algorithm problems, on LeetCode, in interview prep, or in a DSA course, JHusk is a way to actually trust your solution instead of eyeballing a few test cases. Write a brute-force reference implementation (slow but obviously correct) alongside your optimized one, and let JHusk generate hundreds of random inputs checking that both produce the same answer, this is exactly how differential testing catches the edge case a manual test list misses. It's also a good way to build real intuition for edge cases: empty arrays, single elements, duplicates, negative numbers, integer overflow boundaries, the inputs that actually break naive solutions, rather than only checking the 2-3 examples a problem statement happens to give you.

3. **Application and backend developers.** If you're validating input parsing, form handling, or writing utility functions, string manipulation, date handling, formatting logic, JHusk is where you throw the inputs you wouldn't think to write yourself: empty strings, extreme numbers, unusual Unicode, boundary values. Wire it in as a `@Property` test alongside your existing `@Test` methods, and it runs in the same suite, same CI, no separate tooling.

4. **Library and framework authors.** If you maintain a data structure, a serialization layer, or any public API, JHusk lets you stress-test it the way an external, adversarial caller eventually will. Round-trip properties (encode then decode returns the original) and invariant checks (a sorted list never changes length) catch the exact class of bug that only shows up under inputs nobody thought to hand-write.

5. **Engineers on correctness-critical or algorithmic code.** If you're implementing or optimizing an algorithm, sorting, searching, numeric computation, concurrency primitives, or maintaining code where a wrong edge case has real consequences, financial calculations, data integrity checks, security-relevant parsing, JHusk's shrinking turns a rare, hard-to-reproduce failure into a minimal, debuggable one. If you already know Hypothesis or QuickCheck from another language, this is the same discipline, ported to the JVM with the same shrinking quality.

6. **QA and test engineers.** If testing is your actual job, not just something you do alongside writing features, JHusk is a way to scale your test suite's coverage without scaling the number of test cases you write by hand. It slots into an existing JUnit 5 pipeline your team already runs, so it's additive to what you have, not a replacement toolchain.

7. **Platform and tooling engineers.** If you're writing config parsers, CLI argument handling, or internal developer tooling, JHusk is a natural fit for exactly the kind of "structured text in, structured data out" logic that tends to break on inputs nobody anticipated during a demo.

8. **Open source maintainers.** If your project accepts contributions or public usage from people you don't control, JHusk gives you a way to build confidence against the inputs an unpredictable, wide user base will eventually throw at your code, before they do, rather than after a bug report.

9. **Anyone validating a refactor or migration.** If you're checking that a rewrite, an optimization pass, or a library swap didn't change behavior, JHusk automates comparing old-vs-new output across hundreds of generated inputs, instead of manually re-running a fixed set of examples and hoping they cover what changed.

JHusk isn't a replacement for your existing unit tests. It's a complement: use `@Test` for the specific examples and edge cases you already know matter, and `@Property` for the broader rules you want checked automatically across everything else.

## Key Features

JHusk's feature set is intentionally focused. Rather than trying to cover every possible testing scenario, it aims to do the core property-based testing loop well.

- **Composable generators** for primitives, collections, and custom types, built from `map`, `filter`, `flatMap`, and `combine`, so complex generators are assembled out of simple ones instead of written from nothing.
- **Internal, byte-stream-based shrinking**, so every generator, including ones you write yourself, gets high-quality shrinking without any extra work on your part.
- **A persistent local failure database** that replays known failing cases first on every subsequent run, so a bug you thought you fixed can't quietly resurface without JHusk catching it immediately.
- **Deterministic, reproducible failures** through seed-based replay, meaning any failure JHusk finds can be reproduced exactly, on demand, by anyone with the seed.
- **Native JUnit 5 integration** through a `@Property` annotation, so property tests run alongside your regular `@Test` methods, in the same test reports, with no separate tooling required.

See [Guide: Generators](https://nikhilvirdi.github.io/JHusk/guide/generators.html), [Guide: Understanding Failures](https://nikhilvirdi.github.io/JHusk/guide/failures.html), and [Guide: JUnit Integration](https://nikhilvirdi.github.io/JHusk/guide/junit-integration.html) for the full detail behind each of these.

## Quick Install

JHusk is published on Maven Central under the coordinates `io.github.nikhilvirdi:jhusk`.

```xml
<dependency>
    <groupId>io.github.nikhilvirdi</groupId>
    <artifactId>jhusk</artifactId>
    <version>1.1.1</version>
    <scope>test</scope>
</dependency>
```

See [Installation](https://nikhilvirdi.github.io/JHusk/installation.html) for Gradle setup, verification steps, and requirements, and [Quickstart](https://nikhilvirdi.github.io/JHusk/quickstart.html) for a full walkthrough from nothing to a passing property test.

## How JHusk Is Tested

Most of JHusk's own test suite was written by the same person who built the library, which means it can share the same blind spots as the implementation itself. To get a second, more skeptical perspective, an independent adversarial test suite was written separately, working only against JHusk's public API as an external Maven dependency, with no knowledge of the internals.

That suite covered eight categories: boundary values, deeply nested composite generators, filters and generators that are impossible to satisfy, deliberately planted bugs, used to confirm shrinking actually finds and reports real failures rather than missing them, determinism and thread safety under concurrent use, large-scale stress cases, generic type inference across chained generators, and unusual or malformed API usage.

It's worth being upfront about what that process actually found, rather than only reporting a final pass count.

Real bugs it caught: `Property.check()` originally threw plain `AssertionError` for every kind of failure, including invalid-budget exhaustion and generator crashes. Because `AssertionError` doesn't extend `RuntimeException`, code wrapping `check()` in `catch (Exception e)` couldn't catch it. This is now fixed: `PropertyExecutionException` extends `RuntimeException` and covers budget and crash failures, while `AssertionError` is reserved for genuine property falsification. See [Architecture](https://nikhilvirdi.github.io/JHusk/design/architecture.html) for the full exception hierarchy.

Issues that turned out to be in the test suite itself, not JHusk: an apparent infinite loop traced back to a bug in the test's own planted binary search helper, not JHusk's shrinking logic. A few early failures came from incorrect assumptions baked into the tests themselves, wrong expected exception types, the `optionals()` misunderstanding covered in [Troubleshooting](https://nikhilvirdi.github.io/JHusk/troubleshooting.html), and generators configured with overly strict exact sizes that were, in practice, nearly impossible to satisfy.

A known, intentional design limit rather than a defect: the 8KB default buffer cap described in [Architecture](https://nikhilvirdi.github.io/JHusk/design/architecture.html). Several of the stress tests generated very large collections specifically to exercise this limit, and asserted that JHusk correctly reported a budget-exceeded exception in that scenario, rather than silently succeeding, hanging, or producing an incorrect result.

Beyond that initial review, an independent, external adversarial test suite exists as its own repository, [jhusk-adversarial-tests](https://github.com/nikhilvirdi/jhusk-adversarial-tests). It consumes JHusk's published Maven Central artifact the same way any real project would, rather than testing against JHusk's own source tree, and covers 50 scenarios across boundary values, planted bugs, documented claim verification, and every feature introduced in 1.1.0. It found a real defect in `Generator.flatMap()`, a `filter()` that exhausted its retry budget on JHusk's built-in edge-case corpus could crash the whole property instead of being treated as an ordinary invalid run, fixed in 1.1.1. See that repository for the full methodology, every scenario, and the complete writeup of what was found.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full release history, including every fix and addition in each version.

- [1.1.1](CHANGELOG.md#111---2026-08-10), a fix for a `Generator.flatMap()` crash found by independent adversarial testing
- [1.1.0](CHANGELOG.md#110---2026-08-10), stateful testing, `assuming()`, better shrinking on large collections, and more
- [1.0.1](CHANGELOG.md#101---2026-08-09), bug fixes from an independent adversarial review
- [1.0.0](CHANGELOG.md#100---2026-08-08), first release

## License

JHusk is released under the license included in this repository's `LICENSE` file. See that file for the complete license text.

## Contributing

JHusk isn't currently accepting external contributions. See [CONTRIBUTING.md](CONTRIBUTING.md) for details.