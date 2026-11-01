# Compatibility implementation work plan

Execution companion to [COMPATIBILITY_PLAN.md](COMPATIBILITY_PLAN.md). That document owns decisions D1–D6, regressions R1–R17, invariants F1–F5, and the acceptance scenarios. This document turns them into ordered implementation batches.

2026-10-30 approved correction: preserve legacy; strict opt-in. Apply the plan's
transport correction when interpreting D5/F2: bound SDK flight/dispatch counts
separately from caller-owned HTTP retries. Owned >=3.14 bounds exchanges per
execution only. Modern no-arg one-shot behavior remains an intentional improvement,
not universal host-extension control. No production transport change is required
for this test/contract correction; historical RESET remains exercised and counted.

Status **2026-10-30: implementation and scoped local execution recorded; qualification remains open**. See [current evidence and outstanding gates](docs/compatibility-wiki/status-2026-10-30.md). The original planning-only statement (no production/test changes or execution yet) is superseded, not a description of today's working tree. Original inspection used local `master` (`3c2ced0`); the published, checksum-verified 0.5.4 AAR remains the behavioural oracle. Batch text below preserves the implementation plan, not a completion checklist.

## Implementation strategy

1. Establish the differential harness and record failing compatibility cases.
2. Fix storage/import and request recovery before changing response acceptance.
3. Restore configuration, transport, and event contracts in small batches.
4. Move persistence outside the state lock only after those contracts are covered.
5. Complete upgrade/device coverage and make the gates required before release.

Keep owner/configuration/revision checks in `SessionState`. Put transport policy shared by OkHttp and URLConnection in `SessionTransport`. Keep storage classification in `SharedPreferencesSessionStorage`. Avoid a second legacy session state machine: both legacy and explicit callers operate on the same snapshot, with different validation/error policies at their entry points.

Every production batch includes its reproducer, permitted updates to existing assertions, and the applicable F1–F5 checks. Baseline failures are tracked by name, not silently skipped. Move each fixed regression into the green gate immediately. The full suite must be green when all compatibility batches finish; milestone-specific known failures must not be confused with newly introduced failures.

## Details to settle before coding their batch

### Published behaviour versus proposed behaviour

- **Expiry validation:** M2 proposes rejecting responses using server `Date`. That can still reject a response 0.5.4 accepts, and conflicts with the broad legacy-parity rule. Characterize expired responses with valid, absent, malformed, and duplicate `Date`. Recommendation: no expiry-based rejection on legacy response paths; device time drives proactive refresh, strict `installSession` remains strict. If server-time rejection is retained, explicitly define its scope and tolerance before M2.
- **Sign-out:** local `master` accepts an unknown string status, but `{}`, empty bodies, and HTML still throw `IllegalStateException`. It also processes response headers through `newRequest` before parsing the body. Test exception **and post-state** with/without removal headers. “Anything except GENERAL_ERROR succeeds” must not accidentally swallow historical parse errors or change when headers take effect.
- **Pre-init:** include `getAccessToken` and unexpired `getAccessTokenPayloadSecurely`, not just the getters listed in M5. Local `master` reads those from preferences too. Characterize expired/malformed cases separately; the planned no-network fallback is a specified subset, not proof of parity for every pre-init input.
- **Refresh completion events:** define D6 as synchronous delivery on the transition-producing thread. Shared refresh emits once on the leader, not once per follower. Test whether followers must wait for that delivery before returning; currently `flight.succeed()` wakes them before `dispatch()`. Any stronger ordering needs a reentrancy-safe completion protocol.
- **Device coverage:** API 19/21 does not establish the declared API 17 runtime floor. Keep lint plus oldest-runtime coverage explicit; either add API 17 execution where feasible or document the remaining verification gap. Do not silently raise minSdk.

These are acceptance questions, not reasons to block independent harness work. Record oracle results and resolve each before its production batch.

## Batch 0 — Reproducible baseline and shared fixtures (M0)

### 0A. Freeze the baseline

Files: `testHelpers/testapp/app/src/test/java/com/example/example/`, `app/src/test/compatibility/`, `app/build.gradle`.

