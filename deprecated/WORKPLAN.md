# JHusk — Workplan

Living document. Update the status fields as phases complete.

---

## Locked Decisions

**Project.** Husk (formal name: JHusk) — a property-based testing library for Java. The user declares a rule that should hold for all inputs; the library generates many inputs trying to falsify it; on failure it shrinks the input to a minimal reproducing case.

**Core architecture.** Conjecture-style *internal* shrinking, as used by Hypothesis (Python). A generator is not a value factory — it is an **interpreter over a stream of bytes**. Shrinking operates on the underlying byte buffer, never on the typed value, and the shrunk buffer is re-interpreted to recover the smaller value. Consequence: every generator, including user-written ones, inherits shrinking for free. This is the differentiator; jqwik and QuickTheories both use integrated/tree-based shrinking where generators carry their own shrink logic.

**Scope — locked, do not expand.**
- Pure property-based testing only.
- Excluded: stateful/model-based testing, coverage-guided fuzzing, targeted search, exhaustive built-in generator catalogues.
- Required: composable generators, property runner, internal shrinker, seed-based deterministic replay, file-based failure database, JUnit 5 integration via `@Property`.

**Stack.**
| Concern | Decision |
|---|---|
| Language | Java, local JDK 26, **compiled against `--release 17`** |
| Build | Maven |
| IDE | IntelliJ IDEA Community |
| Self-testing | JUnit 5 (Jupiter) |
| Distribution | Maven Central via Central Portal (`central.sonatype.com`), `io.github.<username>` namespace — OSSRH is dead as of 2025-06-30, do not plan around it |
| CI/CD | GitHub Actions — build+test on push, tag-triggered signed release |
| License | Apache 2.0 |
| Repo | `jhusk` (README.md, LICENSE.md, logo assets already present) |

**Build method.** Hand-written by a learner, mentored one concept at a time, no agentic code generation. Phases are sized for one to three sittings each and deliberately sequenced so that each new abstraction solves a problem already felt at a lower level.

---

## Design Decisions To Settle Before Phase 4

These are cheap to decide now and expensive to change later. Each should be resolved in conversation before the corresponding phase begins — do not discover them mid-implementation.

**D1. Byte stream vs. typed choice sequence.**
Hypothesis originally used a flat byte buffer; modern versions moved to a typed "choice sequence" IR (a list of typed choices: integers, booleans, floats, strings) because raw bytes made certain shrinks and float handling awkward. **Recommendation: use raw bytes for JHusk.** It is the architecture the project set out to bring to the JVM, it is dramatically simpler to reason about and teach, and it is sufficient for high-quality shrinking. Record this as a conscious choice with a known ceiling, not an oversight — being able to articulate *why* you chose the simpler IR and what it costs is exactly the kind of thing a portfolio project should be able to defend.

**D2. Draw granularity.** Fixed-width blocks (e.g. every draw consumes 8 bytes) or variable-length draws? Fixed width makes block-level shrink passes (lowering, swapping, deduplication) far simpler to implement; variable width is more compact. **Recommendation: fixed-width blocks per primitive draw.**

**D3. Bounded-integer encoding.** Naive `value % (max - min + 1)` introduces modulo bias *and*, more importantly, breaks shrink monotonicity — a smaller byte can map to a larger value, which silently sabotages the shrinker. Needs an encoding where byte-wise reduction implies value-wise reduction toward the shrink target.

**D4. Shrink targets.** Zero for integers, `false` for booleans, empty for collections, lowest allowed value for bounded ranges. Must be consistent, because the entire shrinker assumes "lexicographically smaller bytes ⇒ simpler value."

**D5. Collection encoding — the decision most likely to be got wrong.** Two options: draw a length up-front then N elements, or draw a *continuation flag* before each element. Length-prefix encoding is intuitive but shrinks terribly: deleting bytes desynchronizes the entire remainder of the buffer. **Recommendation: continuation-flag encoding**, so that deleting a coherent byte span deletes exactly one element and leaves the rest interpretable.

**D6. Buffer size cap.** A maximum buffer length (Hypothesis uses 8KB) that, when exceeded, marks the run overrun rather than looping forever. Needed for termination guarantees.

