# Published-SDK upgrade / rollback consumer

Standalone M6 rank-2 fixture. **API34 published upgrade/rollback: 20/20 stages
passed.** See [dated artifact/backend/device evidence](RESULTS_2026-10-29.md).
The separate [R8 consumer smoke](R8_SMOKE.md) now proves unmapped-cookie migration
and real login/refresh across two differently mapped release APKs on API34.
Run commands from the repository root only with the Gradle/device lane assigned
to you. No root settings or app build edits are required.

Both flavors use `com.supertokens.upgrade`, versionCode **1**, the same debug key
and shared instrumentation contract. Published 0.5.4 is checksum-verified against
`app/src/test/compatibility/legacy054.sha256`:
`2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9`.
Candidate input is an explicit local AAR plus required SHA-256. The build checks
both hashes and embeds flavor/AAR provenance in each app APK. Dependencies pin
OkHttp 3.3.0 / Okio 1.8.0, minSdk 17 and Java 7, without MockWebServer. These settings
do not establish API-17 runtime compatibility.

## Build handoff — after Gradle lane release

Use JDK 11 with the root Gradle 6.5 wrapper / AGP 4.1.2 and Android platform 32.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"

# Coordinator-approved build lane only. This command RUNS Gradle.
BUILD_EVIDENCE="${TMPDIR:-/tmp}/candidate-build-$(date +%Y%m%d-%H%M%S)"
python3 testHelpers/upgradeapp/tools/build_provenance.py --output "$BUILD_EVIDENCE"
CANDIDATE_AAR="$BUILD_EVIDENCE/candidate.aar"
CANDIDATE_BUILD="$BUILD_EVIDENCE/candidate-build.json"
CANDIDATE_SHA=$(shasum -a 256 "$CANDIDATE_AAR" | awk '{print $1}')

./gradlew -p testHelpers/upgradeapp \
  -PcandidateAar="$CANDIDATE_AAR" -PcandidateAarSha256="$CANDIDATE_SHA" \
  -PcandidateBuildProvenance="$CANDIDATE_BUILD" \
  :app:assembleSdk054Debug :app:assembleSdk054DebugAndroidTest \
  :app:assembleCandidateDebug :app:assembleCandidateDebugAndroidTest
```

The build helper records Git revision, dirty status, binary `git diff HEAD` hash,
untracked-file content manifest hash, build command, start/end timestamps, log hash
and fresh AAR hash. It forces task execution with `--rerun-tasks`, rejects source
changes during the build and copies the resulting AAR. Both Gradle packaging and
the runner reject a candidate hash equal to the published baseline. Packaging
embeds the build receipt; the runner requires an identical external receipt and
copies it into `run.json` and `candidate-build.json`.

A hash alone does not prove freshness. These are local build attestations, not
signed independent proof of compiled SDK identity. File AAR dependencies
do not import a POM; this consumer supplies dependencies explicitly.

## Device handoff

Use a dedicated fixture installation and explicit device-reachable backend
origin. Backend requires `CORE_PROTOCOL_TESTS=1`, compatible live Core and
`mappedGoogleSession` capability. See
[`INTEGRATION_READINESS.md`](../../app/src/test/compatibility/INTEGRATION_READINESS.md).
Capability failures fail the test; the runner does not start/reset services.
The logout scenario requires the backend extension described in
[`BACKEND_HANDOFF.md`](BACKEND_HANDOFF.md). It is applied and active in the recorded
Express/Core runtime; verify current readiness before subsequent runs.

```sh
APKS="$PWD/testHelpers/upgradeapp/app/build/outputs/apk"
export ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
export AAPT="$ANDROID_SDK_ROOT/build-tools/30.0.2/aapt"
export APKSIGNER="$ANDROID_SDK_ROOT/build-tools/30.0.2/apksigner"
# Select installed build-tools paths if 30.0.2 is unavailable.
EVIDENCE="${TMPDIR:-/tmp}/upgrade-$(date +%Y%m%d-%H%M%S)"
sh testHelpers/device/upgrade.sh \
  --serial emulator-5580 --backend http://10.0.2.2:8080 \
  --sdk-apk "$APKS/sdk054/debug/app-sdk054-debug.apk" \
  --sdk-test-apk "$APKS/androidTest/sdk054/debug/app-sdk054-debug-androidTest.apk" \
  --candidate-apk "$APKS/candidate/debug/app-candidate-debug.apk" \
  --candidate-test-apk "$APKS/androidTest/candidate/debug/app-candidate-debug-androidTest.apk" \
  --candidate-aar-sha256 "$CANDIDATE_SHA" \
  --candidate-build-provenance "$CANDIDATE_BUILD" --output "$EVIDENCE"