- Pin the master revision used to restore assertions. Restore only the cases named in M0, including the four `startST(1)` calls.
- Inventory every existing assertion listed for rewrite in M0. Record its owning batch and D decision; preserve unrelated fencing assertions.
- Run current JVM suites and restored testapp suites with their required backend. Record command, commit, failing test names, and whether failures are semantic or environmental.
- Add at least one reproducible baseline test for every R1–R17 before applying its fix. Tests can land alongside their fix with the pre-fix failure recorded; do not make the default suite permanently red just to stage later work.

### 0B. Extend the existing fixture

Files: `app/src/test/java/com/supertokens/session/SessionTestFixture.java` and adjacent test helpers.

- Split context/preferences/server setup from SDK initialization. The current `@Before` always builds the SDK, which prevents genuine pre-init and seed-before-init tests.
- Add `seedLegacy`, versioned token builders, restart, and configurable commit failures. Preserve raw preference value types for XML replay.
- Model acknowledged writes, commit returning false, commit throwing, and in-memory changes despite failed persistence as distinct cases. The current map fake treats `apply()` and `commit()` identically.
- Add `RecordingEventHandler`, latch-controlled `BlockingStorage`, and `FakeBackend`. Use latches/barriers for races rather than sleeps.
- Give `FakeBackend` deterministic request logs, refresh overlap counters, rotations, and parent-token grace behaviour. Verify the latter against live Core before using it as an oracle for lost responses.
- Restore process-global `CookieHandler` and close clients, classloaders, and servers after each test.

### 0C. Build the 0.5.4 oracle

Files: `app/build.gradle`, new `app/src/legacyDriver/java/com/supertokens/session/Legacy054Driver.java`, new JVM `Legacy054Harness`.

- Resolve the AAR through a dedicated configuration; verify SHA-256 `2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9` before extraction. Share that pin with `regenerate.sh`.
- Compile the driver against only the old SDK plus required Android/dependency classes. Candidate SDK classes must not enter that compiler classpath.
- Child-load the driver and `com.supertokens.*`; parent-load Android shims, OkHttp, JSON, and shared preferences interfaces. Exchange strings, primitives, maps, and shared interfaces across reflection, never SDK-specific objects.
- Smoke-test build, one preference read, login, refresh, and close/reopen of a fresh loader. Verify static SDK state is isolated and preference state is shared only where intended.
- Differential tests use equivalent isolated initial stores/backend state. Only upgrade/rollback tests intentionally share a store sequentially between SDKs. Running two SDKs against the same rotating session concurrently would invalidate the oracle.
- Create `legacy054Test` with the Android JVM test runtime/shims, explicit inputs, and a failure if no tests are discovered. Avoid running the same differential classes twice under the normal test task.

Gate: harness smoke green; named baseline recorded; fixture supports all later JVM scenarios.

## Batch 1 — Import, rollback, and reachable recovery (M1: R1, R2, R4)

Files: `SharedPreferencesSessionStorage.java`, `SessionStorage.java`, `SessionIdentity.java`, `SessionState.java`, `SessionTransport.java` under `app/src/main/java/com/supertokens/session/`.

### Storage classification and parsing

- Separate front-token decoding from strict header-tuple validation. Legacy identity comes from the readable front token; access-token parsing is best effort and must not override front `uid` or reject opaque/mismatched JWTs.
- Distinguish plain legacy data, coherent versioned data, evidence of older-SDK writes, and damaged versioned data. Classify rollback evidence before strict metadata validation can throw.
- Add a metadata copy of the legacy update marker, written atomically with the tuple. Define migration of existing schema-1 records that lack this field; absence alone must not make all existing records invalid.
- Detect status/credential contradictions in addition to marker changes. Test same-millisecond marker collisions and cookie/front-only changes; a marker alone is not proof that the tuple is unchanged. Add tuple evidence if the oracle demonstrates a missed rollback write.
- Use the same compatible validation when reloading a leniently imported schema-1 record. Today `snapshotFromRecord()` calls strict `validateStoredAvailable()` again, so merely relaxing `importLegacyLocked()` is insufficient.
- Keep explicit `installSession` validation strict. If persisted provenance is needed to distinguish strict installs from compatible imports, make it explicit and backward-readable rather than inferring it from access-token presence.

### Recovery and raw writes