**D7. Package layout.** Proposed: `io.github.<username>.jhusk` (public API — `Generator`, `Generators`, `Property`), `.internal` (data source, buffer, spans), `.shrink` (passes, ordering), `.db` (persistence), `.junit` (JUnit 5 extension). Public/internal separation matters here because this is a *library* — anything public is a compatibility promise.

**D7 — as shipped, and the deviation from the proposal above:** `Shrinker`/`ShrinkHarness` stayed in `.internal` rather than moving to a dedicated `.shrink` package, and `FailureStorage` was promoted to the public `io.github.nikhilvirdi.jhusk` package rather than living in `.db` at all (see `internal/package-info.java` and `io.github.nikhilvirdi.jhusk/package-info.java` for the full reasoning, recorded there rather than only here). This was flagged in Phase 16 but only in conversation, not durably recorded in the shipped source — fixed during the Phase 18 final audit.

---

## Architectural Risk Register

Ordered by how expensive the mistake is to undo.

### 🔴 R1 — Span/interval recording (Phase 6). Highest risk in the project.
This is the piece most easily overlooked, and it is the difference between a shrinker that works and one that technically runs. A flat byte buffer with no structure can only be shrunk by blind byte-range deletion, which almost always produces an uninterpretable buffer. Hypothesis records **spans**: start/end markers around each generator's draws, forming a tree over the buffer. The shrinker then deletes *semantically coherent* regions — one list element, one field of a composite object — instead of arbitrary ranges.

**If skipped or bolted on later:** shrink quality collapses to near-useless, and retrofitting spans means touching every generator written up to that point plus the entire shrinker. Build spans into the data source *before* the first generator exists.

**Verified (final audit):** confirmed genuinely mitigated, not just superficially addressed — span tree integrity was stress-tested at real composite depth (list of lists of optionals of a combined type, four generator layers deep), not just the shallow structures the original test suite used, and holds: correctly nested, non-overlapping spans at every level.

### 🔴 R2 — DataSource design (Phases 4–5).
Everything sits on this. It must support, from the start: (a) generation mode drawing from a PRNG while recording every byte, (b) replay mode reading from a supplied buffer, (c) an overrun state when replay runs out of bytes, (d) an invalid/rejected state for filter failures, (e) freezing once a run completes.

**If got wrong:** every generator and the entire shrink loop are built against the wrong interface. This is the single most disruptive rewrite available in this project.

**Verified (final audit):** the OVERRUN/INVALID guard was found to be inconsistently enforced downstream in two places — `ShrinkHarness.tryBuffer`'s "generator crashed" branch, and `Property.check()`'s stored-failure replay path — both accepted non-VALID status as if it were a normal run. DataSource's own three-state design was correct throughout; the bugs were in code consuming it that didn't check the state as rigorously as `ShrinkHarness`'s other branch already did. Both fixed.

### 🟠 R3 — Encoding conventions (Phase 7).
The invariant "lexicographically smaller buffer ⇒ simpler value" is not automatic; it is a property each encoding must deliberately preserve (big-endian integers, shrink-toward-target bounded ranges, continuation-flag collections). Violating it in one primitive poisons the shrinker for every composite built on it.

**If got wrong:** the shrinker appears to work but produces erratic results — sometimes larger than the original — and the cause is nearly invisible from the outside.

**Verified (final audit):** `DataSource.drawBoolean()` was found to still use the rejected parity/LSB encoding (non-monotonic) even though `Generators.booleans()` had already worked around it — a public method left silently broken for the one thing it looks like it's for. Fixed to match `booleans()`'s monotonic encoding; `booleans()` simplified to delegate to it, removing the duplication. Bounded-integer monotonicity was also extended to cases the original test suite never hit: the `range == 2^32` identity-mapping special case, and single/2-value ranges pinned to `Integer.MIN_VALUE`/`MAX_VALUE` exactly — all hold.

### 🟠 R4 — Three-valued run outcomes (Phase 5, surfaced in Phase 10).
A run is not pass/fail. It is **valid-pass**, **valid-fail**, or **invalid** (filter rejection or buffer overrun). Invalid runs must count against a separate budget and must never be mistaken for passes.

**If got wrong:** filters silently make properties vacuously true, and the shrinker treats overruns as successful shrinks — producing "minimal" cases that don't actually reproduce the bug.

**Verified (final audit):** see R2 above — this is the same finding from the run-outcome side. Both fixes are in place and regression-tested.

