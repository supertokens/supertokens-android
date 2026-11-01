# M5 read-only getters / empty initialization — 2026-10-29

Applied the staged read-only and fixture-followup patches as minimal hunks onto
M4's 285 JVM / 47 oracle green tree. Reverse applicability checks pass for both
patches. No commits. Initial JVM integration used no backend/device execution;
the corrected API34 StrictMode red/green follow-up is recorded below.

## Scope and checks

- Pre-init legacy getters preserve published marker-presence, expired-payload
  exception/cause and malformed-input behavior. Nineteen oracle cases compare seven
  getters each. Preference editing throws in those tests; no request reaches the
  test server, and snapshot/tag APIs still require initialization.
- Empty first initialization creates ABSENT in memory without commit/apply.
  Five JVM tests cover restart, write-failure independence, first durable mutation,
  coherent existing startup without metadata writes, and one-time import writes.
  Explicit install/clear/resolve remain NOT_CONFIGURED before initialization.
- Existing rollback fixture now explicitly persists absence before altering its
  status, preserving its assertions and intended coherent-metadata setup.
- M4 event exceptions, custom authorization, sign-out and current strict/import
  policy code remain intact; their suites are included in the full run.
- Added `StrictModeMainThreadTest` under `app/src/androidTest`: API28+ tests for
  prewarmed empty and coherent startup on the main thread. The coherent test changes
  configuration to detect metadata-only writes. They detect disk writes/network,
  assert unchanged preferences, and isolate their preference namespace. Fixture
  preparation and cleanup occur outside the monitored main-thread operation.
   Corrected device execution passed after proving the prior reporting race with
   an actual file-write control; see the red/green evidence below.

This is not completion of R15: global-lock disk-write serialization and nonblocking
getters during mutation remain unapplied. Import writes are permitted and tested
separately from the zero-write empty/coherent startup contract.

## Results

- Focused empty-init/configuration JVM and pre-init oracle checks: passed.
- Full JVM: **290 passed**, zero failures/errors/skips.
- Published 0.5.4 oracle: **66 passed**, zero failures/errors/skips.
- API policy, japicmp, old-consumer freshness and debug/release assembly: passed.
- Debug and release: **79 classes each, all major 51 (Java 7)**.
- Instrumentation APK assembly and `git diff --check`: passed.

Environment: Temurin JDK 11.0.25+9 at
`/Users/bogdan/.local/share/mise/installs/java/temurin-11.0.25+9`, Android SDK at
`/Users/bogdan/Library/Android/sdk`. Existing Gradle/AGP wiring unchanged.

```sh
./gradlew :app:testDebugUnitTest --tests '*EmptyInitializationTest' \
  --tests '*ConfigurationUpgradeTest' :app:legacy054Test \
  --tests '*PreInitGetterParityTest' --offline --console=plain

./gradlew :app:testDebugUnitTest :app:legacy054Test :app:assembleDebug \
  :app:assembleRelease :app:apiCompatibilityGateTest :app:japicmp \
  :app:verifyOldSdkConsumer :app:assembleDebugAndroidTest \
  --offline --continue --console=plain
```

## Artifacts

Release AAR: `app/build/outputs/aar/app-release.aar`

SHA-256: `d597ce11a0490b0830b6ad14b14bf0b2af5448dde2c0c2d358e366f87148fd9a`

Debug AAR SHA-256:
`9d4aaaaf43e6a72d4ab3f5d2621c157301b3da600a9128341cc6503747921ac1`

Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Original APK SHA-256: `645cb031bef039cf16ac90d264077e3f8d728399cda761a40d9b6294f05d97e9`.
Its prior **2/2 StrictMode result is INVALID performance evidence**, superseded below.

Immutable artifact copies and full result XML:
`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/android-m5-readonly/integration/`.
Parent directory holds `focused-integrated.log`, `full-integrated.log`, staging
oracle/failing-before evidence and `HANDOFF.md`.

## Corrected StrictMode API34 red/green proof

Reviewer `ses_e77d447bcffeuSKXc4WxLGPgH4` identified premature policy restoration:
Android reports violations in a subsequent front-of-queue main-loop message and
reads the current thread-local listener then. Restoring inside the monitored action
can lose the callback. The old 2/2 result must not support zero-write claims.

Executed on **emulator-5580, API34**, targeting only
`com.supertokens.session.test/androidx.test.runner.AndroidJUnitRunner`:

- Red APK: added the real file-write control with the old early-restoration timing.
  **1 executed, 1 expected assertion failure**: `Monitor missed actual main-thread
  disk write: []`. File open/write/fsync and the one-byte size assertion succeeded;
  this was not a timeout, crash or setup failure.
- Green: removed only the premature restoration. Policy stays installed until a
  normal main-queue barrier behind Android's reporting message. Direct-executor
  listener delivery finishes before the captured prior policy is restored, and the
  restoration finally block signals completion. All green calls completed through
  that barrier; no cleanup watchdog fired.
- Fixed control alone: **1/1 passed** with an actual `DiskWriteViolation` received.
- Full class: **3/3 passed**, zero failures/skips: control plus prewarmed empty and
  coherent startup. Startup cases observed zero disk-write/network violations and
  unchanged preferences. No filters weakened.

Waits are bounded to 10 seconds plus 5-second cleanup; external instrumentation
supervision uses a 90-second timeout with package-scoped termination only on timeout.
No timeout occurred. Status codes, exact counts and final JUnit summaries were checked,
not just adb exit codes. Each APK was copied/hashed before installation; green was
installed once and used for the two separate invocations. Upgrade-app retained state
and backend processes were untouched. Production unchanged, so no repeat JVM gate.

Build for each variant: `./gradlew :app:assembleDebugAndroidTest --offline --console=plain`.
Instrumentation command: `adb -s emulator-5580 shell am instrument -w -r -e class
<selector> com.supertokens.session.test/androidx.test.runner.AndroidJUnitRunner`.
Selectors: `com.supertokens.session.StrictModeMainThreadTest#monitorDetectsActualMainThreadDiskWrite`
for red/green control; `com.supertokens.session.StrictModeMainThreadTest` for full green.

Red APK SHA-256:
`c9752935776f38ae0f5b56db9351bd72ad42c6785adff4964120896a5498444b`

Fresh green APK SHA-256:
`dd39c37c9983244a7c119e8433ef8327cd62e9a8ad92c2e56f8bdb9fc7b55b5b`

Private immutable APKs, source snapshots, build/install/instrumentation logs and
parsed `red-result.json` / `green-result.json`:
`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/android-m5-strictmode-review/`.

Green result manifest SHA-256:
`2371a065e546e15f68f55828eefaaf595896693743541b09d17eb6b45e729833`

Migration's allowed commit remains separately documented by the JVM tests. This
startup-only result does not complete R15/global-lock or concurrent-mutation work.

Gradle and DEVICE lanes **FREE**. Backend ownership unchanged.
