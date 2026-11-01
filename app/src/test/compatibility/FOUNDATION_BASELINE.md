# Batch 0A/0B baseline — 2026-10-29

Historical execution record. Commands/results below are retained as evidence, not
current startup instructions. Use [owned entrypoints](../../../../testHelpers/testapp/RUNNING.md)
for new standalone runs; direct testapp Gradle is no longer supported.

Candidate: `eda605ba8453bc716b82b83c04628a4c0ac4ba3e`.
Restored assertion source: `3c2ced040ff4764f83f84f5f15e9bc1d0b2e6e0c` (local master).

## Execution

Working JDK: `/Users/bogdan/.local/share/mise/installs/java/temurin-11`
(Temurin 11.0.25+9). The macOS `java_home -V` launcher found no registered runtime;
the installed mise JDK works. Android SDK: `/Users/bogdan/Library/Android/sdk`.

Use these exports for the commands below:

```sh
export JAVA_HOME=/Users/bogdan/.local/share/mise/installs/java/temurin-11
export ANDROID_HOME=/Users/bogdan/Library/Android/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

- Before edits: `./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain` passed
  (42 seconds). A prior invocation was up-to-date, so the forced rerun established the baseline.
- After edits: `./gradlew :app:testDebugUnitTest --tests com.supertokens.session.SessionFixtureHelpersTest --console=plain`
  passed all eight new helper tests.
- After edits: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
  passed; XML reports show **182 tests, zero failures/errors/skips**.
- From `testHelpers/testapp`, after rebuilding the candidate AAR:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*testThatOldSessionsStillWork*' \
  --tests '*testThatRefreshingOldSessionsWorksFineWithExpiredAccessToken' \
  --tests '*ConfigTests.testShouldDoInterceptionBasedOnUrl' --console=plain
```

Seven tests ran; all failed, classified below. An earlier exact ConfigTests filter
used the directory-derived package and discovered no tests; the actual package is
`com.example.example.android`. That invocation is a command-selection error, not an SDK failure.

### Semantic failure

`com.example.example.android.ConfigTests.testShouldDoInterceptionBasedOnUrl`:
`AssertionError` at line 178, the first historical cross-port acceptance assertion.
This is the restored R8 reproducer (Batch 4, D1).

### Environmental failures

All six following tests fail with `ConnectException` before compatibility assertions:

- `com.example.SuperTokensOkHttpTest.okhttp_testThatOldSessionsStillWorkAfterRefreshing` (line 847)
- `com.example.SuperTokensOkHttpTest.okhttp_testThatRefreshingOldSessionsWorksFineWithExpiredAccessToken` (line 921)
- `com.example.SuperTokensOkHttpTest.okhttp_testThatOldSessionsStillWorkWhenUsingHeaders` (line 989)
- `com.example.SuperTokensRetrofitTest.retrofit_testThatOldSessionsStillWorkAfterRefreshing` (line 586)
- `com.example.SuperTokensRetrofitTest.retrofit_testThatRefreshingOldSessionsWorksFineWithExpiredAccessToken` (line 633)
- `com.example.SuperTokensRetrofitTest.retrofit_testThatOldSessionsStillWorkWhenUsingHeaders` (line 690)

`curl --max-time 5 http://127.0.0.1:8080/` independently reports connection refused.
Live Core parity, device behaviour, and lost-refresh-response semantics remain unverified.

## Restoration scope

Restored only the named interception method, the two header-migration methods, and
the four additional one-second-token setups. The header-migration methods themselves
also use `startST(1)` in pinned master (six restored calls total). The existing OkHttp
response-close/error-diagnostic improvements remain intact; the remaining diff against
pinned master in testapp consists only of those improvements.

## Deferred assertion rewrite inventory

Existing fencing assertions remain unchanged. Owners use implementation-batch numbering:

- Batch 2 / M1, D5: `SessionLifecycleTest.normalizedRepeatBuildPreservesOwnerAndExplicitConfigChangeClears`
  and `SessionStateBoundaryTest.defaultCallbackSubclassesAreNotIdempotent…`.
- Batch 1 / M1, D5: `SessionRefreshPolicyTest.missingSchemaAndPartialVersionedMetadataFailClosed`,
  `invalidPersistedUidExpiryAndVersionFailClosed`; and
  `SessionPersistenceBoundaryTest.legacyMigrationCommitExceptionCannotExportCredentials`.
  Keep no-uncommitted-export coverage and add subsequent login recovery.
- Batch 4 / M3, D2/D3: `SessionConfigurationContractTest.transferModeChangesClearAndRetireOwnerAcrossHeaderCookieHeader`.
- Batch 4 / M3, D1/D4: `SessionConfigurationContractTest.crossPortRequestCannotDispatch…`,
  `SessionRedirectBoundaryTest.crossPort*` / `sameOriginOutsidePath*`, and
  `SessionStateBoundaryTest.configurationScopeIncludesSchemePortAndDomainPath`.
  Preserve F3 by using foreign hosts and cross-origin redirects.
- Batch 6 / M4, D5: `SessionTransportPolicyTest.unmanagedExplicitBearerCannotMutateSessionBothTransports`.
  Preserve foreign Bearer assertions; add managed non-Bearer coverage.
- Batch 6 / M4, D5: `SessionTransportPolicyTest.signoutGeneralErrorAndMalformedBodiesRetainTuple…`.
  Oracle exception/post-state characterization must precede rewriting malformed-body expectations.

## Helper contracts and remaining gate

`initializeOnSetup()` defaults to true for existing consumers. Override it for genuine
pre-init setup, then call `initializeSession()`. `seedLegacy(Map)` preserves raw value
types; `seedLegacy(SessionTokens)` supplies the historical keys. `restart(true)` discards
unpersisted preference memory; `restart(false)` only resets SDK state. Existing private
restart helpers remain untouched.

`TestPreferences` distinguishes acknowledged commits, false/throw outcomes both before
and after memory mutation, and explicit apply/flush. `RecordingEventHandler` captures
thread/order and an explicit request-return boundary. `BlockingStorage` gates one write
without blocking its delegate's readers. `FakeBackend` records requests and refresh
overlap, rotates tokens and permits a configured number of parent-token reuses.
Its grace policy is synthetic, not a verified model of Core. R1–R17 reproducers still
belong alongside their production slices; this baseline does not claim the entire M0
exit gate or any oracle/device coverage.

Smallest next production slice: Batch 1's malformed legacy-front classification and
credential-free recovery-to-login path, with a restart plus subsequent-login regression
and failed-commit no-export assertions. Keep explicit install validation strict.
