# JHusk

<p align="center">
  <picture>
    <source
      media="(prefers-color-scheme: dark)"
      srcset="https://github.com/user-attachments/assets/694aac97-97dd-4bee-be49-1ffae0e896a5"
    />
    <img
      width="400"
      height="100"
      alt="JHusk"
      src="https://github.com/user-attachments/assets/009397db-9358-48fe-9a3d-3beaf4d2cdf7"
    />
  </picture>
</p>

## What Is JHusk?

Husk is a property-based testing library for Java. Its formal, Java-specific name is JHusk, and that's the name you'll see in the Maven coordinates, the package structure, and anywhere the library needs to be referenced precisely. Husk and JHusk refer to the same library throughout this document.

This library is for Java developers writing unit tests who want their tests to check more than the three or four examples they happened to think of. If you're testing anything with a rule that should hold across a range of inputs, a sorting function, a parser, a serializer, a custom data structure, a numeric algorithm, JHusk generates a wide spread of inputs, including the ones you wouldn't think to write by hand, and checks that rule against every one of them.

JHusk was built by a single developer as a way of bringing Hypothesis-style, internally-shrunk property-based testing to the JVM, an approach that, as far as this project's author is aware, no existing Java library takes in quite the same way.

## Who Is JHusk For?

JHusk is built for Java developers who write JUnit 5 tests and want stronger coverage than hand-picked examples can give. It's useful across a wide range of experience levels and project types.

If you're new to testing, JHusk can still be useful to you:

- You're learning how to write good unit tests and want a tool that helps you think in terms of rules and properties, rather than just individual examples.
- You're a student working through algorithms and data structure assignments and want a way to check your implementation against a wide range of inputs without writing dozens of test cases by hand.

If you're building everyday application code:

- You're validating input parsing or form handling and want to make sure odd inputs, empty strings, extreme numbers, unusual Unicode, don't slip through unhandled.
- You're writing utility functions, string manipulation, date handling, formatting logic, where a small oversight in one edge case can cause a real production bug.
- You've been bitten before by a bug that only showed up on an input nobody thought to write a test for, and you'd rather catch that kind of thing automatically going forward.

If you're building libraries or reusable components:

- You're building a data structure or library and want confidence it behaves correctly across a wide range of inputs, including boundary values, deeply nested structures, and unusual combinations others might throw at it.
- You maintain a serialization or encoding layer, JSON, binary formats, custom protocols, where round-tripping a value, encoding it and then decoding it, should always return the original, and any exception to that rule is a real bug.
- You're writing a public API and want to stress-test it the way an external, adversarial user might, before they do.

If you work on correctness-critical or algorithmic code:

- You're implementing or optimizing an algorithm, sorting, searching, numeric computation, concurrency primitives, where subtle correctness bugs are easy to introduce and hard to catch with a handful of examples.
- You maintain code where a single wrong edge case has real consequences: financial calculations, data integrity checks, security-relevant parsing.
- You already know property-based testing from another language, Hypothesis in Python or QuickCheck in Haskell, and want the same approach, including high-quality automatic shrinking, on the JVM.

JHusk isn't a replacement for your existing unit tests. It's a complement: use `@Test` for the specific examples and edge cases you already know matter, and `@Property` for the broader rules you want checked automatically across everything else.

## Why Property-Based Testing?

A normal unit test picks its own examples: `assertEquals(5, add(2, 3))`. The problem is that a person can only think of so many examples, and bugs tend to hide in the ones nobody thought to write down. Empty collections. Negative numbers. Duplicate entries. Boundary values. Strings with unusual Unicode in them.

Property-based testing inverts this. Instead of picking examples, you describe a rule that should always be true, "sorting a list should never change its length," "decoding an encoded value should return the original value," and the library generates hundreds or thousands of inputs on its own, actively trying to find one that breaks the rule.

Finding a failure is only half the job. A failing case can be a forty-element list full of arbitrary numbers, and staring at forty arbitrary numbers doesn't tell you much. So JHusk shrinks it: it keeps searching for smaller, simpler inputs that still trigger the same failure, until it can't reduce the input any further. A forty-element mess might shrink down to two elements, and the actual cause becomes obvious.

