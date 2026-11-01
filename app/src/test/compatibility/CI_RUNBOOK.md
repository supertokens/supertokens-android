# Secretless JVM/API check candidate

`.github/workflows/unit.yml` runs on every PR, pushes to `master`, and manual dispatch.
Stable job/check name: **JVM and API compatibility** (workflow: **Unit compatibility**).
It uses `pull_request`, read-only contents permission, no secrets, no persisted checkout
credentials and no privileged follow-up workflow. Fork PRs may require GitHub's normal
maintainer approval. There are no path filters that could strand a required check.

## Toolchain and tasks

- Ubuntu 22.04; JDK 17 bootstraps Android command-line tools 11076708. Install SDK 32
  and AGP 4.1.2's default Build Tools 29.0.2, then switch to **Temurin 11.0.25+9**.
  Gradle 6.5 and byte-for-byte `OldSdkConsumer.bin` verification run only on JDK 11.
- One Gradle invocation runs `:app:testDebugUnitTest`, `:app:legacy054Test`,
  `:app:lintRelease`, `:app:apiCompatibilityGateTest`, `:app:japicmp`,
  `:app:verifyOldSdkConsumer`, and `:app:assembleRelease`. Gradle shares compilation
  dependencies. No redundant `check`, extraction, consumer-regeneration or compile
  task invocation. `--continue` collects independent failures without accepting them.
- Cache only wrapper distributions and dependency downloads, keyed by wrapper,
  Gradle/dependency configuration, legacy checksum and workflow inputs. Build outputs
  and probe downloads are not restored. PR cache scope follows GitHub's isolation.

## Fresh-artifact runtime check

The workflow extracts `classes.jar` from its fresh release AAR and calls the actual
`runtime/probe.py --artifact-jar` CLI. The script requires exactly Java-7 major **51**
production classes and runs all seven exact OkHttp/Okio lanes, starting at
**3.3.0/1.8.0**, through 4.1.0/2.2.2. **3.12 is not the legacy minimum.**

Fourteen Maven Central dependency hashes are pinned inline from the recorded D5
fresh-artifact manifest (2026-10-29), verified before execution, then compared with
the probe's observed manifest. AndroidX annotation 1.1.0 comes from the preceding
Gradle resolution; the script fails unless exactly one cached JAR exists. Gradle's
general dependency graph is not checksum-locked by this workflow; the published
0.5.4 oracle AAR retains its existing checksum gate.

This invocation intentionally omits optional `--mockable-android-jar`: no guessed
AGP transform-cache path. It covers adapter/tag/linkage and owned-transport probes,
not `HistoricalSessionProbe` login/refresh/logout flows. Those broader D5 flows,
Android API17, R8, historical consumer pipelines and historical backend/full CI
remain separate. See [runtime evidence](RUNTIME_COMPATIBILITY.md) and
[API policy](API_GATE.md). API success is not runtime-floor certification or R7 coverage.

## Reports and administration

The always-run upload retains JUnit XML, HTML/lint reports, API policy/comparison
reports, fresh AAR, artifact/dependency hashes and probe log for 14 days, including
available reports on failure. Setup failures may produce no reports. Inspect
`app/build/api-compatibility/report/disallowed-changes.txt` and the failing task;
never regenerate the committed consumer automatically to make CI green.

**Required-check enforcement is manual:** after a real PR run establishes the check,
a repository administrator must add **JVM and API compatibility** to the applicable
branch-protection rule/ruleset, selecting GitHub Actions as the expected source where
available. Verify it blocks a failing PR. This workflow does not configure branch
protection and is not yet claimed enforced. If merge queues are enabled, add and
validate `merge_group` support before requiring it there.

## Validation / remaining risks

Implementation-time validation is static only; M4 owns the sole local Gradle lane.
PyYAML parsing/structure checks, `bash -n` for every embedded command block, all 14
pins against the recorded D5 manifest, probe CLI flag checks and `git diff --check`
passed. `actionlint` was not installed; no tooling was installed for this task.
Actual GitHub/Linux execution is pending. JCenter/JitPack legacy dependency
availability, old AGP/lint on Linux, SDK downloads, pinned JDK availability and
consumer-byte reproducibility must be confirmed by the first run. Action major tags
are used; dependency hashes do not pin action implementations. No local Gradle,
backend, emulator, full CI or historical matrix execution is implied by this change.
