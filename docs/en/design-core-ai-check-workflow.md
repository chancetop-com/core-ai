# Core AI Check Workflow Design

## Context

The CLI release workflow now uses a CLI-scoped `releaseCheck` task so that Windows native releases do not inherit unrelated or interactive checks. That release workflow should remain focused on producing release binaries. The repository still needs a separate, visible GitHub Actions check for the two reusable core library modules.

## Goals

- Check `core-ai-api` and `core-ai` on every relevant pull request and push to `master`.
- Run the standard Gradle verification lifecycle, including tests and configured static analysis.
- Keep the check independent from CLI native compilation, server Docker builds, and releases.
- Fail within a bounded amount of time and cancel obsolete runs for the same branch or pull request.

## Non-goals

- Checking `core-ai-cli`, `core-ai-server`, or `core-ai-benchmark`.
- Building native executables or container images.
- Publishing artifacts, packages, or releases.
- Changing repository branch-protection settings.

## Workflow

Add `.github/workflows/core-ai-check.yml` with the display name `Core AI Check` and one job named `check`.

The workflow runs on:

- Pull requests targeting `master`.
- Pushes to `master`.
- Manual `workflow_dispatch` runs.

Automatic runs use path filters so documentation-only or unrelated service changes do not consume CI time. Relevant paths are:

- `core-ai/**`
- `core-ai-api/**`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `buildSrc/**`
- `gradle/**`
- `gradlew`
- `gradlew.bat`
- `.github/workflows/core-ai-check.yml`

The job runs on `ubuntu-latest`, has a 30-minute timeout, and receives only `contents: read` permission. Its steps are:

1. Check out the repository.
2. Install Temurin JDK 25.
3. Configure the Gradle Actions cache.
4. Run `./gradlew :core-ai-api:check :core-ai:check --no-daemon --stacktrace`.

The two module checks run in one Gradle invocation. This shares configuration and dependency work while still reporting the exact failed Gradle task in the log.

## Concurrency and Failure Behavior

The concurrency key distinguishes pull requests and branch refs. `cancel-in-progress: true` stops an older run when a newer commit supersedes it.

Any failed test, Checkstyle rule, PMD rule, SpotBugs finding, dependency-resolution error, or Gradle configuration error fails the job. The 30-minute job timeout prevents an indefinitely blocked check. No step uses `continue-on-error`, and no verification command is suffixed with `|| true`.

## Validation

Before committing the workflow:

- Run `./gradlew :core-ai-api:check :core-ai:check --no-daemon --stacktrace` locally.
- Parse the workflow as YAML and inspect the rendered trigger/job structure.
- Run `git diff --check` and review the staged file list.

After pushing:

- Trigger `Core AI Check` manually once.
- Confirm the run targets the expected commit and completes successfully.
- Confirm a change outside the configured paths does not start an automatic run.

Once the workflow has produced its first successful check, repository administrators may add `Core AI Check / check` as a required branch-protection status if desired.