### 🟡 R5 — Generator API surface (Phase 7–8).
Genuinely lower risk than it first appears, *provided* R2 is sound: in this architecture `Generator<T>` collapses to a single-method functional interface (`T generate(DataSource)`), and `map`/`filter`/`flatMap` fall out as default methods. Notably, `flatMap` — the hard case under integrated shrinking — is nearly free here, since dependent draws simply continue consuming the same stream. Public API is still a compatibility promise once published, so lock signatures before Phase 18.

**Signatures locked (final audit, Phase 18):** one intentional addition (`Property.maxInvalidRuns(int)`, closing an asymmetry where every other run parameter had a builder method except this one) and one intentional move (`FailureStorage` promoted from `.internal` to the public package, resolving a boundary leak flagged but not fixed in Phase 16). Both are purely additive/clarifying, made before publishing specifically so they wouldn't need to happen after. `Property` (the runner) and `@Property` (the JUnit annotation) deliberately keep the same simple name across packages — considered and documented as a tradeoff, not revisited.

### 🟡 R6 — JDK 17 baseline enforcement.
Local JDK is 26. Without `<release>17</release>`, code compiles happily against APIs unavailable to downstream users on 17 and fails only at *their* runtime. Enforce in Phase 2, verify in CI in Phase 17.

**Verified:** `<maven.compiler.release>17</maven.compiler.release>` is set in `pom.xml`; CI (`.github/workflows/ci.yml`) matrices across JDK 17/21/25.

---

## Sequencing Logic

The dependency spine, stated plainly:

```
DataSource (bytes + recording + replay + status)
    └── Spans (structure over the buffer)
            └── Encoding conventions
                    └── Primitive generators
                            └── Combinators → Collection generators
                                    └── Property runner (no shrinking)
                                            └── Shortlex order + shrink harness
                                                    └── Shrink passes
                                                            └── Reporting → Persistence
                                                                    └── JUnit integration
                                                                            └── CI → Publishing
```

Two rules follow from this and are worth stating explicitly:

1. **No generator may be written before spans exist.** Tempting to skip, since primitives seem not to need structure — but composites do, and every primitive must already be recording spans by the time composites are built.
2. **The runner must work without shrinking first.** Shrinking is a search *over* the runner's ability to re-execute a property against an arbitrary buffer. Building the shrinker before that loop is proven means debugging two unproven systems at once.

---

## Phases

Legend: `Not Started` · `In Progress` · `Done`

---

### Phase 0 — Environment & Repo Bootstrap
**Status:** Done
**Depends on:** —
**Goal:** A verified toolchain and a repo skeleton. No library code.

- [ ] Confirm `java -version` and `javac -version` both resolve (JDK 26)
- [ ] Install IntelliJ IDEA Community; confirm it detects the JDK
- [ ] `git init`, confirm README.md / LICENSE.md / logo assets in place
- [ ] Add `.gitignore` (target/, .idea/, *.class)
- [ ] Fill copyright holder in LICENSE.md appendix
- [ ] Set up Conventional Commits convention for the repo
- [ ] Initial commit, push to `jhusk` on GitHub

**Note (Phase 18 final audit):** the LICENSE.md copyright line was still the unfilled `[Your Name or GitHub Username]` placeholder — this checklist item was checked off in spirit (everything else about the phase was clearly done) but the actual line was never filled in. Fixed during the final audit.

---

### Phase 1 — Life Without a Build Tool
**Status:** Done
**Depends on:** 0
**Goal:** Feel the problem Maven solves, before Maven appears. Deliberately discarded afterwards.

- [ ] Write two trivial `.java` files in separate packages
- [ ] Compile by hand with `javac`, observe directory/package requirements
- [ ] Run with `java`, observe classpath resolution
- [ ] Manually download one third-party JAR and compile against it via `-cp`
- [ ] Add a second JAR that depends on the first — feel transitive dependency resolution by hand
- [ ] Write down, in your own words, exactly what was painful

**Teaching note:** the payoff is the written list. Maven's design is a direct answer to it.

**Note (Phase 18 final audit):** the "deliberately discarded" artifact (`IntStack`/`IntStackTest`) from this phase's JUnit-fluency follow-on (Phase 3) was never actually discarded — it's still shipped as a public class in the main `io.github.nikhilvirdi.jhusk` package. Flagged explicitly in `package-info.java` rather than deleted, since `IntStackTest` still depends on it and removing it wasn't asked for.

