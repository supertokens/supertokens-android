# 0.6.0 process-death execution — 2026-10-29

**PASS: two controlled process deaths, six normal stage passes**, on API34
`emulator-5580`, against real Node SDK 16.7.0 / Core 8.0.0.

Each scenario passed seed, restart check and scoped cleanup with exactly one
normal JUnit pass per invocation. Held invocations are **not JUnit passes**.

- Login: checkpoint PID **3885**, target-only force-stop, old target absent;
  restart retained ABSENT, later login/protected access/refresh succeeded.
- Refresh: checkpoint PID **4211**, actual Core rotation held before SDK adoption,
  target-only force-stop, old target absent; restart retained the parent tuple,
  real Core parent-grace recovery/protected identity and handle/another refresh
  succeeded. Child access was not used before recovery.
- Both hold transcripts contain only test-start status followed by `Process
  crashed`; no completed JUnit status, `FAILURES!!!`, or hold/watchdog failure.
  Reviewed failure-exclusion guards were unchanged.
- Both durably tracked fixture IDs have `phase=cleaned`, `cleanupDone=true`.
  Backend cleanup deletes the known owner and all its sessions, including the
  discarded login session. No untracked owner-creation request occurred at a kill.
- Post-run Express `/healthz` and Core `/hello`: 200. Backend left running.
  Exact-pair account-linking gate remains `LINK_REVOKED_SESSION`, an R7 exclusion.

## Scope

Cutpoint is **received network response before SDK adoption**, not a disk-commit
crash. Evidence establishes controlled checkpoint, matching live target, explicit
force-stop and subsequent absence. The observation-to-stop race cannot fully
establish external kill causality; known reported failures are rejected.
App data was preserved with install-r; no uninstall, clear, broad reset or SDK
source edit. No commits or publication.

## Fresh artifacts

Both SDK AAR manifests report **0.6.0**; all **84 classes** in each AAR have Java
class major **51**. The upgrade fixture's own application version remains 1.

- Device debug AAR: `42f0ea8f23839b97c9c895a6076e1da1fb441a24a2b2176d79047a3fd4c2dcc1`
- Release AAR: `406c3a6640bc1f3a7d00aae58d98fd274938fa307ed6f8b94fdfc3f477c59908`
- Candidate app APK: `f01be1631985e33eb4abb1fc7d8d48dd19d0399ce91297d33c43606b4d2aa823`
- Candidate test APK: `f9e09ec10a2be5f22d6aea98a32d8965f28800a95a5c8797310bd13b42d6d260`
- Shared signer SHA-256: `bafa4a5e47d7339ebca51dfba2adf119c7d857fc2312888debe5cf380f03a6b6`

Fresh debug AAR build used `build_provenance.py`, forced execution and unchanged
source-state attestation. APK manifests/signers/hashes inspected with build-tools
30.0.3; neither declared testOnly. Installed APK hashes matched supplied artifacts.

## Final root gates

Forced `--rerun-tasks`: **437 JVM / 71 oracle**, zero failures/errors/skips;
lint debug/release **0 issues**; japicmp, old consumer verification, API gate
self-tests and release assembly passed. **46/46 tasks executed**. No broad
testapp run. The first root invocation was up-to-date for tests/API comparison,
so the forced run supplies actual execution evidence.

No scenario or test failures. Two evidence/setup checks were corrected: the README's
example build-tools 30.0.2 was absent (used installed 30.0.3), and library BuildConfig
does not embed VERSION_NAME (verified version in each AAR manifest instead).
Lint emitted a nonfatal JNA load stack trace; Gradle succeeded and reported zero
lint issues. AAPT also emitted its existing unknown-chunk warning.

## Private evidence

Root: `/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/m6-device-death-YVbjkwUj`

- `fresh-060/`: candidate AAR, source/build receipt and forced build log.
- `artifacts-060/`: APKs, manifests, signing reports, hashes, installation logs,
  backend capabilities.
- `device-060/`: run/artifact metadata, both kill proofs/checkpoints, PID probes,
  complete instrumentation transcripts and private typed XML at each stage.
- `final-root-060-rerun.log`, `final-testDebugUnitTest/`, `final-legacy054Test/`:
  actual final gate execution and JUnit XML.
- `final-results.json`, `candidate-release-060.aar`: exact counts and artifacts.

Raw preference XML contains credentials; keep this evidence private.
