# PR #82 compatibility implementation plan

PR [#82](https://github.com/supertokens/supertokens-android/pull/82) adds owner-fenced session state so stale work (late responses, racing refreshes, out-of-scope redirects) cannot mutate a newer session. A review against `master` (published 0.5.4) found regressions that would log out, brick or crash existing apps. This plan keeps the PR's fixes and restores 0.5.4 behaviour for existing clients.

## Goal

An app on 0.5.4 upgrades with no code changes and:

- no user is logged out by the upgrade, an app update, or a rollback/re-upgrade;
- no new crashes or exceptions on existing call paths;
- requests, refresh, sign-out and events behave as in 0.5.4, except that stale work can no longer mutate a newer session.

## Invariants to preserve

These are the reasons for PR #82 and must stay covered by tests throughout:

| # | Fix | Covering suites (existing) |
|---|---|---|
| F1 | A late response/sign-out for session A cannot overwrite or clear session B | `SessionOwnershipTest`, `SessionStateBoundaryTest`, `SessionCookieDeletionTest` |
| F2 | One shared refresh flight per owner/lineage across getters, OkHttp and URLConnection (deadline, 8-flight cap, cooldown) | `SessionRefreshPolicyTest`, `SessionResourceBoundsTest`, `SessionBackpressureContractTest`, `SessionLifecycleTest` |
| F3 | Redirected responses cannot adopt/clear credentials from out-of-scope URLs | `SessionRedirectBoundaryTest`, `SessionSignOutRedirectTest` |
| F4 | `signOut` refreshes reactively on server-rejected access tokens (header and cookie) | `SessionSignOutRefreshTest`, `SessionCookieSignOutTest` |
| F5 | New APIs: snapshots, `installSession`/`clearSession`, `resolveAccessToken`, request tags, `observeSessions`, `reconfigure()` | `SessionLifecycleTest`, `SessionContractAuditTest`, `SessionPersistenceBoundaryTest`, `SessionStorageRestartTest` |

Guiding rule: F1–F4 apply to everyone (invisible to well-behaved apps). Extra strictness (full-tuple validation, fail-closed recovery, `StaleSessionException`) applies only to callers of the new APIs or tagged requests. Everything else returns to 0.5.4 semantics.

### Approved transport correction — 2026-10-30

User chose **preserve legacy; strict opt-in**. This explicitly qualifies D5's
absolute behavior-parity wording: retain the modern no-arg one-shot improvement,
while historical caller-owned retries/follow-ups remain allowed. F2 counts shared
SDK refresh flights/dispatches, not internal OkHttp HTTP exchanges. No mandatory
migration, raised historical floor, or historical strict transport is introduced.
Owned refresh (existing opt-in, OkHttp >=3.14) permits at most one HTTP exchange
per transport execution, not exactly-once processing or one exchange per owner
forever; an owned waiter does not override a legacy leader's policy.

`SessionRefreshFailureModeTest` must separately assert SDK preparations/dispatches,
actual RESET wire observations, unchanged session state, and strict owned
reset/consumed-lost-response bounds. See [dated evidence and source pointers](docs/compatibility-wiki/recovery-2026-10-30.md#approved-transport-contract-correction).

## Decisions (agreed)

| ID | Topic | Decision | Rationale |
|---|---|---|---|
| D1 | Interception scope | Outbound credential attachment uses the 0.5.4 rule (host matches `apiDomain`, or matches `sessionTokenBackendDomain`). Adopting/clearing credentials from a **redirected** response additionally requires the final URL to have the same scheme, host and port as the request sent. | The threat behind F3 is a redirect moving a response somewhere the app didn't call. Requests the app sends directly were already trusted in 0.5.4, which deliberately ignored ports (supertokens-website#217). |
| D2 | Configuration changes between launches | A configuration change at startup **never** clears the session, as in 0.5.4. This includes `apiDomain` host and `sessionTokenBackendDomain` changes. The persisted configuration ID is kept as metadata only. Runtime `reconfigure()` still creates a new in-process configuration, clears and fences in-flight work. | Changing settings in an app update must not log users out. A domain migration of the same backend (e.g. `api.old.com` → `api.new.com`) kept users logged in under 0.5.4; tokens that are invalid for a new backend are rejected by the server and cleared through the normal refresh path. |
| D3 | Transfer method change (cookie ↔ header) | Keep the session, as in 0.5.4. | Cookie → header is a supported migration path. The cookie jar is already documented as outside the fencing guarantee, so no new exposure. |
| D4 | Path in `apiDomain` | Ignored, as in 0.5.4. Refresh/sign-out URLs unchanged. The same-origin path boundary is dropped. | Apps with a path in `apiDomain` work today because it is ignored. Same-origin redirects stay inside the same trust boundary. Prefixes belong in `apiBasePath`. |
| D5 | Version and compatibility | Release as **0.6.0** (new public APIs, new storage format) with **no breaking changes**: every 0.5.4 call path, stored session and behaviour keeps working. No "Breaking" CHANGELOG section. | The version signals the size of the change; compatibility is enforced by the tests in this plan and the API diff gate. |
| D6 | Old-style `EventHandler` events | Delivered synchronously on the thread that caused the transition, after the state lock is released and before the request returns. No stale-owner filter, no backlog cap. Handler exceptions propagate to the caller, as in 0.5.4, after the state is committed. | Restores the ordering and error behaviour apps rely on (e.g. UNAUTHORISED before the 401 reaches the caller) while keeping the PR's fix that no handler runs under the state lock. Coalesced, owner-aware updates stay available via `observeSessions`. |

## Regression inventory

Referenced by milestones and tests below.

| # | Regression | Severity |
|---|---|---|
| R1 | A failed legacy import persists `RECOVERY_REQUIRED`; every request, including login, throws forever | Critical |
| R2 | Import of valid 0.5.4 data fails (cookie + exposed access token without refresh, front token without `lastAccessTokenUpdate`, undecodable JWT, …) | Critical |
| R3 | Cookie mode + `exposeAccessTokenToFrontendInCookieBasedAuth`: login/refresh responses rejected; user never logged in | Critical |
| R4 | Rollback to 0.5.4 then re-upgrade: stale v1 metadata contradicts tokens, leading to R1 | Critical |
| R5 | Second `init`/`Builder.build()` throws `IllegalStateException` (different config or new callback instances, e.g. Activity recreation) | Critical |
| R6 | Responses rejected when `ate`/`exp` ≤ device clock (clock skew, short-lived tokens) | High |
| R7 | Refresh rejected when user ID changes (account linking); session stuck failing | High |
| R8 | Interception requires exact scheme/port/path; `apiDomain` path appended to URLs; `init` throws on query/fragment/negative retries | High |
| R9 | Persisted config ID includes retries/status code/base path/transfer method; any change logs everyone out; cookie→header forces re-login | High |
| R10 | Events: UNAUTHORISED missing on 401 without session and from `attemptRefreshingSession`; cross-thread delivery, stale drops, 64 cap | High |
| R11 | Custom non-Bearer `Authorization` makes a request unmanaged (no anti-CSRF, refresh or token saving) | High |
| R12 | Legacy interceptor refresh deadline cancels `chain.call()` (the user's call) | High |
| R13 | `signOut` throws when the body status is not `"OK"` | Medium |
| R14 | Getters before `init` report logged out / throw (0.5.4 read prefs directly) | Medium |
| R15 | Synchronous commits in `init` and under the global lock that main-thread getters wait on | Medium |
| R16 | Legacy interceptor throws for 401 bodies > 1 MiB | Low |
| R17 | Untagged requests throw `StaleSessionException` on retry after an owner change (contract says untagged stale work returns its response) | High |

## Test infrastructure (current state)

| Layer | Location / run | Notes |
|---|---|---|
| JVM unit | `app/src/test`, `./gradlew :app:testDebugUnitTest` | `SessionTestFixture` fakes `Context` and a map-backed `SharedPreferences`. Can seed prefs, simulate restart (`resetForTests` + rebuild), inject commit failures, use MockWebServer. Cannot simulate real XML, StrictMode, process death. |
| JVM live Core | `app/src/coreTest`, only with `-PcoreProtocolUrl=…` | Depends on `/test/email-change-owner` and `/test/protected`, which are **not** in `testHelpers/server`. Not run in CI. |
| Device | `app/src/androidTest` (`SessionStorageRestartTest` seed/check pairs) | Not orchestrated by any script or CI job. |
| Binary compatibility | `app/src/test/compatibility/OldSdkConsumer` (compiled against published 0.5.4) | Covers only 4 calls. |
| Integration | `testHelpers/testapp` against `testHelpers/server` + Core over the FDI matrix, run by `.circleci/setupAndTestWithFreeCore.sh` | The PR rewrote the assertions that would have caught R5, R6, R8, R9. |

CI gaps: CircleCI runs only on `test-cicd/*` branches and `dev-v*` tags. GitHub Actions only checks the changelog and PR title. Nothing gates PRs on tests. `coreTest` and `androidTest` never run.

---

## Milestone 0: Test foundation and failing baseline

Build the harness first so every later fix lands with a test that reproduces the regression.

### Work

1. **Restore master assertions in `testHelpers/testapp`** (`git show master:<file>`):
   - `ConfigTests.testShouldDoInterceptionBasedOnUrl`
   - `okhttp_`/`retrofit_testThatOldSessionsStillWorkWhenUsingHeaders` (replacing `*_transferMethodChangeClearsSessionAndRequiresNewLogin`)
   - `startST(1)` in the four legacy-session tests
2. **Fixture helpers** in `app/src/test/.../SessionTestFixture.java`:
   - `seedLegacy(...)` that writes 0.5.4 keys (`st-storage-item-st-access-token`, `…st-refresh-token`, `…st-last-access-token-update`, `supertokens-android-fronttoken-key`, `supertokens-android-anticsrf-key`, `st-storage-item-sIRTFrontend`);
   - a token builder `tokens(handle, uid, accessExpMs, frontAteMs, version)` covering access-token v2–v5;
   - promote `restart()` (now private in `SessionRefreshPolicyTest`) and `failCommits()` (in `SessionPersistenceBoundaryTest`) into the fixture.
3. **`RecordingEventHandler`**: records event type, thread, order, and whether delivery happened before the request returned.
4. **`BlockingStorage`** (`SessionStorage` with latch-gated `write`, installed via `SessionState.setStorageForTests`).
5. **`FakeBackend`** (MockWebServer `Dispatcher`): stateful SuperTokens emulation with refresh-token rotation, 401 on stale access, refresh-token reuse detection and overlapping-refresh counting.
6. **`Legacy054Harness`** (differential oracle against real 0.5.4 code):
   - A Gradle `legacy054` configuration resolves `com.github.supertokens:supertokens-android:0.5.4@aar`, verifies the sha256 used by `app/src/test/compatibility/regenerate.sh`, and extracts `classes.jar`.
   - A driver (`app/src/legacyDriver/java/.../Legacy054Driver.java`) compiled against that jar exposes `init`, `login`, `request`, `refresh`, `signOut`, `doesSessionExist`, `getUserId`, `getAccessToken` and URL normalisation.
   - Tests load jar + driver in a child-first `URLClassLoader` for `com.supertokens.*` (parent provides `android.*` shims, OkHttp, `org.json`). Both SDKs then run in one JVM against the same fake `SharedPreferences`; a fresh loader is a fresh 0.5.4 "process".
   - Smoke-test the harness first: 0.5.4 may touch stubbed Android APIs.
7. **Mark tests that currently assert regressed behaviour.** Each is rewritten in the milestone that fixes it:

   | Test | Rewritten in |
   |---|---|
   | `SessionLifecycleTest.normalizedRepeatBuildPreservesOwnerAndExplicitConfigChangeClears`; `SessionStateBoundaryTest.defaultCallbackSubclassesAreNotIdempotent…` | M1 |
   | `SessionRefreshPolicyTest.missingSchemaAndPartialVersionedMetadataFailClosed`, `invalidPersistedUidExpiryAndVersionFailClosed`; `SessionPersistenceBoundaryTest.legacyMigrationCommitExceptionCannotExportCredentials` (keep "no export", add "login still works") | M1 |
   | `SessionConfigurationContractTest.transferModeChangesClearAndRetireOwnerAcrossHeaderCookieHeader` | M3 |
   | `SessionConfigurationContractTest.crossPortRequestCannotDispatch…`, `SessionRedirectBoundaryTest.crossPort*` / `sameOriginOutsidePath*`, `SessionStateBoundaryTest.configurationScopeIncludesSchemePortAndDomainPath`: re-baseline F3 to a foreign **host** (second MockWebServer as `127.0.0.1` vs `localhost`) and to cross-origin redirects | M3 |
   | `SessionTransportPolicyTest.unmanagedExplicitBearerCannotMutateSessionBothTransports`: keep for a foreign Bearer; add non-Bearer case | M4 |
   | `SessionTransportPolicyTest.signoutGeneralErrorAndMalformedBodiesRetainTuple…` | M4 |

### Exit criteria

- Harness smoke test passes. Restored testapp tests fail on the current branch, recorded as the baseline.
- New regression tests from later milestones can be written against the fixtures without new infrastructure.

---

## Milestone 1: No stuck users, safe `init` (R1, R2, R4, R5, R17)

### Work

1. **Recovery never blocks** (`SessionState.applyOrdinaryResponse:709-711`, `SessionTransport.apply:63`):
   - ordinary requests in `RECOVERY_REQUIRED`/`UNAVAILABLE` pass through without throwing and without credentials;
   - a complete authentication response replaces the recovery state via `replaceForResponseLocked`;
   - `RECOVERY_REQUIRED` stays visible in snapshots for new-API callers; old-style getters report logged out.
2. **Lenient legacy import** (`importLegacyLocked`, `validateStoredAvailable(…, false)`, `SessionIdentity.parse`):
   - accept access + front without refresh; front-only; missing `lastAccessTokenUpdate` (synthesise one);
   - an undecodable access token imports with identity from the front token only;
   - fall back to ABSENT only when the front token is unreadable; never enter recovery during import;
   - the raw-write path (`legacyStorageChanged`) uses the same rules.
3. **Rollback / re-upgrade detection** (`SharedPreferencesSessionStorage`, `SessionState.configure`):
   - persist a copy of `lastAccessTokenUpdate` in v1 metadata;
   - on read, if it differs from the legacy key, or the status contradicts which credentials exist, treat the data as written by an older SDK and re-import with the lenient rules.
4. **Idempotent `init`** (`SuperTokens.configure`, `SessionState.configure:118-124`):
   - a second `init`/`build()` is a no-op; log a warning when the configuration differs;
   - `reconfigure()` remains the explicit way to change configuration.
5. **Untagged stale retry returns the response** (`SuperTokensInterceptor.intercept`, `SuperTokensHttpURLConnection.newRequest`):
   - when `nextWire`/`requireCurrent` detects an owner change for an untagged request, return the last response instead of throwing;
   - tagged requests keep throwing `StaleSessionException`.

### Tests

| Test (layer) | Scenario → assertion |
|---|---|
| `LegacyImportRecoveryTest` (JVM) | Unimportable legacy data → status not RECOVERY; login over both transports returns 200, snapshot AVAILABLE. Persisted schema-1 `status=recovery` → restart → login works. First commit fails → next login succeeds, restart shows AVAILABLE. Requests reach the wire without credentials in every state. |
| `LegacyImportMatrixTest` (JVM, parameterized, harness oracle) | Rows: header tuple v2–v5; header expired; cookie front+csrf+lastUpdate; cookie + exposed access without refresh; front without lastUpdate; lastUpdate without front; access+refresh without front; refresh+front without access; opaque/undecodable access; front `uid` ≠ JWT `sub`; front `ate` in the past; stale `sIRTFrontend`. Each row: status ∈ {AVAILABLE, ABSENT}; `doesSessionExist`/`getUserId`/`getAccessToken` equal 0.5.4 on the same prefs; later login works. |
| `RollbackRoundTripTest` (JVM, harness) | Candidate login A → 0.5.4 logs in B → candidate restart is AVAILABLE as B. Variants: 0.5.4 refresh rotation (candidate uses the new refresh token), 0.5.4 sign-out (ABSENT), 0.5.4 cookie login. |
| `RepeatedInitTest` (JVM) | `build()` ×3 with new lambda handlers → no throw, owner and tokens kept. `build()` with changed base path/retries/status code → no throw. testapp: `setInitToFalse()` then `build()` with a different config → no throw. |
| `UntaggedStaleRetryTest` (JVM, both transports) | Untagged 401 → `installSession(B)` during refresh → `execute()` returns a response, B untouched, no request carries B's token. Tagged variant still throws. |
| `SessionStorageRestartTest` pair (device) | `testSeedUnimportableLegacy` / `testCheckUnimportableLegacyAllowsLogin`. |

### Exit criteria

- All M1 tests and invariant suites F1–F5 green; M0 tests for R1, R2, R4, R5 rewritten and green.

---

## Milestone 2: Accept valid server responses (R3, R6, R7)

### Work

1. **Cookie mode with exposed access token**:
   - accept access + front without refresh on ordinary and refresh responses (`applyOrdinaryResponse:741`, `SessionResponse.refreshCandidate:132`, `completeRefresh:572`);
   - send `Authorization` only when both access and refresh exist (0.5.4 `getAuthorizationHeaderIfExists` semantics);
   - `getAccessToken` returns the exposed token.
2. **Clock-independent response validation** (`SessionIdentity.parse:43,106`):
   - compare `ate`/`exp` against the response `Date` header plus a small tolerance; skip the expiry check when there is no `Date`;
   - `installSession` keeps its strict check;
   - device-clock expiry only drives proactive refresh.
3. **User ID change on refresh** (`SessionState.sameStoredIdentity`, `completeRefresh:586`):
   - same session handle and tenant with a different user ID is a same-session promotion (owner kept, ACCESS_TOKEN_PAYLOAD_UPDATED);
   - a different handle or tenant stays rejected;
   - malformed 2xx refresh responses remain temporary failures (never clear the session; captive portals must not log users out).

### Tests

| Test (layer) | Scenario → assertion |
|---|---|
| `CookieExposedAccessTokenTest` (JVM, both transports, `CookieManager`) | Login with access+front(+anti-CSRF)+Set-Cookie, no refresh header → AVAILABLE, `getAccessToken` returns it; 401 → refresh (access+front) → retry 200; `signOut` clears. |
| testapp `okhttp_`/`retrofit_`/`urlconnection_cookieModeExposedAccessTokenLoginRefreshSignOut` | `startST(…, enableJWT:true)` (server sets the expose flag on node ≥14; skip when `/featureFlags.v3AccessToken` is false). |
| `ClockSkewTest` (JVM) | Login response with `ate`/`exp` = now−30s and `Date` = server time → AVAILABLE, next request refreshes; refresh response in the past → accepted, refreshes bounded by `maxRetryAttemptsForSessionRefresh`; `ate` = now+1s accepted. |
| testapp legacy tests | `startST(1)` restored in M0 now green. |
| `RefreshUserIdChangeTest` (JVM) | Same handle, new `sub`/`uid` → accepted, `getUserId` changes, retry 200; different handle → rejected without clearing. |
| testapp account-linking test | New server endpoint `POST /link-accounts` (node ≥16 with account linking): link, force refresh, protected request 200, `getUserId` changes. |

### Exit criteria

- All M2 tests, invariant suites, and the four `startST(1)` integration tests green.

---

## Milestone 3: Interception scope and configuration (R8, R9)

### Work

1. **Outbound scope (D1)**: `Utils.shouldDoInterceptionBasedOnUrl` returns to the 0.5.4 host/backend-domain rule.
2. **Redirect fencing (D1)**:
   - a separate check compares the final response URL's scheme/host/port with the request actually sent;
   - used before adoption/removal/retry in both transports, the old-style OkHttp refresh, and URLConnection cookie handling (`SessionTransport.isResponseInScope` callers).
3. **URL normalisation (D4)** (`Utils.NormalisedInputType.normaliseInputOrThrowError`):
   - stop appending the `apiDomain` path;
   - ignore query/fragment instead of throwing;
   - clamp negative retry counts to 0.
4. **No startup clear (D2)**: remove the configuration-ID mismatch clear in `SessionState.configure` (`persistConfigurationClearLocked`); rewrite the stored ID to the current configuration without touching credentials. Runtime `Configuration` object identity still fences in-flight work.
5. **Transfer method change (D3)**: no clear on startup; header tokens adopted from responses on the same session.

### Tests

| Test (layer) | Scenario → assertion |
|---|---|
| testapp `ConfigTests` (restored in M0) | Green. |
| `InterceptionScopeTest` (JVM, parameterized over apiDomain × backend domain × request URL) | Credentials attached and 401→refresh exactly where 0.5.4 did, including other-port subdomains with a backend domain, http/https and other paths. |
| `RedirectOriginTest` (JVM, both transports) | Re-baselined F3: cross-host and cross-origin (scheme/port) redirects cannot adopt/clear/retry; same-origin redirects can. |
| `UrlNormalisationParityTest` (JVM, harness) | Inputs with path/base path/query/fragment/negative retries: throw-or-not, refresh URL and sign-out URL equal 0.5.4. |
| `ConfigurationUpgradeTest` (JVM) | Session under C1; restart with C2 differing in exactly one of retries/status code/base path/handlers/transfer method/`apiDomain` host/`sessionTokenBackendDomain` → session survives and the next request to the new host carries the credentials (0.5.4 parity via harness). |
| testapp `*_testThatOldSessionsStillWorkWhenUsingHeaders` (restored in M0) | Green. |

### Exit criteria

- All M3 tests and re-baselined F3 suites green.

---

## Milestone 4: Events and request handling (R10, R11, R12, R13, R16)

### Work

1. **Event delivery (D6)** (`SessionState.offerPublishedLocked`, `dispatch`):
   - collect events per commit and deliver on the producing thread after the lock is released, before the request returns;
   - remove the stale-owner filter and the 64-event cap for old-style events;
   - fire UNAUTHORISED on a 401 with no local session (interceptor, URLConnection, `attemptRefreshingSession`);
   - fire SIGN_OUT when a configuration change clears an active session;
   - handler exceptions propagate to the caller as in 0.5.4 (D6), after the state is committed and the lock released.
2. **Custom `Authorization`** (`SuperTokensInterceptor`, `SuperTokensCustomHttpURLConnection.isManagedAuthorization`):
   - a non-Bearer header keeps the caller's value while the request still gets anti-CSRF, token saving and refresh on 401 (all owner-checked);
   - a Bearer value different from the session stays unmanaged.
3. **Refresh deadline** (`SuperTokensInterceptor.refresh`, `RefreshDeadline`):
   - never cancel `chain.call()`;
   - with the no-arg interceptor, rely on the chain's timeouts; followers still give up at 30 s;
   - keep the cooldown and flight cap (F2).
4. **Sign-out body parity** (`SuperTokens.signOutWire`): any status other than `GENERAL_ERROR` is success.
5. **401 buffering** (`SuperTokensInterceptor.intercept`): restore full buffering of 401 bodies for the no-arg interceptor.

### Tests

| Test (layer) | Scenario → assertion |
|---|---|
| `LegacyEventContractTest` (JVM, `RecordingEventHandler`, harness oracle) | 401 without session → UNAUTHORISED; `attemptRefreshingSession` without session → UNAUTHORISED; `signOut` without session → SIGN_OUT; REFRESH_SESSION/SESSION_CREATED/ACCESS_TOKEN_PAYLOAD_UPDATED on the calling thread before `execute()` returns; 200 transitions with a slow handler all delivered in order; a throwing handler surfaces the same exception type as 0.5.4 and the committed state is unaffected. Event sequences for login, refresh, sign-out and revocation flows equal 0.5.4. |
| `CustomAuthorizationTest` (JVM, both transports) | `Authorization: Basic x` with a session → header untouched, `anti-csrf`/`rid` added, front-token adopted, 401 → refresh → retry keeps the custom header. testapp: refresh variant of `/base-custom-auth`. |
| `LegacyRefreshDeadlineTest` (JVM; add `RefreshCoordinator.setNetworkTimeoutForTests`) | Slow refresh on the no-arg interceptor does not cancel the user's call. After a disconnect, a request outside the cooldown attempts refresh again. |
| `SignOutBodyParityTest` (JVM, harness oracle) | Bodies `{"status":"FOO"}`, `{}`, empty, HTML, `GENERAL_ERROR` → exception type and post-state equal 0.5.4. |
| `LargeUnauthorisedBodyTest` (JVM) | 2 MiB 401 → refresh → retry 200; refresh failure → original 401 body readable. |

### Exit criteria

- All M4 tests and invariant suites green; rewritten `SessionTransportPolicyTest` cases green.

---

## Milestone 5: Pre-init reads and main-thread performance (R14, R15)

### Work

1. **Getters before `init`**: a read-only storage fallback for `FrontToken.getToken`, `getUserId`, `doesSessionExist` (unexpired only, no refresh) and `Utils.getLocalSessionState`.
2. **`init` disk work**: no commit on first launch with empty storage; the one-time import commit is acceptable.
3. **Commits outside the global lock** (`SessionState`, `SharedPreferencesSessionStorage`):
   - a dedicated write-serialisation lock performs `storage.write`;
   - the new snapshot is published under the state lock after the commit succeeds, so readers never wait for disk;
   - profile before and after.

### Tests

| Test (layer) | Scenario → assertion |
|---|---|
| `PreInitGetterParityTest` (JVM, harness oracle) | Seed 0.5.4 prefs, no `init`: getters equal 0.5.4. Extend `OldSdkConsumer` to call getters before `build()`. |
| `GetterNonBlockingTest` (JVM, `BlockingStorage`) | A write blocked on another thread does not delay getters (≤200 ms). |
| `StrictModeMainThreadTest` (device, API ≥28 `penaltyListener`) | Pre-warm prefs off main; `build()` + getters on main → zero disk-write violations in `com.supertokens` frames. |

### Exit criteria

- All M5 tests green; F1/F2 suites green (lock change must not weaken fencing).

---

## Milestone 6: Critical-scenario coverage and CI gates

Broader detection of regressions not tied to a single fix. Ranked by value.

| Rank | Test | Layer / mechanism | Effort |
|---|---|---|---|
| 1 | **Upgrade differential suite** (`Legacy054UpgradeTest`): 0.5.4 drives each flow (header/cookie/exposed login, refresh, sign-out, payload update) → candidate restart → parity plus a protected request and refresh. Invariant: never logged out or bricked. | JVM, harness | M |
| 2 | **Real-device upgrade and rollback**: `testHelpers/upgradeapp` with flavors `sdk054` (published dependency) and `candidate` (`:app`), same applicationId. `testHelpers/device/upgrade.sh`: install sdk054 → seed → `adb install -r` candidate → check → `adb install -r -d` sdk054 → check → re-upgrade → check. Capture `shared_prefs/supertokens-android-shared-preferences.xml` from 0.5.4 runs into `app/src/test/resources/upgrade/0.5.4/` and replay in JVM fixtures (catches XML type coercion). | device + JVM | M-L |
| 3 | **Live-backend upgrade across the FDI matrix**: in testapp, 0.5.4 (harness loader) logs in via `/login`, `/login-2.18`, cookie, `enableJWT`; the candidate continues (request, refresh, sign-out). Covers real token versions v2–v5. | testapp | M |
| 4 | **0.5.4 crash consistency**: a prefs proxy that drops every edit after the k-th `apply()`; for 0.5.4 login and refresh, enumerate every k → candidate restart never RECOVERY, login works. | JVM | S-M |
| 5 | **Lost refresh response / process death**: raw client refreshes and discards the response (server rotated) → SDK request still 200 (Core accepts the parent until the child is used). JVM twin with `FakeBackend`. Device seed/check pairs for death mid-refresh and mid-login. | testapp + JVM + device | M |
| 6 | **API diff gate**: japicmp of 0.5.4 `classes.jar` vs candidate; fail on removed/changed public members and on methods added to `EventHandler`, `CustomHeaderProvider`, `PreConnectCallback`. Expand `OldSdkConsumer` to touch every 0.5.4 public symbol (Builder setters, `getAccessTokenPayloadSecurely`, `attemptRefreshingSession`, `signOut`, `newRequest` with a lambda, `SuperTokensPersistentCookieStore`, `SuperTokensCustomHttpURLConnection`, `EventType` values, `SuperTokensGeneralError`). | Gradle | S |
| 7 | **Concurrency stress** (`SessionStressTest`): 16 threads × 500 seeded-random ops (OkHttp, URLConnection, getters, `attemptRefreshingSession`, `signOut`, `installSession`, login responses) against `FakeBackend`. Invariants: no deadlock; ≤1 overlapping refresh per lineage; no request carries another owner's token; no refresh-token reuse; never RECOVERY; flight slots released; exceptions within an allowlist. Log the seed. | JVM | M |
| 8 | **Backend-initiated changes**: `/update-jwt` (payload update, same owner, ACCESS_TOKEN_PAYLOAD_UPDATED); `/update-jwt-with-handle` (visible after refresh); `/logout`, `/revokeAll` (next call → refresh 401 → cleared, one UNAUTHORISED); `/session-claims-error` 403 (no refresh, no clear); new `/login-tenant` (non-public tenant visible in snapshot and through refresh). | testapp | S-M |
| 9 | **Refresh failure modes** (`FakeBackend`): captive portal 200 HTML without tokens; 500/502/503; 429 with `Retry-After`; connection reset; read timeout; header-mode refresh with front token only. Session kept, 0.5.4-equivalent result returned, never cleared, no loop. | JVM | S |
| 10 | **OkHttp matrix**: JVM suite against OkHttp 3.12.13 (last line supporting API < 21; SDK uses `Chain.call()` 3.10+, `Request.tag(Class)` 3.11+, `Call.timeout()` 3.12+), 4.1.0, 4.12.0, 5.x; Retrofit 2.9 + OkHttp 3.14 in testapp. Extra `Test` tasks with forced resolution. | JVM | M |
| 11 | **API 17 runtime**: explicit lint (`abortOnError true`, `error 'NewApi'`); emulator API 19/21 and 34 running `connectedDebugAndroidTest`, restart pairs and the StrictMode test. | device | M |
| 12 | **R8 consumer smoke**: testapp release with `minifyEnabled true`, instrumented login/refresh; cookie persistence across builds with different mappings (`SuperTokensSerializableHttpCookie` uses Java serialization and reflection). Add `consumer-rules.pro` if needed. | device | M |
| 13 | **Cookie-store interplay**: `SuperTokensPersistentCookieStore` survives restart; after `clearSession`/config change with cookies still in the jar, adoption follows the decided rule with no silent resurrection of the old user; no `CookieHandler` in cookie mode. | JVM | S |
| 14 | **Context retention**: an Activity context passed to `init` is stored as the application context. | JVM | XS |

### CI wiring

1. **New required PR workflow** `.github/workflows/unit.yml` (JDK 11, Android SDK 32, no secrets):
   - `./gradlew :app:testDebugUnitTest :app:lintRelease :app:japicmp :app:legacy054Test`;
   - OkHttp 3.12 test task.
2. **CircleCI integration** (existing pipeline):
   - run on PR branches, or require a green `test-cicd/*` run before merge (needs `SUPERTOKENS_API_KEY`);
   - include restored and new testapp tests;
   - port `/test/email-change-owner` and `/test/protected` into `testHelpers/server`, then run `coreTest` across the FDI matrix (`./gradlew :app:testDebugUnitTest -PcoreProtocolUrl=http://127.0.0.1:8080 --tests 'com.supertokens.session.Core*'` in `setupAndTestWithFreeCore.sh`).
3. **Emulator job** (`reactivecircus/android-emulator-runner`, API 21 and 34; API 19 nightly):
   - `connectedDebugAndroidTest`;
   - `testHelpers/device/restart-pairs.sh` (seed → `am force-stop` → check for each `SessionStorageRestartTest` pair);
   - `testHelpers/device/upgrade.sh`;
   - required on release branches and `dev-v*` tags, nightly on master.
4. **Nightly**: stress test ×20 with random seeds, full OkHttp matrix, API 19 emulator, R8 smoke.
5. **Release gate** (`dev-v*`): empty japicmp report (or reviewed allowlist) and a green upgrade/rollback device job before `markAsSuccess.sh`.

### Exit criteria

- Ranks 1–9 implemented; PR workflow and CircleCI integration required for merge; emulator job green on API 21 and 34.

---

## Milestone 7: Documentation and release

1. `publishVersionID` → 0.6.0 (`app/build.gradle`); check `Version.java`.
2. `CHANGELOG.md`: new APIs and fixes; an explicit statement that 0.5.4 integrations need no changes; no "Breaking" section (D5). Note Java 8 bytecode and the OkHttp minimum (3.12) as requirements, and confirm both were already effectively required by 0.5.4 consumers, otherwise treat them as breaking and fix.
3. `SESSION_OWNERSHIP.md`: update for D1–D6, idempotent `init`, recovery behaviour, event delivery. Remove the "intentionally restricts historical behavior" paragraph.
4. Remove or fold in the untracked `SESSION_FIX_CHANGES.md` and `REFRESH_TRANSPORT_MIGRATION.md`.

### Exit criteria

- All milestone exit criteria met; release gate green.
- testapp suite passes with every 0.5.4 assertion restored and unmodified.
- japicmp report against 0.5.4 shows no removed or changed public API (additions only).

## Global guardrails

- Every milestone keeps F1–F5 suites green. The only expected edits to them are the F3 re-baseline in M3 and the rewrites listed in M0.
- Any test changed to accept new behaviour must reference a decision (D1–D6) in its commit message. Restored 0.5.4 assertions in `testHelpers/testapp` must never be modified (D5).
- Each regression R1–R17 has at least one test that fails on the current branch and passes after its milestone.