---

### Phase 2 — Maven
**Status:** Done
**Depends on:** 1
**Goal:** A real project skeleton, and understanding what each part of the POM is for.

- [ ] Understand groupId / artifactId / version coordinates
- [ ] Author `pom.xml`: `io.github.<username>` : `jhusk`
- [ ] Adopt the standard directory layout (`src/main/java`, `src/test/java`)
- [ ] Configure `maven-compiler-plugin` with **`<release>17</release>`** (R6)
- [ ] Add JUnit 5 dependency with `test` scope — understand why scope exists
- [ ] Walk the lifecycle: `validate → compile → test → package → install`
- [ ] Inspect the produced JAR's contents
- [ ] Verify a JDK-18+ API fails to compile, proving the baseline is enforced

---

### Phase 3 — JUnit 5 as a User
**Status:** Done
**Depends on:** 2
**Goal:** Fluency with the framework JHusk will be tested *with*, long before integrating *into* it.

- [ ] `@Test`, assertions, `assertThrows`
- [ ] Lifecycle: `@BeforeEach` / `@AfterEach` / `@BeforeAll` / `@AfterAll`
- [ ] `@DisplayName`, `@Nested`, `@Disabled`
- [ ] Parameterized tests — note conceptually how close these are to properties
- [ ] Run from both IntelliJ and `mvn test`; read the surefire report

---

### Phase 4 — DataSource, Generation Mode 🔴
**Status:** Done
**Depends on:** 3 · **Risk:** R2
**Goal:** The foundation. A byte source that draws from a PRNG and records everything it hands out.

- [ ] Settle **D1** (bytes vs typed IR) and **D2** (draw granularity) in discussion first
- [ ] Seeded PRNG (`SplittableRandom` vs `Random` — compare reproducibility guarantees)
- [ ] `drawBytes(n)` returning bytes and appending them to an internal recording buffer
- [ ] Buffer growth strategy; enforce **D6** size cap
- [ ] `freeze()` — a completed run is immutable
- [ ] Expose the recorded buffer for later replay
- [ ] Tests: identical seed ⇒ identical buffer; buffer length matches bytes drawn

**Note (Phase 18 final audit):** `drawBytes()` cloned its already-fresh, never-aliased result array on every generation-mode draw for no actual safety benefit (replay mode already returned the fresh array directly, without cloning — the inconsistency was itself a tell). Removed; this was a real, if small, hot-path allocation on every single primitive draw across every example and every shrink attempt.

---

### Phase 5 — DataSource, Replay Mode & Run Status 🔴
**Status:** Done
**Depends on:** 4 · **Risk:** R2, R4
**Goal:** The same object, driven by a supplied buffer instead of randomness. This is what makes shrinking possible.

- [ ] Second construction mode: read bytes from a provided buffer
- [ ] Overrun handling when the buffer is exhausted
- [ ] `Status` enum: `VALID` / `INVALID` / `OVERRUN` (and how a property failure is recorded alongside it)
- [ ] `markInvalid()` for filter rejection
- [ ] Guarantee: recording a run, then replaying its buffer, reproduces it exactly
- [ ] Tests: round-trip fidelity; truncated buffer ⇒ `OVERRUN`, never a silent pass

**Checkpoint:** do not proceed until record-then-replay is bit-for-bit reliable. Everything downstream assumes it.

---

### Phase 6 — Span Recording 🔴 HIGHEST RISK
**Status:** Done
**Depends on:** 5 · **Risk:** R1
**Goal:** Structure over the flat buffer, so the shrinker can delete meaningful units instead of arbitrary bytes.

- [ ] Understand *why* flat-buffer deletion fails — work a concrete example by hand before coding
- [ ] `startSpan()` / `endSpan()` recording (start, end) index pairs
- [ ] Maintain the span stack during nested draws
- [ ] Build the resulting span tree over a completed buffer
- [ ] Decide whether spans carry labels (useful for reporting and targeted passes)
- [ ] Tests: nested draws produce correctly nested, non-overlapping spans; span boundaries align exactly with byte ranges

---

