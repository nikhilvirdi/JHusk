/**
 * JHusk's implementation details: byte-stream generation/replay ({@link
 * io.github.nikhilvirdi.jhusk.internal.DataSource}, {@link io.github.nikhilvirdi.jhusk.internal.Span}),
 * the shrinker ({@link io.github.nikhilvirdi.jhusk.internal.Shrinker}, {@link
 * io.github.nikhilvirdi.jhusk.internal.ShrinkHarness}), and failure persistence ({@link
 * io.github.nikhilvirdi.jhusk.internal.FailureStorage}).
 *
 * <p><b>Not a compatibility promise.</b> Nothing in this package is part of JHusk's public API,
 * regardless of the {@code public} modifier on individual classes and methods here — signatures
 * and behavior in {@code .internal} can change without notice between releases. Two exceptions,
 * both unavoidable rather than intentional:
 * <ul>
 *   <li>{@link io.github.nikhilvirdi.jhusk.internal.DataSource} is a parameter type of
 *       {@link io.github.nikhilvirdi.jhusk.Generator#generate}, so anyone writing a custom
 *       generator calls its methods directly. It is documented as if public because it
 *       effectively is, even though it lives here.</li>
 *   <li>{@link io.github.nikhilvirdi.jhusk.internal.FailureStorage} is accepted directly by
 *       {@link io.github.nikhilvirdi.jhusk.Property#withFailureStorage}, a genuine public/internal
 *       boundary leak (flagged, not fixed, during the Phase 16 audit) — prefer {@link
 *       io.github.nikhilvirdi.jhusk.Property#withStorageDir}, which only requires a
 *       {@link java.nio.file.Path}.</li>
 * </ul>
 * {@link io.github.nikhilvirdi.jhusk.internal.Span} is also reachable, transitively, via {@code
 * DataSource.getRootSpans()} — but only framework code (the shrinker) has any real reason to call
 * that method; ordinary generator authors won't need it.
 */
package io.github.nikhilvirdi.jhusk.internal;
