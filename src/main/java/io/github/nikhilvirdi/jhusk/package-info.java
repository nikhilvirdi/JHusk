/**
 * JHusk's public property-based testing API: {@link io.github.nikhilvirdi.jhusk.Generator},
 * {@link io.github.nikhilvirdi.jhusk.Generators}, and {@link io.github.nikhilvirdi.jhusk.Property}.
 *
 * <p>A typical property is written by picking or composing a {@code Generator<T>} from {@link
 * io.github.nikhilvirdi.jhusk.Generators} (or building one from scratch), then binding it to an
 * assertion via {@link io.github.nikhilvirdi.jhusk.Property#forAll}. For JUnit 5 integration, see
 * the sibling {@link io.github.nikhilvirdi.jhusk.junit} package instead of using {@code Property}
 * directly.
 *
 * <p><b>Compatibility promise:</b> everything {@code public} in this package is JHusk's supported
 * API surface. The one caveat is {@link io.github.nikhilvirdi.jhusk.internal.DataSource} — it
 * lives in {@code .internal} (see that package's documentation for why), but is unavoidably
 * exposed here too, since {@link io.github.nikhilvirdi.jhusk.Generator}'s sole abstract method
 * takes one as a parameter and writing a custom generator (a first-class, encouraged use of this
 * package) means calling its draw/span methods directly.
 *
 * <p><b>Not part of this API:</b> {@link io.github.nikhilvirdi.jhusk.IntStack} is a leftover
 * teaching fixture from this project's early bootstrapping phases (a small stack used to learn
 * JUnit 5 before any property-testing code existed). It shares this package only for historical
 * reasons, has no connection to property-based testing, and should not be treated as part of the
 * public API despite being {@code public}.
 */
package io.github.nikhilvirdi.jhusk;
