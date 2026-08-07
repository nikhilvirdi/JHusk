# Husk (JHusk)

[![CI](https://github.com/nikhilvirdi/JHusk/actions/workflows/ci.yml/badge.svg)](https://github.com/nikhilvirdi/JHusk/actions/workflows/ci.yml)

## What is JHusk?

<p align="center">
  <img width="400" height="100" alt="JHusk LTL" src="https://github.com/user-attachments/assets/009397db-9358-48fe-9a3d-3beaf4d2cdf7" />
  <img width="400" height="100" alt="JHusk DTL" src="https://github.com/user-attachments/assets/694aac97-97dd-4bee-be49-1ffae0e896a5" />
</p>

Husk is a property-based testing library for Java. Its formal, Java-specific name is JHusk, and that's the name you'll see in the Maven coordinates, the package structure, and anywhere the library needs to be referenced precisely. Husk and JHusk refer to the same library throughout this document.

This library is for Java developers writing unit tests who want their tests to check more than the three or four examples they happened to think of. If you're testing anything with a rule that should hold across a range of inputs, a sorting function, a parser, a serializer, a custom data structure, a numeric algorithm, JHusk generates a wide spread of inputs, including the ones you wouldn't think to write by hand, and checks that rule against every one of them.

## What does it do?

A normal unit test picks its own examples: `assertEquals(5, add(2, 3))`. The problem is that a person can only think of so many examples, and bugs tend to hide in the ones nobody thought to write down. Empty collections. Negative numbers. Duplicate entries. Boundary values. Strings with unusual Unicode in them.

Property-based testing inverts this. Instead of picking examples, you describe a rule that should always be true, "sorting a list should never change its length," "decoding an encoded value should return the original value," and the library generates hundreds or thousands of inputs on its own, actively trying to find one that breaks the rule.

Finding a failure is only half the job. A failing case can be a forty-element list full of arbitrary numbers, and staring at forty arbitrary numbers doesn't tell you much. So JHusk shrinks it: it keeps searching for smaller, simpler inputs that still trigger the same failure, until it can't reduce the input any further. A forty-element mess might shrink down to two elements, and the actual cause becomes obvious.

## Equivalent libraries

### In Java

- jqwik: the most established property-based testing library on the JVM today, built directly on JUnit 5. It uses integrated, tree-based shrinking, where each generator carries its own logic for shrinking itself.
- QuickTheories: a lighter-weight JVM library that supports both shrinking and targeted, coverage-guided search for failures.
- junit-quickcheck: one of the earliest Java property-based testing tools, built on JUnit 4's theories mechanism. It added shrinking support in a later release, though JUnit 4 itself has since moved into maintenance mode.

### In other languages

- Hypothesis (Python): the library that popularized modern property-based testing outside the Haskell world, and the direct design inspiration for JHusk's shrinking approach.
- QuickCheck (Haskell): the original property-based testing library, created by Koen Claessen and John Hughes in 2000, and the ancestor of everything else in this space.
- Hedgehog (Haskell): a later alternative to QuickCheck with its own take on integrated shrinking.
- fast-check (JavaScript / TypeScript)
- PropEr (Erlang)
- test.check (Clojure)
- ScalaCheck (Scala)
- RapidCheck (C++)

## Concepts

### Generators
A generator describes how to produce a random value of a given type: an integer, a string, a list, or a custom object built from your own domain. Generators are composable. You build complex ones out of simple ones using operations like `map`, `filter`, and `flatMap`, instead of writing each one from nothing.

### Properties
A property is a rule you assert should hold for any input a generator can produce. You write it as a plain method. JHusk runs it against a large batch of generated inputs, and any input that violates the rule counts as a genuine failure.

### Shrinking
When a property fails, JHusk doesn't hand you the raw failing input as-is. It searches for a smaller version of the same failure and keeps searching until it can't reduce the input any further, then reports that minimal version.

Most JVM libraries implement this through integrated shrinking: each generator is responsible for knowing how to produce smaller versions of its own output. JHusk takes a different approach, borrowed from Hypothesis's internal design, a component Hypothesis itself calls Conjecture. Underneath every generator, there's really just a stream of random bytes, and the generator is an interpreter that turns those bytes into a value. Shrinking works by minimizing that underlying byte stream directly, then re-running the interpreter on the shrunk bytes to get a smaller value. One consequence of this design: shrinking comes built in for every generator you write, including custom ones, because there's a single general-purpose byte-stream minimizer instead of one that every generator author has to hand-implement.

### Failure persistence
Once JHusk finds a failing input, it saves the byte stream that produced it to a local file. The next test run replays known failures first, so a bug you thought was fixed can't quietly resurface without JHusk catching it immediately.

## Features

- Composable generators for primitives, collections, and custom types, built from `map`, `filter`, `flatMap`, and `combine`
- Internal, byte-stream-based shrinking, so custom generators get high-quality shrinking without extra work from their authors
- A persistent local failure database that replays known failing cases on every run
- Deterministic, reproducible failures through seed-based replay
- Native JUnit 5 integration through a `@Property` annotation

## Usage

The examples below are real, compiled code, not aspirational API sketches — each one is backed by
a test in `ReadmeExamplesTest` that runs the exact sample shown and fails the build if it ever
stops compiling or behaving as documented.

### A minimal property
```java
static Generator<List<Integer>> integerLists() {
    return Generators.lists(Generators.integers());
}

@Property
void reversingTwiceReturnsTheOriginalList(@ForAll("integerLists") List<Integer> list) {
    List<Integer> reversed = new ArrayList<>(list);
    Collections.reverse(reversed);
    Collections.reverse(reversed);
    assertEquals(list, reversed);
}
```
JHusk generates a hundred lists by default, including empty ones, single-element ones, and ones
with duplicates, and checks this assertion against every one of them. Note the explicit
`@ForAll("integerLists")`, pointing at a static generator factory, rather than a bare `@ForAll`:
generic types like `List<Integer>` are erased at runtime, so JHusk can't infer a generator for one
automatically the way it can for `int`, `long`, `double`, `char`, `boolean`, or `String`.

### Composing generators
```java
Generator<Point> points = Generators.combine(
    Generators.integers(-1000, 1000),
    Generators.integers(-1000, 1000),
    Point::new
);
```
This builds a generator for a custom `Point` type out of two integer generators, with no manual randomness or bounds-checking code required.

### A shrunk failure, before and after
A property asserting no list element exceeds 10 fails on a 50-element list of random positive integers:
```
Falsifying (shrunk) value:
  [11]

Original (unshrunk) value:
  [20695, 93362, 75080, 76371, 24401, 59448, 37393, 33853, 92859, 71806, 57184, 5120, 93123, 66524, 49239, 72997, 44345, 78011, 81261, 90943, 94231, 241, 38550, 2132, 86408, 84489, 35148, 44128, 6509, 32739, 89240, 38170, 79299, 82341, 87827, 53812, 26419, 4008, 82162, 90492, 39560, 41511, 9374, 81070, 59640, 49457, 10115, 77172, 92012, 95741]
```
JHusk searches for a smaller version that still fails, and reports the minimal case: a 50-element mess shrinks to a single element, `11`, one past the boundary the property actually cares about. The full failure report also includes the reproduction seed, execution statistics, and the original exception — see `ReadmeExamplesTest.capturesRealShrinkReportForReadme` for the complete, unedited output.

### JUnit 5 integration
Properties run alongside regular `@Test` methods, appear in the same test reports, and behave like any other JUnit 5 test as far as your build and CI setup are concerned. A `@Property` method shows up as a single test — not one per generated example — that internally runs JHusk's full generate/check/shrink loop and fails with the shrunk report above if any example falsifies it.

## Thread-safety

- **`Generator<T>`** (including everything `Generators` returns, and anything built from `map`/`filter`/`flatMap`/`combine`) holds no mutable state of its own and is safe to share and reuse across threads, as long as each thread supplies its own `DataSource`.
- **`DataSource`** is not thread-safe and must never be shared across threads. This is rarely something you need to think about directly: `Property.check()` creates a fresh `DataSource` per generated example, and you'd only construct one yourself if writing a custom `Generator` from scratch.
- **`Property<T>`** is a mutable builder. Configure an instance (`named`, `examples`, `withStorageDir`, etc.) on one thread before calling `check()`; don't mutate its configuration concurrently with a `check()` call, and don't call `check()` concurrently on the same instance from multiple threads.
- Running `check()` concurrently across *different* `Property` instances is safe even when they share a failure-storage directory and property identity: `FailureStorage` writes atomically (temp file + rename), so a concurrent read can never observe a torn or partially-written buffer. What's still true is that concurrent writes to the *same* identity race for which one's failure ends up stored — one atomic rename simply wins over the other. Distinct identities or storage directories are entirely unaffected either way.

## How JHusk differs from jqwik

jqwik is the most mature and widely used property-based testing library on the JVM, and a genuinely strong tool. The difference between it and JHusk is architectural, not a claim that one is better than the other in general.

jqwik uses integrated shrinking. Each generator carries its own knowledge of how to produce a smaller version of whatever it generates, represented as a tree of values. This works well, and jqwik implements it carefully, but it means shrinking quality depends partly on how well each individual generator's shrinking logic was written, including any custom generator you write yourself.

JHusk uses internal shrinking, the same family of approach Hypothesis uses in Python. Every generator is really an interpreter over a stream of bytes, and shrinking works by minimizing that byte stream once, generically, on behalf of every generator at the same time. A custom generator built through composition inherits high-quality shrinking automatically, without its author writing any shrinking logic at all.

jqwik and QuickTheories already answer the question of whether Java has property-based testing. JHusk is aimed at a narrower, more specific question: whether Java has property-based testing with Hypothesis-grade shrinking. As far as this library's authors are aware, nothing on the JVM has answered that second question yet.

## Acknowledgments

JHusk's shrinking design is a direct descendant of Hypothesis, created by David R. MacIver, and its internal engine, Conjecture. Property-based testing itself traces back to QuickCheck, created by Koen Claessen and John Hughes for Haskell in 2000. Both projects did the hard conceptual work this library builds on.