The value of this approach compounds over time. A test suite built entirely from examples only ever protects against the specific bugs the author already thought to guard against. A property, once written, keeps checking new random inputs on every single run, which means it can catch a regression introduced months later by code nobody connected to the original test.

## What Makes JHusk Different?

Most property-based testing libraries on the JVM, and in fact most property-based testing libraries in general, use what's called integrated shrinking. Each generator carries its own knowledge of how to produce a smaller version of whatever it generates, usually represented as a tree of values. This works, and it's implemented carefully in libraries like jqwik, but it means shrinking quality depends on how well each individual generator's shrinking logic was written, including any custom generator a user writes themselves.

JHusk takes a different approach, the same family of approach Hypothesis uses in Python, where the underlying engine is called Conjecture. Instead of generators owning their own shrinking logic, JHusk treats every generator as an interpreter over a stream of random bytes. A value is really just an interpretation of that byte stream. Shrinking works by minimizing the byte stream directly, once, generically, and then re-running every generator's interpretation logic on the shrunk bytes to get a smaller value.

The practical consequence of this design is that a custom generator, built purely through composition with `map`, `filter`, `flatMap`, and `combine`, inherits high-quality shrinking automatically. Its author never has to write any shrinking logic at all. That's the core bet JHusk makes: rather than asking every generator author to solve shrinking themselves, solve it once, generically, underneath everything.

## Key Features

JHusk's feature set is intentionally focused. Rather than trying to cover every possible testing scenario, it aims to do the core property-based testing loop well.

- **Composable generators** for primitives, collections, and custom types, built from `map`, `filter`, `flatMap`, and `combine`, so complex generators are assembled out of simple ones instead of written from nothing.
- **Internal, byte-stream-based shrinking**, so every generator, including ones you write yourself, gets high-quality shrinking without any extra work on your part.
- **A persistent local failure database** that replays known failing cases first on every subsequent run, so a bug you thought you fixed can't quietly resurface without JHusk catching it immediately.
- **Deterministic, reproducible failures** through seed-based replay, meaning any failure JHusk finds can be reproduced exactly, on demand, by anyone with the seed.
- **Native JUnit 5 integration** through a `@Property` annotation, so property tests run alongside your regular `@Test` methods, in the same test reports, with no separate tooling required.

## Requirements & Compatibility

JHusk is built and tested against JDK 17, 21, and 25. It requires Java 17 as a minimum, since it relies on language features introduced in that release.

