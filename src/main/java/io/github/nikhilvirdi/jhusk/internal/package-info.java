/**
 * JHusk's implementation details: byte-stream generation/replay ({@link
 * io.github.nikhilvirdi.jhusk.internal.DataSource}, {@link io.github.nikhilvirdi.jhusk.internal.Span})
 * and the shrinker ({@link io.github.nikhilvirdi.jhusk.internal.Shrinker}, {@link
 * io.github.nikhilvirdi.jhusk.internal.ShrinkHarness}).
 *
 * <p><b>Deviation from WORKPLAN.md's D7 package layout proposal:</b> D7 originally proposed
 * separate {@code .shrink} (shrink passes/ordering) and {@code .db} (persistence) packages
 * alongside {@code .internal}. In the shipped layout, shrinking ({@code Shrinker}/
 * {@code ShrinkHarness}) stayed in {@code .internal} instead of moving to a dedicated
 * {@code .shrink} package, and persistence ({@code FailureStorage}) ended up promoted to the
 * public {@link io.github.nikhilvirdi.jhusk} package rather than living in {@code .db} at all
 * (see that package's javadoc for why). Justification: none of these are part of the public
 * compatibility surface except {@code FailureStorage}, and splitting {@code .internal} further
 * by concern (rather than by public/private boundary) wasn't buying enough clarity to justify
 * three internal packages instead of one for a codebase this size. Recorded here, in the shipped
 * package-info, rather than only in a phase-completion chat transcript, which is not durable.
 *
 * <p><b>Not a compatibility promise.</b> Nothing in this package is part of JHusk's public API,
 * regardless of the {@code public} modifier on individual classes and methods here — signatures
 * and behavior in {@code .internal} can change without notice between releases. One exception,
 * unavoidable rather than intentional: {@link io.github.nikhilvirdi.jhusk.internal.DataSource} is
 * a parameter type of {@link io.github.nikhilvirdi.jhusk.Generator#generate}, so anyone writing a
 * custom generator calls its methods directly. It is documented as if public because it
 * effectively is, even though it lives here. {@link io.github.nikhilvirdi.jhusk.internal.Span} is
 * also reachable, transitively, via {@code DataSource.getRootSpans()} — but only framework code
 * (the shrinker) has any real reason to call that method; ordinary generator authors won't need it.
 */
package io.github.nikhilvirdi.jhusk.internal;