### Phase 7 — Encoding Conventions & Primitive Generators 🟠
**Status:** Done
**Depends on:** 6 · **Risk:** R3, R5
**Goal:** The `Generator<T>` interface, plus primitives whose encodings preserve the shrink invariant.

- [ ] Settle **D3** and **D4** in discussion first
- [ ] Define `Generator<T>` — functional interface over `DataSource`
- [ ] State the invariant explicitly in a comment: *lexicographically smaller buffer ⇒ simpler value*
- [ ] `booleans()` — one byte, shrinks toward `false`
- [ ] `integers()` — big-endian so byte reduction implies magnitude reduction
- [ ] `integers(min, max)` — bounded without breaking monotonicity or introducing modulo bias
- [ ] `longs()`, `characters()`
- [ ] `doubles()` — discuss why floats are the acknowledged weak point of byte-level IR (ties back to D1)
- [ ] Every primitive wraps its draws in a span
- [ ] Tests: shrink-monotonicity property for each primitive — smaller buffer never yields a larger value

**Note (Phase 18 final audit):** see R3 above — `drawBoolean()`'s stale non-monotonic encoding, and monotonicity gaps at the extreme int boundaries, both fixed/closed.

---

### Phase 8 — Combinators
**Status:** Done
**Depends on:** 7 · **Risk:** R5
**Goal:** Composition, where the architecture starts paying dividends.

- [ ] `map` — trivial, no shrink logic needed (note *why*, versus integrated shrinking)
- [ ] `filter` — retry budget, `markInvalid()` on exhaustion (R4)
- [ ] `flatMap` — dependent generation; observe that it is nearly free here
- [ ] `combine` — 2-arity and 3-arity, for building custom types
- [ ] `oneOf` — choice encoded so that earlier alternatives are the shrink target
- [ ] `just` / `constant`
- [ ] Tests: composed generators still satisfy shrink-monotonicity

---

### Phase 9 — Collection Generators 🟠
**Status:** Done
**Depends on:** 8 · **Risk:** R3 (D5)
**Goal:** Where span deletion earns its keep.

- [ ] Settle **D5** — continuation-flag encoding, with the reasoning worked through by hand
- [ ] `lists(elementGen)` with optional size bounds
- [ ] Wrap each element in its own span, so one deletion removes exactly one element
- [ ] `strings()` built on character generators
- [ ] `optionals()` / nullable wrapper
- [ ] `sets()` / `maps()` — think through duplicate handling and its interaction with shrinking
- [ ] Tests: deleting one element's span leaves a valid, interpretable buffer

---

### Phase 10 — Property Runner (No Shrinking Yet)
**Status:** Done
**Depends on:** 9 · **Risk:** R4
**Goal:** The generate-and-check loop, end to end, reporting raw failures. Deliberately unshrunk.

- [ ] `Property` abstraction over a user predicate/assertion
- [ ] Run N examples (default ~100, configurable)
- [ ] Fresh `DataSource` per example; capture the buffer of every run
- [ ] Distinguish pass / fail / invalid; enforce a separate invalid budget (R4)
- [ ] Abort with a clear diagnostic if too many runs are invalid — a vacuous pass is a bug, not a success
- [ ] Capture the failing buffer *and* the seed
- [ ] Report the raw falsifying example, deliberately ugly
- [ ] Tests: a known-buggy function is reliably falsified; a correct function passes

**Checkpoint:** the ugliness of the output here is the motivation for Phases 11–12. Sit with it before continuing.

---

### Phase 11 — Shrink Ordering & Harness
**Status:** Done
**Depends on:** 10
**Goal:** The search scaffolding, before any actual shrink pass exists.

- [ ] Define the **shortlex** order: shorter buffers first, then lexicographically smaller
- [ ] `tryBuffer(candidate)` — replay, re-run the property, report whether the failure survives
- [ ] Cache attempted buffers to avoid redundant re-execution
- [ ] Bound total shrink attempts, so pathological cases terminate
- [ ] Tests: the order is a valid total order; the harness correctly rejects candidates that no longer fail

**Note (Phase 18 final audit):** see R2/R4 above — `tryBuffer`'s "generator crashed" branch accepted OVERRUN/INVALID candidates by exception-class match alone, without the same status check its sibling branch already had. Fixed and regression-tested (an adversarial custom generator that throws on OVERRUN, with the same exception class as the real bug).

