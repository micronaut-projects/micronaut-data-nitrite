# Micronaut Data Nitrite

[![Maven Central](https://img.shields.io/maven-central/v/io.micronaut.data/micronaut-data-nitrite.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.micronaut.data/micronaut-data-nitrite)
[![Build Status](https://github.com/micronaut-projects/micronaut-data-nitrite/actions/workflows/gradle.yml/badge.svg)](https://github.com/micronaut-projects/micronaut-data-nitrite/actions/workflows/gradle.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=micronaut-projects_micronaut-data-nitrite&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=micronaut-projects_micronaut-data-nitrite)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.micronaut.io/scans)

Micronaut Data Nitrite brings Micronaut Data repositories to the
[Nitrite](https://nitrite.dizitart.com/) embedded document database. The database runs inside your
application, with in-memory storage or persistence through MVStore or RocksDB, without a separate
database server.

Micronaut Data processes repository interfaces at compile time, validating repository methods
and preparing queries for the Nitrite runtime. Applications use familiar repository APIs for
derived and criteria queries, pagination, entity mapping, associations, transactions, and
optimistic locking. An optional spatial module adds JTS geometry support, indexes, and queries.

## Quick Start

Add the Nitrite integration to an existing Micronaut application. Micronaut Launch does not yet
provide a dedicated `data-nitrite` feature.

For a Java application using Gradle, set `nitriteVersion` to a published integration version
shown by the Maven Central badge, or the current snapshot in [`gradle.properties`](gradle.properties),
and add the following dependencies. For a snapshot version, configure the snapshot repository below.

```groovy
def nitriteVersion = "<nitrite-version>"

dependencies {
    implementation platform("io.micronaut.data:micronaut-data-nitrite-bom:$nitriteVersion")
    annotationProcessor platform("io.micronaut.data:micronaut-data-nitrite-bom:$nitriteVersion")
    annotationProcessor "io.micronaut.data:micronaut-data-document-processor"
    annotationProcessor "io.micronaut.data:micronaut-data-nitrite-core"
    implementation "io.micronaut.data:micronaut-data-nitrite"
}
```

To start with an in-memory database, add this to `src/main/resources/application.yml`:

```yaml
micronaut:
  nitrite:
    default:
      storage-mode: IN_MEMORY
```

See the [Quick Start](https://micronaut-projects.github.io/micronaut-data-nitrite/snapshot/guide/#nitriteQuickStart)
for entity and repository examples, Maven and other language setup, and persistent storage options.
Runnable examples are available in [Java](doc-examples/nitrite-example-java),
[Kotlin](doc-examples/nitrite-example-kotlin), and [Groovy](doc-examples/nitrite-example-groovy).

## Documentation

- [Documentation](https://micronaut-projects.github.io/micronaut-data-nitrite/latest/guide/) for the latest release, available after the first release.
- [Snapshot Documentation](https://micronaut-projects.github.io/micronaut-data-nitrite/snapshot/guide/) for current development.
- [Scope and limitations](https://micronaut-projects.github.io/micronaut-data-nitrite/snapshot/guide/#nitriteLimitations).

## Snapshots and Releases

Snapshots are published to [Sonatype Snapshots](https://central.sonatype.com/repository/maven-snapshots/io/micronaut/data/micronaut-data-nitrite/maven-metadata.xml)
through [GitHub Actions](https://github.com/micronaut-projects/micronaut-data-nitrite/actions).
Use `https://central.sonatype.com/repository/maven-snapshots/` as the Maven repository URL and the
current version in [`gradle.properties`](gradle.properties). See
[Sonatype's snapshot setup](https://central.sonatype.org/publish/publish-portal-snapshots/#consuming-snapshot-releases-for-your-project)
for Maven and Gradle configuration.

Releases are published to Maven Central through GitHub Actions. Published releases are listed in
[GitHub Releases](https://github.com/micronaut-projects/micronaut-data-nitrite/releases).

For building, testing, and contributing, see [CONTRIBUTING.md](CONTRIBUTING.md). Maintainer
procedures are in [MAINTAINING.md](MAINTAINING.md); vulnerability reporting is covered by
[SECURITY.md](SECURITY.md).