```

The recorded build confirmed these APK paths. Required `aapt`/`apksigner`
preflight checks all packages/signers, both app versionCodes, instrumentation
target/runner and app AAR provenance before contacting the device. All four APKs
and hashes are preserved in the new private evidence directory. Preflight saves
the actual manifests for **all four APKs**. Actual packaging and `testOnly` values
were inspected in the recorded run (none declared testOnly). If a future manifest declares
`android:testOnly=true`, the runner stops before adb unless `--allow-test-only`
was explicitly supplied. After inspecting the packaged manifest, rerun with that
flag and a new output directory; `-t` is then used only for flagged APKs. Unknown
boolean encodings fail closed. Installation always uses `-r`, never clear/uninstall;
equal versionCodes make `-d` unnecessary. An install failure remains a failure.

Each scenario (`login-b`, `refresh`, `logout`, `cookie`) runs:

1. Published seed: create scoped real backend owner A and adopt its session.
2. Candidate check: compare persisted credential digests/user, protected request,
   real refresh, protected request again.
3. Published mutation: check candidate-written state, then login B, refresh,
   sign out, or cookie-mode login B respectively.
4. Candidate final check: verify B/refreshed session and refresh again, or verify
   durable logout: no persisted header access/refresh credentials, no usable
   credential cookies after reopening the cookie store, no
   session in Core for the owned fixture handle, and `attemptRefreshingSession`
   returns false without restoring credentials. These checks also run immediately
   after published logout. Receipts record observed digests, never forced empty
   digests hiding surviving raw tokens.
   Credential/digest assertions use boolean checks with nonsecret labels, so
   assertion failures do not interpolate token values. `getCookies()` filters
   expired cookies: this proves no usable cookie, **not** literal absence of
   serialized cookie entries in XML. Raw XML remains private evidence.
5. Scoped cleanup: SDK sign-out and delete only locally recorded fixture IDs.

Login B asserts a different user. Protected requests assert user and session
handle. Cookie mode checks actual access/refresh cookie digests and absence of
header access. Refresh asserts token rotation. Each stage uses a new process
after targeted force-stop, fenced by run/scenario/backend, phase and process UUID.
No uninstall, `pm clear`, or SDK preference clear occurs. Only the harness receipt
is reset between successfully cleaned scenarios. Empty preference commits flush
SDK writes: this covers completed writes, not dropped-apply/crash consistency.

Exactly one passing `UpgradeTest#runStage` is required per invocation: 20 stages
for all scenarios including cleanup. Commands reuse the reviewed restart runner's
deadlines, cancellation and exact-result parser. Defaults: commands 30s, installs
and tests 120s; flags can override. Host timeout kills the owned adb process group,
but cannot guarantee an already-dispatched Android operation stopped.

## Evidence and recovery

Every successful stage captures typed raw preference XML using `run-as`: SDK
session preferences, receipt, plus cookie preferences for the cookie scenario.
`captures.json` records XML hashes/type tags. `run.json` records artifact hashes,
run ID, serial and arguments. Per-command logs and `summary.txt` record outcomes.
No XML/token values appear in runner console output. **Private evidence contains
live credentials** (umask 077); sanitize before sharing or committing fixtures.
JVM replay remains a separate integration task; raw captures are not copied into
source fixtures automatically.

Failure preserves state. Repeat the original artifact/backend arguments with a
**new output directory**, adding
`--cleanup-only --run-id ORIGINAL_RUN_ID --scenario FAILED_SCENARIO` for scoped
cleanup. Recover the run ID from `run.json` or the final diagnostic. Never use a
global backend reset for recovery.

With the applied backend patch activated, cleanup retries for known completed owner IDs
return success using process-local tombstones. A lost cleanup response or death
before saving the shortened receipt can therefore be retried safely. Unknown IDs
still return false; other owners remain untouched. Backend restart loses both
ownership and cleanup tombstones. A lost **creation** response can still leave an
owner unrecorded locally; no idempotent server creation protocol is implemented.
Before dispatch, the client commits the diagnostic request ID/email; pending
creation blocks cleanup success. Host `recovery.json` retains planned request
identifiers even if instrumentation fails, and the runner prints recovery steps. Stop
and ask the backend owner to identify the exact request's registered fixture;
never guess an ID, delete by broad email matching, or reset globally. See the
handoff for recovery limits. Failed SDK sign-out stops cleanup for inspection.

## Static verification — 2026-10-29

- Shell syntax passed.
- Direct Java 7 source/target compilation against checksum-verified published
  0.5.4, Android 32, AndroidX monitor 1.4.0 and JUnit 4.13.2 passed. This checks
  public API references, not AGP packaging, dexing or API-17 execution.
- 24 deterministic fake-tool tests passed: stage order, install-r/force-stop,
  typed XML, cleanup-only, package/version/signature/hash rejection before adb,
  failed install/stop, invalid XML, wrong/zero results, timeout and SIGTERM;
  baseline-as-candidate rejection, missing/mismatched source provenance, explicit
  testOnly opt-in, selective `-t` and unknown testOnly encoding rejection.
  Failed-stage recovery identifiers/instructions are also checked.
  Archive receipt tests preserve original producer evidence, reject modified
  artifact/source manifests and verify the receipt survives runner preflight.
  Run `python3 testHelpers/upgradeapp/tools/test_upgrade.py`; optional
  `TEST_TMPDIR` selects temporary storage.
- Shared backend ownership and session-state adapter tests passed, including lost
  cleanup response retry, unrelated-owner preservation, independent active/revoked
  lookup and failure propagation. These are fake-SDK tests, not live Core evidence.

Initial staging used no Gradle/device/backend execution. Subsequent authorized
build, backend activation and API34 20-stage results are documented separately
above. API17 remains unverified; R8's narrow qualification is documented separately.
StrictMode and candidate-only restart
evidence belong to their separate lanes.