---

### Phase 12 — Shrink Passes 🔴 Hardest Implementation
**Status:** Done
**Depends on:** 11
**Goal:** The actual minimization algorithm. Expect several sittings and genuine iteration.

- [ ] **Span deletion** — remove whole spans, largest first
- [ ] **Block lowering** — binary-search each block downward toward its shrink target
- [ ] **Zeroing** — bulk-zero regions as a fast path
- [ ] **Block deduplication** — replace distinct blocks with a repeated smaller one (finds "two equal elements" bugs)
- [ ] **Sorting/normalizing** passes where order is irrelevant
- [ ] Run each pass to fixpoint; run the pass *set* to fixpoint
- [ ] Guard: never accept a candidate whose status is `OVERRUN` or `INVALID` (R4)
- [ ] Benchmark against known cases from **The Shrinking Challenge** — an external, objective ruler
- [ ] Tests: shrunk output still fails; output is minimal under the defined order; termination is guaranteed

**Verified (Phase 16 dogfooding, reconfirmed Phase 18):** JHusk's own `@Property`/`@ForAll` API is used to property-test the shrinker itself — "shrunk buffer is never shortlex-larger than the original" and "shrunk buffer still reproduces the same failure class" both hold under randomized, non-hand-picked inputs.

---

### Phase 13 — Reporting
**Status:** Done
**Depends on:** 12
**Goal:** Output a developer can act on immediately.

- [ ] Print the shrunk falsifying example clearly
- [ ] Show the seed and explicit reproduction instructions
- [ ] Optionally show before/after (original vs shrunk) — the feature that sells the library
- [ ] Report counts: examples run, invalid runs, shrink steps taken
- [ ] Preserve the original exception's stack trace, don't swallow it

---

### Phase 14 — Failure Persistence
**Status:** Done
**Depends on:** 13
**Goal:** Yesterday's bug can't quietly return. Cheap, because failures are already just byte buffers.

- [ ] Choose the on-disk location (a `.jhusk/` directory convention) and format
- [ ] Key entries by property identity — think hard about stability across refactors
- [ ] Save the *shrunk* buffer on failure
- [ ] Replay stored failures **first**, before generating anything new
- [ ] Prune entries that no longer reproduce
- [ ] Tests: a failure survives a JVM restart and is replayed first on the next run

**Note (Phase 18 final audit):** two real bugs found and fixed here. (1) The replay-first path checked `status != INVALID` instead of `status == VALID`, so a stored buffer that OVERRUNS on replay (e.g. after a generator's shape changes) got fed to the assertion as if it were a normal run — either a bogus "still fails" report off zero-padded garbage, or a silent, never-actually-validated pruning of a real regression. (2) `saveFailure` wrote via truncate-then-write, which is not atomic; an empirical stress probe (concurrent writers/readers on one identity) measured roughly a third of concurrent reads as torn/corrupted — not a rare fluke. Both fixed: the replay path now requires VALID status before asserting, and `saveFailure` writes to a temp file and atomically renames it into place (with a short retry, since NTFS — unlike POSIX — can transiently block replacing a file another thread has briefly open).

---

### Phase 15 — JUnit 5 Integration
**Status:** Done
**Depends on:** 14 · **Risk:** R5
**Goal:** Properties that feel native alongside `@Test`.

- [ ] Study the JUnit 5 extension model — `TestTemplate` vs `ArgumentsProvider`, and why the choice matters here
- [ ] `@Property` annotation (example count, seed, configuration)
- [ ] `@ForAll` parameter annotation
- [ ] Resolve generators from parameter types; explicit generator override
- [ ] Ensure failures surface as normal JUnit failures in IDE and surefire reports
- [ ] Tests: properties run under both `mvn test` and IntelliJ's runner

**Note (Phase 18 final audit):** the most severe bug found in the whole audit was here. `Property.resolvePropertyId()`'s auto-naming stack-walk finds the first stack frame outside the `Property` class — but every `@Property` method is invoked through the exact same `PropertyExtension` call site, so that frame was ALWAYS `PropertyExtension`'s own internal frame, never the user's actual test method. Confirmed empirically: two completely unrelated, differently-named `@Property` methods collapsed onto one shared stored-failure file. Since failure persistence with zero-config auto-naming is the headline feature `@ForAll`/`@Property` are supposed to deliver, this meant it was silently broken by default for every unnamed property in the JUnit integration path — the primary way most users will interact with the library. Fixed by deriving the identity directly from the reflected `Method` (declaring class + method name) inside `PropertyExtension`, which is both the fix and more reliable than a stack walk ever was for this call path.

---

### Phase 16 — Library Hardening
**Status:** Done
**Depends on:** 15
**Goal:** The difference between "it works on my machine" and "someone else can depend on this."

- [ ] Javadoc on every public type and method
- [ ] Audit public vs `internal` — anything public is a compatibility promise (D7)
- [ ] **Use JHusk to test JHusk** — property-test the generators and shrinker with JHusk itself
- [ ] Meaningful exception messages for misuse
- [ ] Thread-safety statement: document what is and isn't safe, explicitly
- [ ] `package-info.java` for each public package
- [ ] Re-verify README usage examples match the API as actually built, and correct any drift

**Note (Phase 18 final audit):** the two boundary items this phase explicitly flagged but didn't resolve (`Property.withFailureStorage(FailureStorage)` leaking an internal type, `ShrinkHarness.BufferKey`'s visibility) are now resolved, not just re-flagged — see D7 above and the Phase 18 entry below. The thread-safety statement's concurrency claim was verified empirically (see Phase 14 note), not just trusted as written.