- Unreadable legacy front tokens produce ABSENT, not persisted recovery. Persistence failures remain visible to explicit callers and never export uncommitted credentials.
- Ordinary untagged requests from RECOVERY_REQUIRED/UNAVAILABLE go to the wire without credentials. A valid authentication response can replace the recovery owner after the normal owner/configuration/revision checks.
- `SessionTransport.apply()` must not turn a pre-existing recovery state into an exception for an otherwise ordinary response. Keep error mapping for a new persistence failure explicit and covered by the oracle/new-API contract.
- Audit `Utils.storeInStorage`, `FrontToken.setItem`, `AntiCSRF.setToken`, and `saveTokenFromHeaders` together. They write sequentially. Importing ABSENT after the first raw access-token write must not delete that access token before the refresh/front setters execute.
- Separate the in-memory interpretation of an incomplete raw write from destructive durable cleanup. Add a regression that uses the actual public legacy setters to construct a session, not just atomic fixture seeding.

Tests: `LegacyImportRecoveryTest`, `LegacyImportMatrixTest`, `RollbackRoundTripTest`, raw-setter sequence coverage, and existing persistence/ownership suites. Every accepted import row gets a second candidate restart and a later login. Check rollback login, refresh, logout, cookie login, and failed import commit.

Gate: R1/R2/R4 reproducers green; malformed storage cannot permanently prevent login; candidate→candidate and candidate→0.5.4→candidate restarts work.

## Batch 2 — Idempotent init and stale retry handling (M1: R5, R17)

Files: `SuperTokens.java`, `SessionState.java`, `SessionTransport.java`, `SuperTokensInterceptor.java`, `SuperTokensHttpURLConnection.java`.

- Return from repeated legacy init before validating a replacement configuration. Local `master` performs its no-op check before normalization, so even a malformed second configuration must not newly throw. Warning generation must be best effort and cannot replace the active callbacks/context/configuration.
- Serialize concurrent first initialization. Explicit `reconfigure()` still performs its documented replacement; keep test-only initialization/transfer-method helpers compatible with restored testapp expectations.
- Cover every stale edge: resolution returns STALE_OWNER, `nextWire`, retry-loop entry, callback completion, and the last pre-dispatch owner check. Fixing only `nextWire` leaves other exceptions reachable.
- Keep the previous response open until the next attempt has passed its eligibility checks. On an untagged stale retry, return that response without attaching B's tokens. Tagged paths retain `StaleSessionException`.
- For ownership changes before any response exists, define and test a credential-free legacy fallback rather than inventing a response or borrowing the new owner's credentials.
- Audit legacy `signOut` and getters for leaked stale-owner exceptions too: the guiding rule is broader than interceptor R17. Late sign-out must leave B untouched and follow the legacy-call error policy.

Tests: `RepeatedInitTest`, `UntaggedStaleRetryTest` across both transports, no-response race, and F1/F4/F5.

Gate: repeated builds preserve configuration/owner; all stale retry outcomes preserve readable response bodies and never retry as B.

## Batch 3 — Server response acceptance (M2: R3, R6, R7)

Files: `SessionResponse.java`, `SessionIdentity.java`, `SessionState.java`, `SessionTransport.java`, `SessionResolution.java`, `SuperTokens.java`.

- Replace access-token-presence-as-header-mode with explicit credential shape/transfer policy. Distinguish a full header tuple from a cookie session exposing an access token.
- Permit access+front without refresh in cookie login/refresh, including replacement of recovery state. Do not accidentally accept a header-mode front-only refresh.
- Attach Authorization only when both header credentials exist. Use the shared helper in `signOutWire` as well: it currently attaches access directly.
- Restore legacy `getAccessToken` for exposed cookie tokens. `SessionResolution.getAccessToken()` currently goes through `snapshot.getTokens()`, which deliberately hides incomplete header tuples; choose the legacy accessor implementation without accidentally changing the new snapshot export contract.
- Implement the agreed clock policy from the acceptance questions. Keep structural parsing separate from expiry policy and retain strict installation checks.
- Split refresh continuity from ordinary-response replacement identity. Same known handle+tenant permits a changed user ID during refresh; different handle/tenant does not. Define conservative behaviour for imported/cookie sessions without a handle and test later enrichment to a known handle.
- Commit refresh rotation and identity changes atomically, retain the owner, and emit payload-update then refresh events in oracle order. Malformed 2xx refresh stays a temporary failure and retains the session.

