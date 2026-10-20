# Micronaut Module Maintenance

Development setup and verification commands are documented in [CONTRIBUTING.md](CONTRIBUTING.md).
Report undisclosed vulnerabilities through [SECURITY.md](SECURITY.md).

## Triage Incoming Issues

Classify issues with a `type:` label such as `bug`, `improvement`, `enhancement`, or `docs`.
Use `type: breaking`, `type: deprecated`, or `type: removed` when applicable. Label pull requests
consistently: [`.github/release.yml`](.github/release.yml) uses these labels to group release notes.

Use `status:` labels to record missing information, validation, or third-party blockers, and update
them as the issue progresses. Prioritize accepted work with `priority: high`, `priority: medium`,
or `priority: low`, following the
[issue priority guidelines](https://github.com/micronaut-projects/micronaut-core/wiki/Issue-Priority-Labels).

## Review Pull Requests

Before merging, confirm that:

- The CLA and required CI checks pass.
- The implementation follows existing Micronaut APIs and module conventions.
- Behavior changes have regression coverage and appropriate documentation.
- The description records verification and compatibility implications.
- Resolved issues are linked using closing keywords or GitHub's issue links.

Choose the target branch according to
[Micronaut Module Versioning](https://github.com/micronaut-projects/micronaut-core/wiki/Micronaut-Module-Versioning)
and [Module Branch Naming](https://github.com/micronaut-projects/micronaut-core/wiki/Micronaut-Module-Branch-Naming).
Keep breaking changes out of patch and minor releases. Micronaut Core and Starter use a
[separate branching strategy](https://github.com/micronaut-projects/micronaut-core/wiki/Micronaut-Core-and-Starter-Branching-Strategy).

## Dependency Updates

Renovate configuration lives in [`.github/renovate.json`](.github/renovate.json). Review dependency
updates together when they belong to the same library or platform, and check the
[Module Upgrade Strategy](https://github.com/micronaut-projects/micronaut-core/wiki/Module-Upgrade-Strategy)
before accepting a new major or minor version.

If an upgrade must wait, record the reason in an issue and configure the version restriction
in the owning build or Renovate configuration. Keep that restriction visible for later removal.

## Shared Files and Workflows

The [project template](https://github.com/micronaut-projects/micronaut-project-template) supplies
shared workflows, configuration, the Gradle wrapper, security and maintenance guidance, and agent
skills. The source of truth for the copied files is
[`.github/workflows/files-sync.yml`](.github/workflows/files-sync.yml); workflow exclusions are
listed in [`.github/workflows/.rsync-filter`](.github/workflows/.rsync-filter).

Make generally applicable changes in the template, then review the resulting sync pull request.
Do not rely on edits to a synced workflow surviving a later sync. The Java CI workflow supports
optional root `setup.sh` and `cleanup.sh` hooks; additional module-specific verification can use
a separate workflow excluded from sync.

For files in the explicit copy list, sync checks for a sibling `.lock` file before replacement.
It also supports locks for individual agent skill files. Use repository-specific guidance outside
synced files where possible. `README.md` and `CONTRIBUTING.md` are not in the recurring file-copy list.

## Snapshots

Successful Java CI push builds publish snapshots and development documentation through
[`.github/workflows/gradle.yml`](.github/workflows/gradle.yml). Pull request builds verify changes
without publishing. The manual
[snapshot workflow](.github/workflows/publish-snapshot.yml) publishes artifacts only.

Use the repository's `projectVersion` in `gradle.properties` and the snapshot repository described
in [README.md](README.md). Publishing requires the repository's Sonatype credentials; documentation
deployment also requires the configured GitHub token.

## Releases

Release behavior is defined by [`.github/workflows/release.yml`](.github/workflows/release.yml).
It runs when a GitHub release is **published**. Merging a pull request, saving a draft release,
or pushing a tag alone does not trigger this workflow.

Before publishing:

1. Select the commit and release branch, and confirm that the required checks have passed.
1. Choose an unused semantic version and a matching `v`-prefixed tag, such as `v1.2.3`.
1. Create or edit the GitHub release, generate and review its release notes, and check the target
   branch or commit. A pre-existing draft release is not required.
1. Mark milestones and release candidates as prereleases. Confirm that the publishing, signing,
   and GitHub credentials used by the workflow are configured.
1. Publish the GitHub release and monitor the Release workflow.

The workflow runs the vulnerability audit, prepares the release version, publishes artifacts with
`publishToMavenCentral --publishing-type=AUTOMATIC`, generates and deploys documentation, and runs
the post-release action to advance development. It also generates provenance and attaches build
artifacts to the GitHub release. A separate **Maven Central Sync** run is not required.

The vulnerability audit is advisory for milestone and release-candidate tags such as `v1.2.3-M1`
or `v1.2.3-RC1`; findings block stable releases. Its implementation is in
[`.github/scripts/vulnerability-audit.sh`](.github/scripts/vulnerability-audit.sh).

After a successful release, check that the artifacts are available from Maven Central, the
versioned guide is deployed, and development has advanced to the next snapshot version. For a
stable release, also check the `latest` guide and the README's version badge.

If a release fails, inspect the failed step and whether artifacts were already published before
retrying. Published Maven Central versions cannot be replaced. For a new minor or major release,
preserve any supported previous release line with the appropriate maintenance branch before
advancing the default branch.
