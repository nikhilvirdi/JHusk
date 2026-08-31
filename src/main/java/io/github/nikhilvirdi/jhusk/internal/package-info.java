/**
 * JHusk's implementation details: byte-stream generation/replay ({@link
 * io.github.nikhilvirdi.jhusk.internal.DataSource}, {@link io.github.nikhilvirdi.jhusk.internal.Span}),
 * the shrinker ({@link io.github.nikhilvirdi.jhusk.internal.Shrinker}, {@link
 * io.github.nikhilvirdi.jhusk.internal.ShrinkHarness}), and the terminal-reporting bridge shared
 * by {@code Property} and the {@code junit} package's summary listener ({@link
 * io.github.nikhilvirdi.jhusk.internal.PropertyReporting}, {@link
 * io.github.nikhilvirdi.jhusk.internal.TerminalFormat}, {@link
 * io.github.nikhilvirdi.jhusk.internal.ConsolidatedWarnings}).
 *
 * <p><b>Package layout note:</b> a natural alternative would split this package into separate
 * {@code .shrink} (shrink passes/ordering) and {@code .db} (persistence) packages alongside
 * {@code .internal}. Instead, shrinking ({@code Shrinker}/{@code ShrinkHarness}) stays in
 * {@code .internal}, and persistence ({@code FailureStorage}) is promoted to the public
 * {@link io.github.nikhilvirdi.jhusk} package rather than living in a {@code .db} package at all
 * (see that package's javadoc for why). Justification: none of these are part of the public
 * compatibility surface except {@code FailureStorage}, and splitting {@code .internal} further
 * by concern (rather than by public/private boundary) wasn't buying enough clarity to justify
 * three internal packages instead of one for a codebase this size.
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
