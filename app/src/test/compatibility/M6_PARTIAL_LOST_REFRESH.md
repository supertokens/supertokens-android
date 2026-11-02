# M6 partial writes and lost refresh response

Verified 2026-10-29 against published Android 0.5.4 (existing checksum pin),
candidate working tree, Node SDK 16.7.0 / Core 8.0.0.

## Rank 4: published partial writes

`legacy054/LegacyPartialWriteCrashTest` drives actual published login and refresh
through `Legacy054Harness`. Both perform six applies, no commits, in this order:
refresh, access, last-access-update marker, sIRTFrontend, front token, anti-CSRF.

The dedicated `CrashCheckpointPreferences` retains every edit in process memory
but only the first k applies on modeled disk. Crash discards the memory suffix;
candidate writes are then enabled. Two helper tests check typed values, sets,
clear/remove, editor reuse, lost applies and post-crash commits. This is a
durable-prefix model, not an Android filesystem or device-death test.

All k=0..6 were exercised for both flows: **14 checkpoints**, including zero,
first, last partial and complete. Each passed two candidate initializations and
a subsequent candidate login with durable credentials:

- Login k=0..4: ABSENT; k=5..6: AVAILABLE.
- Refresh k=0..6: AVAILABLE.
- No checkpoint remained in RECOVERY. Partial availability is not a claim that
  mixed credentials remain usable against a real backend.

## Rank 5: discarded refresh response

`LostRefreshResponseTest` has two synthetic cases: one parent grace use recovers;
zero grace rejects reuse and leaves the session absent.

`CoreLostRefreshResponseTest` creates a scoped real owner, installs its login,
then raw-refreshes with the current refresh token. It verifies rotation but
discards the child without publishing SDK state/preferences or using child access.
Forced SDK refresh with the retained parent succeeds; intercepted protected access
returns 200 with the same user and session handle; another forced refresh succeeds.
Scoped owner cleanup passed. No additional backend route was required.

This proves parent grace **before child use** for this actual pair. The separate
account-linking gate remains `backend_proven_unsupported / LINK_REVOKED_SESSION`;
its successful exclusion gate is **not an R7 pass**.

## Verification

Focused results: **4 oracle + 2 synthetic JVM + 1 live Core = 7 tests passed**.
The two parameterized oracle cases enumerate the 14 checkpoints internally.
Android test Java compilation also passed; no device execution.

With Temurin 11 and Android SDK 32:

```sh
./gradlew --no-daemon :app:legacy054Test --tests '*LegacyPartialWriteCrashTest' --tests '*CrashCheckpointPreferencesTest'
./gradlew --no-daemon :app:testDebugUnitTest --tests '*LostRefreshResponseTest'
# First run testHelpers/server's test:account-linking-gate with the current runtime identity.
./gradlew --no-daemon -PcoreProtocolUrl=http://127.0.0.1:8080 :app:testDebugUnitTest --tests '*CoreLostRefreshResponseTest' :app:compileDebugAndroidTestJavaWithJavac
```

Evidence: `historical external evidence: m6-partial-lost-refresh-9cj3xrrl`
(build/gate logs, oracle/live JUnit XML, capabilities, post-run health).
Post-run Express `/healthz` and Core `/hello`: both 200.

Only new tests/helpers and this report were added in this slice. Shared fixtures,
production and existing assertions were not edited; the pending legacy-stale fix
was not applied. Full suites were not rerun. No commits or backend shutdown/reset.

Remaining rank-5 gap: real-device process death mid-refresh and mid-login, followed
by durable restart/recovery. The live test loses a response without killing a
process, and the oracle models crash persistence in JVM memory.
