# Installation

JHusk is published on Maven Central under the coordinates `io.github.nikhilvirdi:jhusk`.

## Adding JHusk with Maven

Add the dependency to your `pom.xml`, inside the `<dependencies>` block:

```xml
<dependency>
    <groupId>io.github.nikhilvirdi</groupId>
    <artifactId>jhusk</artifactId>
    <version>1.1.1</version>
    <scope>test</scope>
</dependency>
```

The `test` scope keeps JHusk out of your production classpath, since it's a testing tool and has no reason to ship with your application.

## Adding JHusk with Gradle

For Groovy-based `build.gradle` files:

```groovy
testImplementation 'io.github.nikhilvirdi:jhusk:1.1.1'
```

For Kotlin DSL `build.gradle.kts` files:

```kotlin
testImplementation("io.github.nikhilvirdi:jhusk:1.1.1")
```

## Verifying the installation

Once you've added the dependency, run your build tool's dependency resolution to confirm it downloads correctly:

```bash
mvn dependency:resolve
```

or, for Gradle:

```bash
gradle dependencies --configuration testCompileClasspath
```

Either command should list `io.github.nikhilvirdi:jhusk:1.1.1` among the resolved dependencies. If it doesn't appear, double check the coordinates match exactly what's shown above, and that your build file's dependency block was saved correctly.

## Requirements & compatibility

JHusk is built and tested against JDK 17, 21, and 25. It requires Java 17 as a minimum, since it relies on language features introduced in that release. There's no compatibility path for earlier Java versions.

JHusk's JUnit 5 integration requires JUnit Jupiter to already be part of your project's test setup. If you're starting a project from scratch and don't yet have JUnit 5 configured, the [JUnit 5 user guide](https://junit.org/junit5/docs/current/user-guide/#writing-tests) walks through that setup.

JHusk works with both Maven and Gradle, and doesn't require any build plugin beyond the standard JUnit Platform test runner your build tool already uses to discover and run JUnit 5 tests.
