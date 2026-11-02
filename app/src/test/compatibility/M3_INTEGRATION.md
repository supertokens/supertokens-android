# M3 + strict-resolution integration — 2026-10-29

Historical execution record; the commands below describe that run. For new standalone
execution use the full suite through [owned entrypoints](../../../../testHelpers/testapp/RUNNING.md),
which prepares and validates artifacts in separate Gradle processes.

Applied M3 hunks onto the green M2 working tree, followed by the reviewed
`android-m2-validation/strict-resolution.patch`. No commits. Existing testapp
assertions were not edited during this integration.

## Changes and regression coverage

- Historical host/backend outbound scope, ignored apiDomain path/query/fragment,
  negative retry clamping, and sent-origin response checks across ordinary,
  refresh, sign-out and manual-cookie flows.
- Startup configuration changes preserve sessions without metadata-only commits.
  Explicit reconfigure remains destructive; the historical transfer hook preserves
  credentials while replacing configuration identity and retiring refresh work.
- `ConfigurationUpgradeTest.transferHookRetiresBlockedRefreshWithoutClearingSession`
  uses a latch-blocked transport and an acquired shared-flight waiter. Cookie/header/
  cookie changes terminate the waiter, reject the old response and old tagged calls
  through both transports, preserve tokens/owner/revisions/adoption without commits,
  then allow a fresh cookie-mode refresh.
- Strict resolution validates immediate, acquisition-recheck and completed-flight
  success paths. Legacy admission remains unchanged. Added expired, malformed,
  mismatched access and cookie-only export regressions.
- The supplied strict test needed fixture isolation: install a CookieManager and
  clear each prior case explicitly. Identical reconfigure is correctly a no-op and
  otherwise carried credentials into the next cookie case. Assertions retained.

## Executed results

- Before M3, all three staged startup configuration tests failed against actual
  compiled M2 classes (`m2-before-m3.log`).
- Targeted integrated run: **131 tests passed**, including blocked-refresh and
  strict-validation regressions, configuration, redirect, persistence, ownership,
  sign-out and R17 suites.
- Full integrated run: **261 JVM + 39 published-0.5.4 oracle tests**, zero failures,
  errors or skips. Release build, API policy gate, japicmp and old-consumer freshness
  verification passed. JVM run includes the precompiled consumer linkage smoke.
- Release AAR: **77 classes, all major version 51 (Java 7)**.
- Restored testapp: **7/7 passed** against the documented live SDK 16.7.0/Core 8.0.0
  pair, including old cookie-session migration, expired-access refresh and URL scope.
  Its existing lifecycle hooks ran serially; no additional server lifecycle commands.
- Fresh instrumentation APK build passed after those gates. No connected tests run.
- `git diff --check` passed.

Logs and archived JVM/oracle/live XML:
`historical external evidence: android-m3/`
(`targeted-integrated.log`, `full-integrated.log`, `live-testapp.log`,
`instrumentation-build.log`). Old release-unit-test reports were not counted.

## Commands

Environment: `JAVA_HOME=/Users/bogdan/.local/share/mise/installs/java/temurin-11.0.25+9`,
`ANDROID_HOME=/Users/bogdan/Library/Android/sdk`.

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*ConfigurationUpgradeTest' --tests '*InterceptionScopeTest' \
  --tests '*SessionConfigurationContractTest' --tests '*SessionRedirectBoundaryTest' \
  --tests '*SessionSignOutRedirectTest' --tests '*SessionPersistenceBoundaryTest' \
  --tests '*SessionStateBoundaryTest' --tests '*SessionResolutionValidationTest' \
  --tests '*UntaggedStaleRetryTest' --tests '*SessionOwnershipTest' --console=plain

./gradlew :app:testDebugUnitTest :app:legacy054Test :app:assembleDebug \
  :app:assembleRelease :app:apiCompatibilityGateTest :app:japicmp \
  :app:verifyOldSdkConsumer --continue --console=plain

# From testHelpers/testapp
./gradlew :app:testDebugUnitTest --tests '*testThatOldSessionsStillWork*' \
  --tests '*testThatRefreshingOldSessionsWorksFineWithExpiredAccessToken' \
  --tests '*ConfigTests.testShouldDoInterceptionBasedOnUrl' --console=plain

# From repository root, after integrated and live gates
./gradlew :app:assembleDebugAndroidTest --console=plain
```

## Artifacts and remaining evidence

APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

SHA-256: `062ae810ba89e5e4ec9d3b05d4eea7659dbdd0c86cb3703be7f830437106ee1d`

Release AAR SHA-256:
`b473f844ce4fb8c173caeadd86460531979325f0b2921afebe2cbd9dd7397139`.

API34 restart/device run is queued, not certified here. API17, historical Android
consumer/R8 certification and fresh seven-lane runtime recertification remain outside
this run. Backend qualification evidence was unqualified (`QUALIFICATION_PAIR_MISMATCH`)
at the preflight; the seven restored cases do not certify account linking or historical
backend pairs. Devops restart/requalification may proceed after this handoff.

Gradle and live-backend execution lanes released **FREE**.
