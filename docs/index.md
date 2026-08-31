# JHusk

Husk is a property-based testing library for Java. Its formal, Java-specific name is JHusk, and that's the name you'll see in the Maven coordinates, the package structure, and anywhere the library needs to be referenced precisely. Husk and JHusk refer to the same library throughout these docs.

This library is for Java developers writing unit tests who want their tests to check more than the three or four examples they happened to think of. If you're testing anything with a rule that should hold across a range of inputs, a sorting function, a parser, a serializer, a custom data structure, a numeric algorithm, JHusk generates a wide spread of inputs, including the ones you wouldn't think to write by hand, and checks that rule against every one of them.

JHusk was built by a single developer as a way of bringing Hypothesis-style, internally-shrunk property-based testing to the JVM, an approach that, as far as this project's author is aware, no existing Java library takes in quite the same way.

## Why property-based testing?

A normal unit test picks its own examples: `assertEquals(5, add(2, 3))`. The problem is that a person can only think of so many examples, and bugs tend to hide in the ones nobody thought to write down. Empty collections. Negative numbers. Duplicate entries. Boundary values. Strings with unusual Unicode in them.

Property-based testing inverts this. Instead of picking examples, you describe a rule that should always be true, "sorting a list should never change its length," "decoding an encoded value should return the original value," and the library generates hundreds or thousands of inputs on its own, actively trying to find one that breaks the rule.

Finding a failure is only half the job. A failing case can be a forty-element list full of arbitrary numbers, and staring at forty arbitrary numbers doesn't tell you much. So JHusk shrinks it: it keeps searching for smaller, simpler inputs that still trigger the same failure, until it can't reduce the input any further. A forty-element mess might shrink down to two elements, and the actual cause becomes obvious.

## What makes JHusk different

Most property-based testing libraries on the JVM use what's called integrated shrinking. Each generator carries its own knowledge of how to produce a smaller version of whatever it generates. This works, and it's implemented carefully in libraries like jqwik, but it means shrinking quality depends on how well each individual generator's shrinking logic was written, including any custom generator a user writes themselves.

JHusk takes a different approach, the same family of approach Hypothesis uses in Python. Instead of generators owning their own shrinking logic, JHusk treats every generator as an interpreter over a stream of random bytes. A value is really just an interpretation of that byte stream. Shrinking works by minimizing the byte stream directly, once, generically, and then re-running every generator's interpretation logic on the shrunk bytes to get a smaller value.

The practical consequence: a custom generator, built purely through composition with `map`, `filter`, `flatMap`, and `combine`, inherits high-quality shrinking automatically. Its author never has to write any shrinking logic at all. See [Architecture](design/architecture.md) for the full detail on how this works.

## A first look

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

## Where to go next

- [Installation](installation.md) — add JHusk to a Maven or Gradle project
- [Quickstart](quickstart.md) — the shortest path from nothing to a passing property test
- [Guide: Generators](guide/generators.md) — building custom generators through composition
- [Guide: Properties](guide/properties.md) — configuring examples, timeouts, and generation budgets
- [Guide: Thread Safety & Concurrency](guide/thread-safety.md) — what's safe to share across threads, and what isn't
- [Guide: JUnit Integration](guide/junit-integration.md) — `@Property` and `@ForAll` in depth
- [Guide: Understanding Failures](guide/failures.md) — shrinking, seeds, and failure persistence
- [Architecture](design/architecture.md) — the byte-stream design underneath everything
- [Comparisons](design/comparisons.md) — JHusk next to jqwik, QuickTheories, and others
- [Troubleshooting](troubleshooting.md) — common mistakes and how to read the error you're seeing

JHusk isn't a replacement for your existing unit tests. It's a complement: use `@Test` for the specific examples and edge cases you already know matter, and `@Property` for the broader rules you want checked automatically across everything else.