Tests: `CookieExposedAccessTokenTest`, `ClockSkewTest`, `RefreshUserIdChangeTest`, restored one-second-token tests, and feature-gated exposed-token/account-linking integration cases.

Gate: header and cookie refresh/sign-out work; bounded retries under skew; F1–F5 stay covered.

## Batch 4 — Scope, startup configuration, and migration (M3: R8, R9)

Files: `Utils.java`, `SessionTransport.java`, `SessionState.java`, both transports, `SuperTokens.java`.

- Restore the exact historical host/backend-domain matching rules, including leading-dot semantics. Use differential URL cases rather than a new approximate suffix matcher.
- Change response eligibility to accept the sent URL and final response URL. Compare effective scheme/host/port; do not compare the final URL only with `apiDomain`. A direct request to another accepted port remains valid.
- Use that check before token adoption, removal, retry, and SDK-controlled cookie handling in ordinary, refresh, and sign-out flows. Same-origin path redirects are allowed by D4. Ambient automatic cookie jars remain outside the fencing guarantee.
- Stop appending `apiDomain` paths; restore query/fragment handling and negative-retry behaviour. Differential-test runtime retry outcomes as well as normalized values: clamping changes internal representation even when zero attempts is equivalent.
- Remove startup configuration-mismatch clearing. Preserve tuple, revisions, and adoption metadata while presenting the current configuration ID. Persist metadata with the next durable write where possible; an eager metadata-only commit conflicts with M5's main-thread goal.
- Preserve cookie/header sessions across startup changes. Keep explicit runtime `reconfigure()` clearing/fencing, and distinguish it from the public test-only transfer helper used by historical tests.

Tests: restored `ConfigTests` and old-session migration assertions, `InterceptionScopeTest`, `RedirectOriginTest`, `UrlNormalisationParityTest`, `ConfigurationUpgradeTest`.

Gate: direct cross-port calls work while cross-origin redirected responses cannot mutate/retry. Re-baseline only the F3 cases identified in M0, citing D1/D4.

## Batch 5 — Synchronous legacy events (M4: R10)

Files: `SessionState.java`, `SessionCommitResult.java` or an internal transition result, `RefreshCoordinator.java`, `SuperTokens.java`, `SessionTransport.java`, `SessionSubscription.java`.

- Replace the shared capped legacy-event queue with an operation-owned event batch containing the handler/configuration captured for that transition. Keep this internal; no callback interface additions.
- Publish state under the lock, release it, then invoke the batch on the producing thread. Remove legacy owner filtering and exception swallowing. Dispatch once even if later work changes the owner.
- Keep observer coalescing and observer exception policy separate. A slow observer must not move or swallow a legacy event batch.
- Dispatch outside both state and future write locks. Handle callback reentrancy deliberately: a handler may call getters, install, clear, sign-out, or reconfigure.
- Add UNAUTHORISED at the legacy no-session entry paths, avoiding duplicate events when response removal already emitted it. Keep absent sign-out and runtime reconfigure SIGN_OUT.
- Complete/release refresh flights reliably even if a callback throws. Do not let transport exception wrapping convert a handler's runtime exception into a new IOException. Apply the settled leader/follower event-ordering contract.
- Define ordering as the oracle establishes it: ordered events within a transition and sequential calls. Do not introduce a global callback lock merely to serialize independent producer threads; that can deadlock reentrant handlers.

Tests: `LegacyEventContractTest`, >64 transitions, owner replaced before delivery, throwing and reentrant handlers, slow handler with concurrent refresh followers, observer coexistence.

Gate: synchronous thread/order/error parity, committed state retained after handler exceptions, no lost events or stranded flights.

## Batch 6 — Request and sign-out parity (M4: R11, R12, R13, R16)

Files: `SuperTokensInterceptor.java`, `SuperTokensCustomHttpURLConnection.java`, `SuperTokensHttpURLConnection.java`, `RefreshDeadline.java`, `RefreshCoordinator.java`, `SuperTokens.java`.

