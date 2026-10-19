# Repository Guidance

Micronaut Data Nitrite integrates Micronaut Data repositories with the embedded Nitrite database.
Read [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and pull request requirements.

## Repository Shape

- `data-nitrite-core/` contains query generation, mapping, repository operations, and transactions.
- `data-nitrite/` provides the default core/MVStore dependency combination and the main Groovy test suite.
- `data-nitrite-mvstore/`, `data-nitrite-rocksdb/`, and `data-nitrite-spatial/` provide optional integrations.
- `data-nitrite-bom/` aligns integration, Micronaut Data, and Nitrite versions.
- `jakarta-data-tck/nitrite/` runs the Jakarta Data TCK; `data-nitrite-native-tests/` verifies native images.
- `buildSrc/src/main/groovy/io.micronaut.build.internal.data-nitrite-*.gradle` contains build conventions.
- `gradle/libs.versions.toml` declares dependency versions; `gradle.properties` declares the integration version.

## Implementation

- Keep storage and spatial dependencies in their integration modules so core remains usable alone.
- Preserve datasource isolation when changing repository, connection, or transaction operations.
- Add regression coverage in the owning module for behavior changes.
- Explain unsupported features in the guide rather than presenting skipped tests as passing coverage.

## Documentation

- Guide sources and navigation are in `src/main/docs/guide` and its `toc.yml`.
- Tagged snippets come from the Java, Kotlin, and Groovy projects in `doc-examples/`; keep them consistent.
- Keep the README focused on users, contributor commands in `CONTRIBUTING.md`, and release procedures in `MAINTAINING.md`.

## Verification

- Use JDK 25 and `./gradlew -PpredictiveTestSelection=false check` for JVM checks, including the TCKs and examples.
- Use `./gradlew publishGuide` after guide changes, or `./gradlew docs` for the guide and Javadocs.
- With GraalVM 25 selected, use `./gradlew :micronaut-data-nitrite-native-tests:nativeCheck` for native verification.
- For `.agents/skills/**` changes, follow `.github/workflows/skills-validation.yml`.

## Shared Files

- Shared workflows and configuration originate in `micronaut-projects/micronaut-project-template`.
- Check `.github/workflows/files-sync.yml` and `.github/workflows/.rsync-filter` before editing synced files.
- Keep synced guidance generic; put repository-specific workflow requirements in repository-owned files.