---

### Phase 17 — GitHub Actions CI
**Status:** Done
**Depends on:** 16
**Goal:** Every push verified automatically.

- [ ] Workflow: build + test on push and PR
- [ ] Matrix across JDK 17 / 21 / 25 — proves the `--release 17` baseline actually holds (R6)
- [ ] Cache Maven dependencies
- [ ] Surface test results in the PR view
- [ ] Build status badge in README

---

### Phase 18 — Publishing to Maven Central
**Status:** In Progress
**Depends on:** 17
**Goal:** `io.github.<username>:jhusk` installable by anyone.

- [ ] Register at `central.sonatype.com`; claim the `io.github.<username>` namespace via GitHub verification
- [ ] Generate a GPG key; understand *why* artifact signing exists; publish the public key
- [ ] Generate a Portal user token
- [x] POM completeness: name, description, url, licenses, developers, scm — Central rejects incomplete POMs
- [x] Attach `maven-source-plugin` and `maven-javadoc-plugin` — both are mandatory for Central
- [x] Configure `central-publishing-maven-plugin`
- [ ] Publish a `-SNAPSHOT` first as a rehearsal
- [x] Tag-triggered release workflow in GitHub Actions, with secrets for the key and token
- [ ] First release; verify it resolves from a clean, unrelated project
- [ ] Update README with real installation coordinates and the Maven Central badge

**Status detail:** all in-repo plumbing is complete and verified (pom.xml, the `release` profile, `.github/workflows/release.yml`). What remains is the one-time, external, credentialed setup (Sonatype Central account/namespace verification, GPG key generation and publication, Portal token) and the actual first publish — none of which can happen from inside the repository itself. This is also where the final pre-release audit (this pass) lives: comprehensive validation against the locked D1–D7 decisions and R1–R6 risk register, correctness stress-testing beyond the existing suite, a code-quality pass, and a comparative benchmark against Hypothesis/jqwik — see the audit report for the full findings and fixes. `mvn clean test` passes fully as of this pass.

---

## Post-Release

Not part of the initial release. Recorded so scope creep stays visible and deliberate rather than accidental.

- Stateful/model-based testing (a genuinely different paradigm, not a missing PBT feature)
- Targeted/coverage-guided search
- Typed choice-sequence IR (revisiting D1, particularly for float shrinking)
- Broader built-in generator catalogue driven by real user requests
- GitHub Pages Javadoc hosting

**Confirmed at the Phase 18 audit: none of these crept in.** No stateful/model-based testing, no coverage-guided fuzzing, no targeted search, no typed choice-sequence IR (still raw bytes throughout), and the generator catalogue (primitives, `combine`, `oneOf`, `just`/`constant`, `lists`, `strings`, `optionals`, `sets`, `maps`) is deliberately non-exhaustive — no UUID/BigInteger/date-time/regex generators, matching "Required: composable generators," not "as many generators as possible."