- Represent authorization policy as managed SDK Bearer, managed caller non-Bearer, or foreign Bearer. Separate “participates in session handling” from “SDK may replace Authorization”.
- Preserve Basic/custom values on retries, attach anti-CSRF/rid, and permit owner-checked token saving. Verify actual downstream authorization is still the expected custom value; `SessionTransport.matches()` currently only understands Bearer.
- For no-arg OkHttp refresh, remove scheduled cancellation and SDK deadline enforcement against the user's call. Preserve chain timeouts, follower wait bounds, cooldown, and the eight active-flight cap. Count timed-out followers' still-running leaders until transport termination.
- Keep independent cancellation/deadlines for SDK-owned refresh calls and URLConnection. Make timeout overrides package-private and reset them between tests.
- Remove the 1 MiB legacy 401 cap. Buffer fully before the legacy chain is reused; retain content type and original readable body for return paths, closing replaced bodies exactly once.
- Implement sign-out status/parsing/post-state parity established by Batch 0; preserve reactive refresh and redirected-response fencing.

Tests: `CustomAuthorizationTest`, `LegacyRefreshDeadlineTest`, `SignOutBodyParityTest`, `LargeUnauthorisedBodyTest`, F2/F3/F4.

Gate: caller's call is never SDK-cancelled by legacy refresh, custom auth survives retry, large 401 is readable on non-retry outcomes.

## Batch 7 — Pre-init reads and persistence locking (M5: R14, R15)

### 7A. Read-only compatibility getters

Files: `FrontToken.java`, `Utils.java`, `SuperTokens.java`.

- Add a shared read-only legacy preference reader for pre-init paths. It does not initialize global state, migrate storage, write metadata, or refresh.
- Cover all historically readable getters identified above; preserve null/exception distinctions using the oracle.
- Empty first initialization constructs ABSENT in memory without a commit. Existing coherent startup state should not require metadata-only synchronous writes. Keep one-time import persistence separately measured.

### 7B. Serialize mutations without blocking readers on disk

Files: `SessionState.java`, `SharedPreferencesSessionStorage.java`, existing `SessionStorage` seam.

Use one internal mutation protocol for install, clear, response promotion/replacement, refresh completion, recovery, raw writes, and reconfigure:

1. Acquire a dedicated write-serialization lock **before** the state lock. No path may wait for the write lock while holding the state lock.
2. Under the state lock, recheck owner, configuration object, and applicable access/refresh revisions. Build an immutable pending record and reserve the transition.
3. For replacement/clear, invalidate the old owner and retire the relevant flight before releasing the state lock. Publish only a credential-free transient view while persistence is pending. Promotion may retain the last committed snapshot; explicitly prevent a second refresh flight from starting during pending completion.
4. Release the state lock and perform the synchronous storage write while holding write serialization. Readers never take this lock.
5. Reacquire the state lock, verify the reservation, and publish the durable result or recovery state. Complete the flight and capture events as appropriate.
6. Release both locks before event/observer callbacks.

All state-changing paths must participate. A check-after-write alone is insufficient: a stale writer can already have overwritten newer disk state. Raw preference setters must join serialization before touching legacy keys. Audit lock order with `SuperTokens.class` and flight monitors too.

Keep failure handling inside the same protocol; a recovery write cannot race past a later successful login. Do not replace `commit()` with `apply()` to make timings pass: explicit durable commit results still matter.

Tests: `PreInitGetterParityTest`, expanded old consumer, `GetterNonBlockingTest`, and races for blocked promotion/clear/install/reconfigure/raw write followed by restart. Assert getters finish while the latch remains blocked, then use a generous watchdog rather than relying only on a fragile 200 ms wall-clock threshold. Add StrictMode API ≥28 coverage with pre-warmed preferences; measure migration separately.

Gate: no state-lock disk I/O, no uncommitted credential export, final disk tuple equals final published committed tuple, F1/F2 persistence races green.

## Batch 8 — Upgrade coverage and CI (M6)

Start harness/build wiring early; make checks required after their suites are green.

### Required coverage before release

