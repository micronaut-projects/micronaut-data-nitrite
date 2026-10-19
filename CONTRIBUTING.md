# Contributing to Micronaut Data Nitrite

Sign the [Contributor License Agreement (CLA)](https://cla-assistant.io/micronaut-projects/micronaut-data-nitrite)
before submitting a pull request. To find work, browse issues labelled
[help wanted](https://github.com/micronaut-projects/micronaut-data-nitrite/issues?q=is%3Aopen+is%3Aissue+label%3A%22status%3A+help+wanted%22).

## Development Setup

Use JDK 25 and the included Gradle wrapper. The [SDKMAN configuration](.sdkmanrc) selects GraalVM 25,
which is also needed for native-image tests. JVM tests use embedded Nitrite and do not require Docker.

Import `settings.gradle` as a Gradle project in your IDE.

## Running Tests

Run the full JVM checks with predictive test selection disabled:

```sh
./gradlew -PpredictiveTestSelection=false check
```

This includes the repository tests, document TCK, Jakarta Data TCK, Java/Kotlin/Groovy examples,
and the JVM tests in `data-nitrite-native-tests`. The TCK suites are enabled by default through
`includeDataTck=true` in `gradle.properties`. Unsupported Jakarta Data features are described in
the [scope and limitations](src/main/docs/guide/nitriteLimitations.adoc).

To run a single repository specification:

```sh
./gradlew :micronaut-data-nitrite:test --tests '*NitriteReadOnlyTransactionSpec'
```

Native compilation and execution are separate from `check`. With GraalVM 25 selected, run:

```sh
./gradlew :micronaut-data-nitrite-native-tests:nativeCheck
```

The native verification project includes all integrations by default. Set `-PnativeBackend=<backend>`
to `in-memory`, `mvstore`, `rocksdb`, or `spatial` to verify an isolated backend classpath. CI runs
both the default and RocksDB configurations. Some tests are conditional on the selected backend;
persistent spatial-index reopening remains opt-in because of the documented upstream limitation.

## Building Documentation

Guide sources are in `src/main/docs/guide`, with navigation in `toc.yml`. Examples used by the
guide live in `doc-examples`; update the example source when changing a tagged snippet.

```sh
./gradlew publishGuide
```

Open `build/docs/index.html` to view the guide. Use `./gradlew docs` to build both the guide and
Javadocs.

## Using Local Changes in an Application

Publish the development artifacts to your local Maven repository:

```sh
./gradlew publishToMavenLocal
```

Use the `projectVersion` from [gradle.properties](gradle.properties) in the consuming application.
For Gradle, add the local repository before Maven Central:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}
```

Maven uses its local repository automatically. Configure the integration dependencies and annotation
processors as described in the [Quick Start](src/main/docs/guide/nitriteQuickStart.adoc).

## Code Style

Follow the existing code style. Checkstyle uses `config/checkstyle/checkstyle.xml` and runs as
part of `check`; Java compilation also runs Error Prone and NullAway. To check the core module's
Java source style directly:

```sh
./gradlew :micronaut-data-nitrite-core:checkstyleMain
```

## Creating a Pull Request

- Keep the change focused and include regression coverage for behavior changes.
- Run `check`; run `publishGuide` for guide changes and native verification for native-image changes.
- Describe the resulting behavior, verification performed, and any compatibility implications.
- Open a pull request against the appropriate branch, following the
  [maintainer guidance](MAINTAINING.md#review-pull-requests).

Report undisclosed security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