JHusk's JUnit 5 integration requires JUnit Jupiter to already be part of your project's test setup. If you're starting a project from scratch and don't yet have JUnit 5 configured, the [JUnit 5 user guide](https://junit.org/junit5/docs/current/user-guide/#writing-tests) walks through that setup.

JHusk works with both Maven and Gradle, and doesn't require any build plugin beyond the standard JUnit Platform test runner your build tool already uses to discover and run JUnit 5 tests.

## Installation

JHusk is published on Maven Central under the coordinates `io.github.nikhilvirdi:jhusk`.

### Adding JHusk with Maven

Add the dependency to your `pom.xml`, inside the `<dependencies>` block:

```xml
<dependency>
    <groupId>io.github.nikhilvirdi</groupId>
    <artifactId>jhusk</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

The `test` scope keeps JHusk out of your production classpath, since it's a testing tool and has no reason to ship with your application.

### Adding JHusk with Gradle

For Groovy-based `build.gradle` files:

```groovy
testImplementation 'io.github.nikhilvirdi:jhusk:1.0.0'
```

For Kotlin DSL `build.gradle.kts` files:

```kotlin
testImplementation("io.github.nikhilvirdi:jhusk:1.0.0")
```

### Verifying the Installation

Once you've added the dependency, run your build tool's dependency resolution to confirm it downloads correctly:

```bash
mvn dependency:resolve
```

or, for Gradle:

```bash
gradle dependencies --configuration testCompileClasspath
```

Either command should list `io.github.nikhilvirdi:jhusk:1.0.0` among the resolved dependencies. If it doesn't appear, double check the coordinates match exactly what's shown above, and that your build file's dependency block was saved correctly.

## Getting Started

Once the dependency is in place, here's the shortest path from nothing to a passing property test.

First, make sure your project has a working JUnit 5 test setup. If you already have `@Test` methods running successfully with `mvn test` or `gradle test`, you're ready to go, no additional configuration is needed for JHusk beyond the dependency itself.

Second, decide what rule you want to check. Property-based testing works best when you can state something that should always be true, regardless of the specific input. Common patterns include:

- Round-tripping: encoding then decoding a value returns the original value.
- Invariants: a sorted list stays the same length as the original, a set never contains duplicates.
- Idempotence: applying an operation twice produces the same result as applying it once.
- Comparisons against a simpler reference implementation.

Third, write the property using `@Property` and `@ForAll`, as shown in the walkthrough below.

Fourth, run it. A `@Property` method behaves like any other JUnit 5 test as far as your build and CI setup are concerned. It shows up as a single test in your test report, not one per generated example, and internally runs JHusk's full generate, check, and shrink loop.

### Your First Property Test

The example below is real, compiled code, not an aspirational sketch, it's backed by a test in `ReadmeExamplesTest` that runs this exact sample and fails the build if it ever stops compiling or behaving as documented.

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

JHusk generates a hundred lists by default, including empty ones, single-element ones, and ones with duplicates, and checks this assertion against every one of them.

Notice the explicit `@ForAll("integerLists")`, pointing at a static generator factory method, rather than a bare `@ForAll`. This is necessary because generic types like `List<Integer>` are erased at runtime, so JHusk can't infer a generator for one automatically the way it can for simpler types like `int`, `long`, `double`, `char`, `boolean`, or `String`. For those simpler types, a bare `@ForAll` works without needing a named generator method at all.

## Core Concepts: Properties, Generators & Shrinking

Three ideas sit underneath everything JHusk does, and understanding them makes the rest of the library much easier to reason about.

A **generator** describes how to produce a random value of a given type: an integer, a string, a list, or a custom object built from your own domain. Generators are composable. You build complex ones out of simple ones using operations like `map`, `filter`, and `flatMap`, instead of writing each one from nothing.

A **property** is a rule you assert should hold for any input a generator can produce. You write it as a plain method. JHusk runs it against a large batch of generated inputs, and any input that violates the rule counts as a genuine failure.

**Shrinking** is what happens after a property fails. JHusk doesn't hand you the raw failing input as-is. It searches for a smaller version of the same failure and keeps searching until it can't reduce the input any further, then reports that minimal version.

Underneath every generator, there's really just a stream of random bytes, and the generator is an interpreter that turns those bytes into a value. Shrinking works by minimizing that underlying byte stream directly, then re-running the interpreter on the shrunk bytes to get a smaller value. One consequence of this design is that shrinking comes built in for every generator you write, including custom ones, because there's a single general-purpose byte-stream minimizer instead of one that every generator author has to hand-implement.

### Working with Generators

The `Generators` class is where most of your day-to-day work with JHusk happens. It provides factory methods for primitives, collections, and combinators for building more complex generators out of simpler ones.

For primitives:

```java
Generators.integers()              // any int
Generators.integers(0, 100)        // an int within a range, inclusive
Generators.longs()
Generators.doubles()
Generators.booleans()
Generators.characters()
Generators.strings()
```

Bounded integer ranges are worth calling out specifically, since they're extremely common in real tests. `Generators.integers(min, max)` generates a value anywhere in that inclusive range, and JHusk's shrinker knows to shrink toward `min`, not toward zero, which matters when your range doesn't include zero at all.

For collections:

```java
Generators.lists(Generators.integers())              // a list of ints, default size range
Generators.lists(Generators.integers(), 0, 20)        // a list of ints, 0 to 20 elements
Generators.sets(Generators.integers(0, 10))
Generators.maps(Generators.strings(), Generators.integers())
```

For combining and transforming generators:

```java
Generators.just(42)                                    // always produces the same value
Generators.oneOf(genA, genB, genC)                      // picks one of several generators
Generators.combine(genA, genB, (a, b) -> new Thing(a, b))
Generators.optionals(Generators.integers())             // a generator that may produce null
```

A quick and important note on `Generators.optionals()`: it returns `Generator<T>`, not `Generator<Optional<T>>`. The value it produces may be a plain Java `null`, rather than wrapped in an `Optional`. This has caught more than one person off guard, so it's worth remembering the first time you reach for it.

### Creating & Composing Custom Generators

Almost every generator you'll actually use in a real test suite is built by composing the primitives above, rather than writing a generator from first principles. The three composition tools you'll reach for most often are `map`, `filter`, and `flatMap`, along with the standalone `combine` function for joining multiple independent generators together.

`map` transforms the output of a generator without changing how often it runs or what values it can produce, it just applies a function to whatever comes out:

```java
Generator<String> digitStrings = Generators.integers(0, 9).map(String::valueOf);
```

`filter` narrows a generator down to only the values that satisfy a predicate. Values that don't satisfy the predicate are discarded and JHusk tries again:

```java
Generator<Integer> evenNumbers = Generators.integers().filter(n -> n % 2 == 0);
```

Filters should be used carefully. A filter that rejects most of the values a generator produces forces JHusk to work harder to find a valid example, and a filter that's nearly impossible to satisfy can cause a run to exhaust its budget entirely, more on that in the Troubleshooting section below.

`flatMap` lets one generator's output determine the shape of a second generator:

```java
Generator<List<Integer>> sameLengthLists =
    Generators.integers(0, 10).flatMap(size -> Generators.lists(Generators.integers(), size, size));
```

For combining several independent generators into a single custom type, `Generators.combine` is usually the cleanest tool:

```java
Generator<Point> points = Generators.combine(
    Generators.integers(-1000, 1000),
    Generators.integers(-1000, 1000),
    Point::new
);
```

This builds a generator for a custom `Point` type out of two integer generators, with no manual randomness or bounds-checking code required. Because JHusk's shrinking works generically over the underlying byte stream, this composed generator gets the same quality of shrinking as any built-in generator, without its author writing a single line of shrinking logic.

For a custom type built from several fields, this pattern scales naturally: keep composing `combine` calls, or nest `flatMap` where one field's valid range genuinely depends on another's, and the resulting generator will shrink sensibly without any additional effort.

## Understanding Test Failures

When a property fails, JHusk doesn't just tell you something went wrong. It hands you a full report designed to get you to the root cause as quickly as possible.

Here's what a real shrunk failure looks like. A property asserting no list element exceeds 10 fails on a 50-element list of random positive integers:

```
Falsifying (shrunk) value:
  [11]

Original (unshrunk) value:
  [20695, 93362, 75080, 76371, 24401, 59448, 37393, 33853, 92859, 71806, 57184, 5120, 93123, 66524, 49239, 72997, 44345, 78011, 81261, 90943, 94231, 241, 38550, 2132, 86408, 84489, 35148, 44128, 6509, 32739, 89240, 38170, 79299, 82341, 87827, 53812, 26419, 4008, 82162, 90492, 39560, 41511, 9374, 81070, 59640, 49457, 10115, 77172, 92012, 95741]
```

JHusk searches for a smaller version that still fails, and reports the minimal case: a 50-element mess shrinks to a single element, `11`, one past the boundary the property actually cares about. Rather than staring down fifty arbitrary numbers trying to spot a pattern, you're looking at exactly the value that matters.

The full failure report also includes the reproduction seed, execution statistics, and the original exception, see `ReadmeExamplesTest.capturesRealShrinkReportForReadme` for the complete, unedited output from a real run.

### Shrinking, Seeds & Reproducibility

Every JHusk run is driven by a seed, a single long value that deterministically controls every random decision the run makes, from which values get generated to how the shrinking search proceeds. Two runs with the same seed against the same property will always produce identical results.

When a property fails, the report includes exactly the seed that produced that failure:

```
Reproduction:
  To reproduce this exact failure, run: check(7L) (Seed: 7L)
```

Calling `check(7L)` on that same property will deterministically walk through the exact same sequence of generated values and land on the exact same failure, every time. This matters enormously for debugging: once you've got the seed, you can set a breakpoint, add logging, or step through in a debugger with total confidence you're looking at the actual failing scenario, not a fresh random run that happens to look similar.

Not passing a seed at all, calling plain `check()`, draws a fresh seed each time, which is what you want for everyday test runs, since it means each run explores a different slice of the input space over time rather than always checking the same fixed set of examples.

### Failure Persistence

Beyond seed-based reproduction within a single debugging session, JHusk also keeps a longer-lived memory of failures across separate runs entirely.

Once JHusk finds a failing input, it saves the byte stream that produced it to a local file, by default under a `.jhusk` directory relative to your project. The next time that property runs, JHusk replays that stored failure first, before generating any new random examples. This means a bug you believed was fixed can't quietly resurface without JHusk catching it immediately on the very next run, since the exact input that broke things before is checked again automatically.

This persistence is written atomically, JHusk writes to a temporary file and then renames it into place, rather than writing directly to the final file. That matters if you're running property tests concurrently across multiple `Property` instances sharing a storage directory: a reader can never observe a torn or partially-written failure file, even under concurrent writes. See the Thread Safety & Concurrency section below for the full detail on what concurrent guarantees do and don't hold here.

If you ever want to discard stored failures, for instance after intentionally changing behavior in a way that makes an old failure no longer relevant, deleting the relevant file, or the whole `.jhusk` directory, clears that history. JHusk will simply start generating fresh examples again on the next run.

## Configuring Property Tests

Beyond the default behavior, JHusk's `Property` type exposes a small set of configuration options for cases where the defaults don't fit.

You can give a property an explicit name, which is useful for making failure-storage identity clear and stable, particularly if you refactor a test method and don't want JHusk to treat the refactored version as an entirely new, unrelated property:

```java
Property.forAll(generator, assertion).named("myCustomPropertyName").check();
```

You can control how many examples are checked in a single run, if the default trial count doesn't suit a particular property, for instance a property you want checked more thoroughly because it covers something especially important, or less thoroughly because each individual check is expensive:

```java
Property.forAll(generator, assertion).examples(500).check();
```

And you can point failure persistence at a custom directory, rather than the default location, which is useful if you want to keep a property's stored failures somewhere specific, or isolate one test class's failure history from another's:

```java
Property.forAll(generator, assertion).withStorageDir(customPath).check();
```

`Property<T>` is a mutable builder, so the pattern is always the same: configure whatever options you need on one thread, then call `check()`. Don't mutate a `Property` instance's configuration concurrently with a `check()` call already in progress, and don't call `check()` concurrently on the same instance from multiple threads. See Thread Safety & Concurrency below for the full picture on what is and isn't safe to do concurrently.

## JUnit 5 Integration

JHusk integrates natively with JUnit 5 through the `@Property` annotation, paired with `@ForAll` on each parameter that should be filled in by a generator.

Properties run alongside regular `@Test` methods in the same test class, appear in the same test reports, and behave like any other JUnit 5 test as far as your build tool and CI setup are concerned. A `@Property` method shows up as a single test in your report, not one entry per generated example, even though internally it's running JHusk's full generate, check, and shrink loop against potentially hundreds of inputs. If any of those inputs falsifies the property, the test fails with the full shrunk report described above, formatted the same way JUnit formats any other assertion failure.

Because this integration builds directly on JUnit's own extension mechanism, `@Property` methods work correctly with everything else JUnit 5 already offers: nested test classes, display names, tags for selectively running subsets of your suite, and your existing CI configuration, all without any special handling.

## Limitations & Important Considerations

A few things are worth knowing before you lean heavily on JHusk in a real project, so that its behavior never comes as a surprise.

`Generators.optionals(Generator<T>)` returns `Generator<T>`, not `Generator<Optional<T>>`. It produces a value that may be a plain Java `null`, rather than wrapping the value in an `Optional`. This is mentioned earlier in this document too, but it's worth repeating here since it's the single most common point of confusion for anyone reaching for this method for the first time.

Generators have an internal size limit. JHusk encodes the values it generates as a stream of bytes, capped at 8KB per generated example, this is the same byte stream referenced throughout the Core Concepts section above. For the overwhelming majority of properties, this limit is completely invisible and never comes up. But if you generate a very large collection, tens of thousands of elements, or a very long string, you can run past that cap before JHusk finishes encoding it, and the run will fail with `PropertyExecutionException` reporting an exhausted budget rather than a normal generated value. This is a known, intentional constraint of the current design, not a bug, and it's covered in more detail in the Design & Architecture section below. If you hit it, the fix is usually to lower the collection's maximum size. The `/adversarial-tests` folder in this repository includes tests that specifically exercise this exact boundary and confirm the behavior is as expected.

JHusk requires Java 17 as a floor. There's no compatibility path for earlier Java versions, since the library relies on language features introduced in that release.

## Troubleshooting & Common Mistakes

A few patterns come up often enough to be worth documenting directly, rather than leaving someone to rediscover them the hard way.

**A property throws `PropertyExecutionException` instead of `AssertionError`.** These two exception types mean different things, and the distinction is deliberate. `AssertionError` means JHusk actually generated a value, ran your assertion against it, and the assertion genuinely failed, a real property falsification. `PropertyExecutionException`, which extends `RuntimeException`, means something prevented JHusk from completing a normal check cycle in the first place: an exhausted invalid-run budget, usually because a filter is too restrictive, or a generator itself throwing during execution. If you're catching exceptions around `check()`, remember that `AssertionError` does not extend `RuntimeException`, so a bare `catch (RuntimeException e)` will not catch a genuine property falsification, only the budget or crash cases.

**A filter seems to hang or take a long time.** This is almost always a filter that's too restrictive for the values the underlying generator tends to produce. If a filter only accepts, say, one value in ten thousand, JHusk has to draw and discard a huge number of candidates before finding one that passes, and eventually gives up with `PropertyExecutionException` rather than looping forever. The fix is usually to restructure the generator so it produces valid values directly, rather than generating broadly and filtering down. For instance, instead of `Generators.integers().filter(n -> n % 2 == 0)`, prefer `Generators.integers().map(n -> n * 2)`, which produces only even numbers to begin with rather than discarding half of everything generated.

**`Generators.optionals()` isn't behaving like an `Optional`.** As covered above, it returns a plain nullable `T`. If your assertion is written assuming an `Optional` wrapper, that's the mismatch to check first.

**Large collections fail with a budget-exhaustion error.** This is very likely the 8KB internal buffer cap described in Limitations & Important Considerations and Design & Architecture. Check whether the collection sizes involved could plausibly exceed that cap given the per-element encoding cost, and lower the maximum size if so.

**A stored failure keeps replaying even after you believe you've fixed the bug.** Failure persistence, described above, replays known failures before generating new examples. If you've genuinely fixed the underlying issue, the replay should now pass rather than fail. If it's still failing unexpectedly, or interfering with an unrelated test, check the `.jhusk` directory for stale entries, particularly after renaming a test method or property, since failure identity is tied to how the property is named and located.

## Thread Safety & Concurrency

`Generator<T>`, including everything `Generators` returns, and anything built from `map`, `filter`, `flatMap`, or `combine`, holds no mutable state of its own and is safe to share and reuse across threads, as long as each thread supplies its own `DataSource`.

`DataSource` itself is not thread-safe and must never be shared across threads. This is rarely something you need to think about directly, `Property.check()` creates a fresh `DataSource` per generated example automatically, and you'd only construct one yourself if writing a custom `Generator` entirely from scratch, bypassing composition.

`Property<T>` is a mutable builder. Configure an instance, `named`, `examples`, `withStorageDir`, and so on, on one thread before calling `check()`. Don't mutate its configuration concurrently with a `check()` call already in progress, and don't call `check()` concurrently on the same instance from multiple threads.

Running `check()` concurrently across different `Property` instances is safe, even when those instances share a failure-storage directory and property identity. `FailureStorage` writes atomically, temp file then rename, so a concurrent read can never observe a torn or partially-written buffer. What's still true is that concurrent writes to the same identity race for which one's failure ends up stored, one atomic rename simply wins over the other, but the file itself is never corrupted by the race. Distinct identities or distinct storage directories are entirely unaffected either way.

## JHusk vs Other Property-Based Testing Libraries

Within Java, JHusk sits alongside a handful of established options, each with a different design philosophy.

jqwik is the most established property-based testing library on the JVM today, built directly on JUnit 5. It uses integrated, tree-based shrinking, where each generator carries its own logic for shrinking itself. It's a mature, carefully implemented library, and the difference between it and JHusk is architectural rather than a claim that one is categorically better than the other.

QuickTheories is a lighter-weight JVM library that supports both shrinking and targeted, coverage-guided search for failures.

junit-quickcheck is one of the earliest Java property-based testing tools, built on JUnit 4's theories mechanism. It added shrinking support in a later release, though JUnit 4 itself has since moved into maintenance mode.

Beyond Java, the same underlying idea shows up across most major language ecosystems: Hypothesis in Python, the library that popularized modern property-based testing outside the Haskell world, and the direct design inspiration for JHusk's shrinking approach; QuickCheck in Haskell, the original property-based testing library, created by Koen Claessen and John Hughes in 2000, and the ancestor of everything else in this space; Hedgehog, a later Haskell alternative with its own take on integrated shrinking; fast-check for JavaScript and TypeScript; PropEr for Erlang; test.check for Clojure; ScalaCheck for Scala; and RapidCheck for C++.

The specific difference between JHusk and jqwik is worth spelling out in more depth, since they're the two most directly comparable options on the JVM. jqwik's integrated shrinking means shrinking quality depends partly on how well each individual generator's shrinking logic was written, including any custom generator a user writes themselves. JHusk's internal shrinking works generically, once, over the underlying byte stream shared by every generator, so a custom generator built through composition inherits high-quality shrinking automatically, without its author writing any shrinking logic at all.

jqwik and QuickTheories already answer the question of whether Java has property-based testing. JHusk is aimed at a narrower, more specific question: whether Java has property-based testing with Hypothesis-grade shrinking. As far as this library's author is aware, nothing else on the JVM has answered that second question yet.

## Design & Architecture

Underneath JHusk's public API, every generator is ultimately an interpreter over a stream of bytes supplied by a `DataSource`. Rather than each generator type implementing its own randomness and its own shrinking behavior, generation and shrinking both operate on that shared byte stream, which is what allows a single general-purpose shrinker to work correctly across every generator, custom or built-in, without any generator author needing to implement shrinking logic themselves.

A handful of specific encoding choices shape how this plays out in practice, and understanding them explains some of the behavior documented elsewhere in this README.

Bounded integer ranges are encoded through multiplicative scaling, computed as `(raw * range) >>> 32`, rather than a naive modulo operation. Modulo was tried first and found to produce non-monotonic shrinking behavior, where a byte-stream value that should shrink toward a smaller output didn't reliably do so. Multiplicative scaling avoids that problem.

Collections are encoded using a continuation-flag scheme rather than a length-prefix scheme. Each element is preceded by a flag indicating whether another element follows. This matters specifically because it allows the shrinker to delete a span of bytes corresponding to one element cleanly, without needing to separately patch up a length value stored elsewhere in the stream. It's also the reason very large collections carry a real, though usually invisible, byte cost: every element pays for its own continuation flag, and that cost compounds across the full collection.

The `DataSource` that backs all of this holds a fixed internal buffer, capped at 8KB. Most properties never come close to this limit. But for a generator asked to produce, say, tens of thousands of elements, especially where each element itself has non-trivial encoding cost, the cumulative byte cost of continuation flags and element data can exceed that cap before generation completes. When that happens, the run reports `PropertyExecutionException` for an exhausted invalid-run budget, rather than silently producing a truncated or incorrect value. This is a deliberate design tradeoff: the cap keeps the byte stream, and therefore the shrinking search space, bounded and fast, at the cost of an explicit ceiling on how large a single generated value can be. See Limitations & Important Considerations and Troubleshooting & Common Mistakes above for what this means practically if you hit it.

Shrink targets are chosen deliberately rather than defaulting to zero everywhere. Integers shrink toward zero, booleans shrink toward `false`, collections shrink toward empty, but bounded integer ranges shrink toward their minimum value, not toward zero. This matters for a range like `integers(50, 100)`, where zero isn't even a valid value in the first place, shrinking toward the range's actual minimum produces a sensible, in-range minimal failure instead of an impossible one.

Failure persistence is written using a write-to-temporary-file-then-atomic-rename pattern, rather than writing directly to the final file path. Naive direct writes were found, during this project's own testing, to produce torn reads under concurrent load, a genuine bug that's now fixed through this atomic approach. This is also what underlies the concurrency guarantees described in Thread Safety & Concurrency above.

`Property.check()` distinguishes two categories of failure through its exception hierarchy. `PropertyExecutionException`, which extends `RuntimeException`, covers cases where a normal check cycle couldn't complete, exhausted budgets or generator crashes. `AssertionError` is reserved for genuine property falsification, mirroring JUnit's own convention where `AssertionFailedError` extends `AssertionError`. This split exists because `AssertionError` does not extend `RuntimeException`, so code that wraps `check()` in a `catch (RuntimeException e)` needs `PropertyExecutionException` to actually be catchable that way, while still letting a real, assertion-driven test failure propagate as a true `AssertionError`, exactly as JUnit itself expects.

## How JHusk Is Tested

Most of JHusk's own test suite was written by the same person who built the library, which means it can share the same blind spots as the implementation itself. To get a second, more skeptical perspective, an independent adversarial test suite was written separately, working only against JHusk's public API as an external Maven dependency, with no knowledge of the internals.

That suite lives in the `/adversarial-tests` folder of this repository and covers eight categories: boundary values, deeply nested composite generators, filters and generators that are impossible to satisfy, deliberately planted bugs, used to confirm shrinking actually finds and reports real failures rather than missing them, determinism and thread safety under concurrent use, large-scale stress cases, generic type inference across chained generators, and unusual or malformed API usage.

It's worth being upfront about what that process actually found, rather than only reporting a final pass count.

Real bugs it caught: `Property.check()` originally threw plain `AssertionError` for every kind of failure, including invalid-budget exhaustion and generator crashes. Because `AssertionError` doesn't extend `RuntimeException`, code wrapping `check()` in `catch (Exception e)` couldn't catch it. This is now fixed, as described in Design & Architecture above: `PropertyExecutionException` extends `RuntimeException` and covers budget and crash failures, while `AssertionError` is reserved for genuine property falsification.

Issues that turned out to be in the test suite itself, not JHusk: an apparent infinite loop traced back to a bug in the test's own planted binary search helper, not JHusk's shrinking logic. A few early failures came from incorrect assumptions baked into the tests themselves, wrong expected exception types, the `optionals()` misunderstanding mentioned throughout this document, and generators configured with overly strict exact sizes that were, in practice, nearly impossible to satisfy.

A known, intentional design limit rather than a defect: the 8KB buffer cap described in Design & Architecture above. Several of the stress tests in `/adversarial-tests` generate very large collections specifically to exercise this limit, and assert that JHusk correctly reports `PropertyExecutionException` in that scenario, rather than silently succeeding, hanging, or producing an incorrect result.

The `/adversarial-tests` folder includes its own README with the full breakdown of every category, along with instructions for running the suite yourself against a locally installed or published copy of JHusk.

## License

JHusk is released under the license included in this repository's `LICENSE.md` file. See that file for the complete license text.

## Acknowledgments

JHusk's shrinking design is a direct descendant of Hypothesis, created by David R. MacIver, and its internal engine, Conjecture. The choice to treat every generator as an interpreter over a shared byte stream, rather than giving each generator its own independent shrinking logic, comes directly from that project's approach, and this library exists largely as an attempt to bring that same idea to the JVM.

Property-based testing itself traces back to QuickCheck, created by Koen Claessen and John Hughes for Haskell in 2000. Both Hypothesis and QuickCheck did the hard conceptual work this library builds on, and neither JHusk's design nor its documentation would have been possible without them.

Thanks are also due to the broader lineage of property-based testing tools across the Java ecosystem and beyond, jqwik, QuickTheories, junit-quickcheck, Hedgehog, fast-check, PropEr, test.check, ScalaCheck, and RapidCheck among them, for continuing to demonstrate, each in their own language and their own way, that this style of testing is worth the investment.