- Implement M6 ranks 1–9: differential upgrades, device rollback, live FDI upgrades, partial 0.5.4 writes, lost refresh response/process death, API diff, stress, backend changes, and refresh failure modes.
- Build `testHelpers/upgradeapp` with the same applicationId, signing key, and compatible versionCode across flavours. Use installed seed/check commands, not tests that reinstall or clear application data between phases. Capture real XML with value types and replay it in JVM fixtures.
- Orchestrate each `SessionStorageRestartTest` seed/check pair explicitly. Generic `connectedDebugAndroidTest` is not a process-restart test and must not run seed/check methods in arbitrary order alongside the orchestration.
- Reuse the legacy driver in testapp JVM tests through an explicit test artifact/classpath setup: testapp is a separate Gradle build consuming `app-debug.aar`, so root test classes are not automatically available.
- Add backend endpoints in `testHelpers/server` with capability checks for each Node/Core pair. Treat missing features as explicit matrix exclusions, not passing coverage.
- Make stress invariants aware of permitted states: no RECOVERY in the healthy-storage stress run; injected persistence failures belong in separate expected-recovery tests. Audit credentials against captured owner/lineage, and check flight cleanup after all transport threads terminate.

### Gates

- Add `.github/workflows/unit.yml` with the pinned working JDK/Android SDK toolchain; validate AGP 4.1.2/Gradle compatibility before choosing additional tooling. Run JVM, legacy oracle, lint, API diff, and the oldest supported OkHttp task. Publish test reports and dependency-resolution output.
- Configure japicmp for no removed/changed public members; additions are expected. Explicitly fail callback-interface additions. An “empty report” means empty **disallowed-change** report, not zero additions. Expand and regenerate `OldSdkConsumer` against 0.5.4, then run it against the candidate.
- Audit published 0.5.4 bytecode/dependency requirements before asserting Java 8 and OkHttp 3.12 are non-breaking. If the old runtime floor is lower, preserve it or resolve the conflict with D5; do not merely document a new minimum.
- Create isolated OkHttp matrix runtime configurations resolving OkHttp, MockWebServer, TLS helpers, Okio, and Kotlin consistently. Avoid global forcing and compile/runtime method mismatches. Run 3.12 on PRs; full matrix nightly.
- Wire live Core tests into `.circleci/setupAndTestWithFreeCore.sh` after server readiness, alongside restored testapp cases. Provision the test endpoints before enabling the job.
- Add emulator jobs for API 21/34, restart orchestration, upgrade/rollback, plus API 19 nightly and API ≥28 StrictMode. Schedule R8/cookie serialization smoke and the remaining ranks 10–14 explicitly; M6's rank 1–9 exit criterion does not complete these automatically.
- Require green release checks before `markAsSuccess.sh`. Required GitHub/CircleCI checks need repository branch-protection configuration as well as workflow files; record that administrative step and the trusted execution path for secret-backed integration runs.

Gate: ranks 1–9 implemented, required PR/integration checks configured, device upgrade/rollback green on API 21/34, and remaining matrix/runtime checks accounted for.

## Batch 9 — Documentation and release (M7)

- Set `publishVersionID` to 0.6.0. Inspect `Version.java` for FDI support rather than changing protocol versions just to match the release number.
- Update `SESSION_OWNERSHIP.md` for D1–D6, compatible import/recovery, runtime versus startup configuration, legacy events, and timeout ownership.
- Update CHANGELOG with additions/fixes and the verified no-migration requirement. Assert no breaking change only once parity and runtime/API gates support it.
- Reconcile `SESSION_FIX_CHANGES.md` and `REFRESH_TRANSPORT_MIGRATION.md` with the final documentation. Both are currently untracked; inspect and preserve unique content before any removal.
- Run the complete release gate with the restored 0.5.4 testapp assertions unchanged and retain the compatibility artifacts/reports.

## Suggested commit boundaries and dependencies

Use the numbered sub-batches above as reviewable commits, splitting storage classification from transport recovery and separating 7A from 7B. Avoid a single persistence/events/transports rewrite.

- Critical path: 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7B → final 8 → 9.
- 7A can follow 1 independently of transport work.
- Oracle/API-diff/CI infrastructure from 8 can follow 0; backend endpoints and device scaffolding can proceed while production fixes land.
- Final stress, device upgrades, and FDI acceptance must run against the completed lock/event implementation, not only the pre-refactor version.

The highest-risk reviews are lenient data surviving a second restart, sequential raw setters, recovery-to-login, callback reentrancy, and disk write ordering. Give these dedicated commits and deterministic tests. Completion means demonstrated compatibility plus F1–F5, not merely that all seventeen symptom tests pass.
