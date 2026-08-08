/**
 * JHusk's JUnit 5 integration: the {@link io.github.nikhilvirdi.jhusk.junit.Property @Property}
 * and {@link io.github.nikhilvirdi.jhusk.junit.ForAll @ForAll} annotations.
 *
 * <p>Annotate a test method with {@code @Property} and its generated parameters with
 * {@code @ForAll} to run a JHusk property as a native JUnit 5 test — it appears as a single test
 * in your build's report, internally running the full generate/check/shrink loop, and failing
 * with JHusk's shrunk failure report if any example falsifies the property.
 *
 * <pre>{@code
 * @Property
 * void additionIsCommutative(@ForAll int a, @ForAll int b) {
 *     assertEquals(a + b, b + a);
 * }
 * }</pre>
 *
 * <p><b>Compatibility promise:</b> {@code @Property} and {@code @ForAll} are the supported public
 * surface of this package. {@link io.github.nikhilvirdi.jhusk.junit.PropertyExtension} is also
 * {@code public} — required by JUnit 5's extension SPI — but is wired in automatically by
 * {@code @Property} and is never meant to be referenced directly.
 */
package io.github.nikhilvirdi.jhusk.junit;