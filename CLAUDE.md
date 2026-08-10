# CLAUDE.md — JHusk Project Context

## What this is
JHusk: a property-based testing library for Java. Core bet: Hypothesis-style internal
byte-stream shrinking (Conjecture), not tree-based shrinking like jqwik. A generator is
an interpreter over a stream of bytes; shrinking minimizes the byte buffer directly, then
re-interprets it — every generator, including user-written ones, gets shrinking for free.

Current released version: **1.0.1**. Now building toward **1.1.0**.

## Locked architecture — do not restructure without explicit discussion
- `Generator<T>` — functional interface, `T generate(DataSource)`. `map`/`filter`/`flatMap`
  are default methods on it.
- `DataSource` (`.internal`) — byte source, two modes: generation (PRNG-backed, records
  every byte) and replay (reads a supplied buffer). Three-valued status: `VALID` /
  `INVALID` / `OVERRUN`. Not thread-safe; one instance per single-threaded run.
- `Span` (`.internal`) — start/end byte-range tree over the buffer, built via
  `startSpan()`/`endSpan()`. This is what lets the shrinker delete semantically coherent
  regions (one list element, one field) instead of arbitrary byte ranges.
- `Shrinker` + `ShrinkHarness` (`.internal`) — shrink passes (span deletion, zeroing,
  block lowering, block dedup, list normalization) operate only through
  `ShrinkHarness.tryBuffer()`. No pass replays a buffer independently.
- `Property` — builder/runner (`io.github.nikhilvirdi.jhusk.Property`). Note this is a
  **different class** from `io.github.nikhilvirdi.jhusk.junit.Property` (the `@Property`
  annotation) — same simple name, different package, deliberate, do not "fix."
- `FailureStorage` — public class, atomic write-then-rename to `.jhusk/`.

## Core invariant
Every encoding must satisfy: lexicographically smaller byte buffer ⇒ simpler value
(shrink-monotonicity). This is stated explicitly in `Generator`'s Javadoc and must hold
for any new generator or encoding added.

## Package layout
- `io.github.nikhilvirdi.jhusk` — public API (`Generator`, `Generators`, `Property`,
  exceptions, `FailureStorage`)
- `io.github.nikhilvirdi.jhusk.internal` — `DataSource`, `Span`, `Shrinker`, `ShrinkHarness`
- `io.github.nikhilvirdi.jhusk.junit` — `@Property`, `@ForAll`, `PropertyExtension`

## Build / verify
- `mvn clean test` — must pass with 0 failures, 0 errors before any change is considered done
- JDK 17 minimum (`--release 17`), tested against 17/21/25 in CI
- Never trust a self-reported "done" — verify every change with `git diff` and a full
  `mvn clean test` run, not just the new/changed test class

## Working method
- Architect/mentor mode: Claude (this chat) writes prompts for Claude Code to execute;
  Claude does not write JHusk source directly in this chat.
- V reviews and runs every Claude Code prompt manually; nothing is auto-applied.
- Every new feature must state, up front, which existing file(s) it touches and confirm
  it doesn't violate the shrink-monotonicity invariant above.

## Historical docs
`deprecated/BUGS.md` and `deprecated/WORKPLAN.md` are historical only — v1.0.0/v1.0.1
planning artifacts, no longer the active roadmap. Do not treat their "locked scope" or
"excluded" sections as current constraints (notably: stateful testing was excluded there
and is now in scope for 1.1.0).

## Current roadmap
See `v1.1.0.md` for the actual scope and feature list of the in-progress release.