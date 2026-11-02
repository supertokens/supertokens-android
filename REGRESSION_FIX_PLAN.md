# 0.6.0 regression fix plan

Follow-up to [COMPATIBILITY_PLAN.md](COMPATIBILITY_PLAN.md). The 2026-11-01 review of `a27f6be` found regressions that the existing suites do not detect. This plan fixes them, adds the missing differential coverage, and defines what must be green before 0.6.0 can be called compatible with 0.5.4.

IDs continue the existing inventory: regressions start at R18, decisions at D7. F1–F5 and D1–D6 keep their meaning.

## Review baseline

- `./gradlew :app:testDebugUnitTest` (JDK 11): 453 tests, 0 failures. None of the regressions below is caught.
- Confirmed regressions were reproduced on the candidate with throwaway JVM probes. R18, R19, R21 and R22 were also run against the published 0.5.4 AAR through `Legacy054Harness`. R20 and R23 were derived from 0.5.4 source.
- Hosted CI has not run on the branch's compatibility commit (`a27f6be`). Only the changelog and PR-title workflows have run on the PR. The last full testapp run recorded in `docs/compatibility-wiki/status-2026-10-30.md` predates the latest production fix.

## Guiding rule

**Untagged legacy calls target 0.5.4 behavior, subject to the approved differences below. Tagged requests and the new APIs keep their strict contract.**

- Untagged (legacy) means no `SessionRequestTag`. It covers the no-arg interceptor, `newRequest` without a tag, and the old static getters, `signOut` and `attemptRefreshingSession`. In code this is `compatibleResponse == true`, i.e. `!tagged`.
- Tagged and new-API callers keep strict validation and errors: tagged requests, `resolveAccessToken`, `installSession`, `clearSession`, `observeSessions`, and the owned-refresh interceptor when used with a tag.
- Every fix must keep F1–F5 green under the approved exceptions below. Owner, configuration and revision fencing remain in force; redirect scoping changes only as approved in D12.

## Regression inventory (new)

The inventory and milestone implementation steps describe the review baseline,
not necessarily the current source. Later fixes may already have landed; the
qualification gates and remaining coverage gaps still need verification.

| # | Regression | Evidence | Severity |
|---|---|---|---|
| R18 | Header config + backend that forces cookie transfer: login response (`front-token` + `Set-Cookie`, no `st-access-token`/`st-refresh-token`) is rejected; user never logged in. Same for `front-token` + exposed `st-access-token` without refresh. | Probe + 0.5.4 oracle (`doesSessionExist`: 0.5.4 true, candidate false) | High |
| R19 | Refresh 401 **without** `front-token: remove` clears the session. Node returns exactly this when the refresh token is missing or the `rid` anti-CSRF check fails (`clearTokens: false`, `sessionRequestFunctions.js`). | Probe + oracle (0.5.4: session kept, `IOException`) | Medium |
| R20 | Persisted `RECOVERY_REQUIRED` (any failed commit, unreadable schema-1 data) makes `signOut` and `attemptRefreshingSession` throw `IOException`; 401s in recovery fire no `UNAUTHORISED`. The state persists until the next login. | Probe (0.5.4 has no recovery state; behaves as "no session") | Medium |
| R21 | After a refresh failure, later 401s do not attempt refresh: 5 s after transport errors, up to 24 h after `Retry-After`. Requests fail with `IOException` without touching the network. | Probe + oracle (0.5.4 refreshes again and succeeds) | Medium |
| R22 | Ordinary response with `front-token: remove` plus a non-empty `st-access-token` (or refresh/anti-CSRF) is rejected as "conflicting"; session kept. | Probe + oracle (0.5.4 clears) | Low–medium |
| R23 | Public `Utils.getTokenForHeaderAuth` returns `null` for an exposed cookie-mode access token; pre-init `Utils.getAuthorizationHeaderIfExists` returns `null`. | Probe | Low |
| R24 | A failed `commit()` on an untagged login/refresh response throws `IOException` from the request (0.5.4 used `apply()` and stayed logged in in memory). | Code + `LegacyRecoveryRequestTest.failedLoginCommitCannotExportAndNextLoginRecovers` | Low (rare), decision needed |
| R25 | Ordinary responses are ignored while a refresh is in flight (`SessionState.applyOrdinaryResponse` ~L1047). A claim/payload update (e.g. email verified) can be lost. | Code | Low |
| R26 | Legacy refresh through URLConnection (used by getters, `attemptRefreshingSession`, `signOut`, `newRequest`) has a hard 10 s deadline and does not follow redirects; 0.5.4 used platform defaults. | Code | Low–medium |
| R27 | `signOut` drops `Authorization` and `rid` returned by `CustomHeaderProvider` for `SIGN_OUT`. | Code | Low |
| R28 | Same-host `http → https` redirect makes the response unmanaged (no token saving, no refresh). Follows D1, but is undocumented. | Code | Low |
| R29 | Test hooks in production paths: `SuperTokens.init` calls `SessionState.resetForTests()` when `config != null`; `SuperTokensInterceptor.beforeDispatchForTests`. | Code | Low |
| R30 | Repository hygiene at review: committed files contained machine-specific paths and extensive run evidence; an untracked `__pycache__` was present. | `git grep` | Low |

### Accepted differences (keep, but document)

These are deliberate and were approved earlier. They must appear in `CHANGELOG.md` and `SESSION_OWNERSHIP.md`, not be "fixed":

- A malformed 2xx refresh (HTML, missing headers) is a failure. 0.5.4 returned `true` (`LegacyRefreshFailureOracleTest`). Decided in M2 to protect against captive portals.
- A request with a foreign `Authorization: Bearer` is unmanaged (M4 / `SessionTransportPolicyTest`).
- Cross-origin redirected responses cannot adopt, clear or retry (D1/F3).
- Shared refresh: followers joining an in-flight refresh share its result instead of refreshing sequentially (F2).
- Ordinary non-removal 401 credential headers are not adopted, unlike 0.5.4, to prevent retargeting a captured request. Normal unauthorized and refresh handling still applies. See [the 401 boundary](app/src/test/compatibility/LEGACY_401_RESPONSE_CONFLICT.md).
- A complete header tuple replacing an opaque cookie session does not inherit its anti-CSRF token; 0.5.4 retains it. See [the provenance boundary](app/src/test/compatibility/LEGACY_CSRF_PROVENANCE_CONFLICT.md).
- A legacy-led refresh flight may make one additional SDK dispatch after a disconnect without a response; followers share that retry. This approved F2 exception can trigger refresh-token reuse detection. Strict resolution and SDK-owned OkHttp refresh do not opt in. See [the disconnect risk](app/src/test/compatibility/LEGACY_DISCONNECTED_REFRESH.md).

## Approved decisions

D7–D12 are approved; implementation and qualification remain in progress. Each fix that changes an existing assertion must cite the applicable decision in its commit message.

| ID | Topic | Options | Recommendation |
|---|---|---|---|
| D7 | Session kind for untagged responses | (a) configured transfer method decides (today); (b) **response shape decides**: a complete header tuple is a header session, anything else with a front token is a cookie-shaped session | **(b)**, untagged only. This matches 0.5.4, which never consulted `tokenTransferMethod` when storing tokens. |
| D8 | Refresh 401 without removal | (a) clear (today); (b) keep session, fail refresh, fire `UNAUTHORISED` (exact 0.5.4); (c) keep session, fail refresh, no event | **(b)** for untagged flights (D6 event parity). **(c)** for strict flights: a 401 without removal is not proof the session ended, and Node avoids clearing for the same race reason. |
| D9 | Post-failure refresh cooldown | (a) keep for everyone (today); (b) **strict callers only**; legacy flights have no post-completion cooldown; (c) legacy: honour `Retry-After` only for 429, capped at 30 s | **(b)**. This narrows F2's "cooldown" to strict callers. Followers that joined an in-flight refresh still share its result, so F2's single-flight guarantee is unchanged. |
| D10 | Commit failure on untagged responses (R24) | (a) **keep throwing** and document; (b) return the response silently, stay in recovery; (c) publish in memory without durability | **(a)**. (b) gives a silent login failure; (c) breaks "never export uncommitted credentials". Disk-full is rare; document it as a behaviour change. |
| D11 | Legacy refresh timeouts and redirects (R26) | (a) keep 10 s deadline (today); (b) **legacy entry points use platform/caller timeouts**; new APIs keep the 10 s deadline. Follow same-origin redirects manually (≤ 20) for both. | **(b)**. Follower wait stays 30 s (already agreed in COMPATIBILITY_PLAN M4). |
| D12 | `http → https` redirect on the same host (R28) | (a) unmanaged (today); (b) treat a same-host scheme upgrade with default ports as in scope | **(b)**. An upgrade to TLS is not the threat F3 defends against; downgrades and host/port changes stay fenced. |

## Milestones

Critical path: M0 → M1 → M2 → M3 → M6 → M7. M4 and M5 can run after M0 in parallel with M1–M3.

### M0 — Differential harness and red baseline

Build the comparison tests first, so each fix lands with a test that fails before it.

1. **Oracle response-shape matrix** `legacy054/LegacyResponseShapeMatrixTest`:
   - Inputs: config {header, cookie} × prior state {absent, header session, cookie session, exposed-cookie session} × response shape {complete tuple, access+front, front only, front+anti-CSRF, removal, removal+access, removal+refresh, duplicate `front-token`, duplicate `st-access-token`} × status {200, 401} × transport {OkHttp, URLConnection}.
   - Observe on both SDKs: `doesSessionExist`, `getUserId`, `getAccessToken`, `Utils.getTokenForHeaderAuth`, stored legacy keys, event sequence, and the next request's `Authorization`/`anti-csrf`.
   - Assert candidate == 0.5.4 for untagged rows except the approved differences above, which assert oracle and candidate outcomes separately. Rows for tagged requests assert the strict contract instead.
2. **Oracle refresh-outcome matrix** `legacy054/LegacyRefreshOutcomeMatrixTest`:
   - Refresh response {200 complete, 200 front-only, 401 + removal, 401 bare, 403, 429 ± `Retry-After`, 500, 503 + `Retry-After`, reset, read timeout} × caller {interceptor 401, `doesSessionExist`, `getAccessToken`, `attemptRefreshingSession`, `getAccessTokenPayloadSecurely`, `signOut`}.
   - Then a second 401 issued immediately afterwards.
   - Observe return value or exception type, session retained or cleared, events, and wire refresh count.
3. **Legacy entry-point × state matrix** `LegacyEntryPointStateMatrixTest` (candidate only):
   - Every public 0.5.4 entry point × every snapshot state {ABSENT, AVAILABLE, AVAILABLE-expired, RECOVERY_REQUIRED, UNAVAILABLE pending, UNAVAILABLE terminal}.
   - Assert only exception types 0.5.4 could raise for the equivalent no-session/session case. Map RECOVERY_REQUIRED and terminal UNAVAILABLE to the oracle's "no session" row.
4. **Driver additions** (`app/src/legacyDriver/.../Legacy054Driver.java`): OkHttp cookie jar for all requests, URLConnection with `CookieManager`, an event recorder, and a `Utils.getTokenForHeaderAuth` passthrough.
5. **Make `FakeBackend` realistic.** Revoked and refresh-reuse (theft) refreshes must return `front-token: remove` like Node. Add a separate `bareUnauthorisedRefresh` mode for the missing-refresh-token case. Today it returns a bare 401, which is why `LostRefreshResponseTest` expects ABSENT.
6. Record the red baseline: test names failing per R18–R23. Do not merge red tests into the default task. Land each with its fix, or behind a named exclusion removed by its milestone.

**Exit:** all matrices compile and run. Every R18–R23 row fails on `a27f6be` and is listed in the baseline.

### M1 — Response acceptance (R18, R22; D7)

Files: `SessionState.java` (`applyOrdinaryResponse`, `replaceForResponseLocked`), `SessionResponse.java`.

1. **Response shape decides the session kind** (D7) when `compatibleResponse`:
   - Complete tuple (access + refresh + front) → header session (unchanged).
   - Front token with no refresh token, with optional access and anti-CSRF → cookie-shaped session, regardless of `tokenTransferMethod`.
   - Access + front without refresh, on top of an existing complete header session with the same identity → promotion that keeps the stored refresh token (unchanged).
   - Replace `cookieAllowed`/`headerMode` (~L1056-1062) and the recovery gate (~L1024-1031) with this shape-based check for untagged responses. Strict path unchanged.
2. **Removal wins for untagged responses** (R22). Add `ordinaryCandidate(boolean compatible)`. When `front-token: remove` is present and `compatible`, ignore credential values and return `Candidate.removal()`. This mirrors `forSignOut`. Tagged responses keep "Conflicting session removal headers".
3. **Duplicate headers.** Characterize with the M0 matrix: OkHttp `header()` and `HttpURLConnection.getHeaderField()` return the last value. If 0.5.4 adopted the last value, do the same for untagged responses. Blank `front-token` stays invalid unless the oracle shows otherwise.
4. Confirm refresh works for a cookie-shaped session under header config. `refreshCandidate` and `completeRefresh` already accept front-only when the previous session is not a header session; cover it with a test.

Tests:
- M0 shape matrix rows green.
- New `TransferMethodMismatchTest` (JVM, both transports): header config with a cookie backend through login → protected request → expiry → refresh → `signOut`. Also cookie config with a header backend.
- Rewrite `LegacyRecoveryRequestTest.explicitAndTaggedRecoveryRemainErrorsAndPartialLoginCannotAdopt`, last block: an untagged `promotion(...)` from recovery under header config is now adopted as a cookie-shaped session (cite D7). The tagged and `installSession` parts stay.
- Audit other tests that send untagged access+front or front-only responses from ABSENT/RECOVERY and expect `INVALID_SESSION`: grep `promotion(` and `front-token` in `SessionTransportPolicyTest`, `SessionResponseCompatibilityTest`, `SessionStateBoundaryTest`, `CaptureIntegrityTest`.

**Exit:** shape matrix green under the approved exceptions. F1 suites (`SessionOwnershipTest`, `SessionStateBoundaryTest`, `SessionCookieDeletionTest`) and F3 suites green unchanged.

### M2 — Refresh failure semantics (R19, R21; D8, D9)

Files: `SessionState.java` (`completeRefresh`, `failRefreshLocked`, `acquireRefresh`), `RefreshCoordinator.java`, `LegacyEventBatch.java`.

1. **Clear only on explicit removal** (D8). In `completeRefresh`, on `sessionExpiredStatusCode`:
   - `response.isRemoval()` → `clearForRefreshLocked` (unchanged).
   - Otherwise → `failRefreshLocked` with `IOException(response.message)` and cooldown 0. For compatible flights, return a `LegacyEventBatch(UNAUTHORISED)` so the leader delivers it (0.5.4 fired it via `fireSessionUpdateEventsIfNecessary(..., "remove")`). No event for strict-only flights.
    - Make sure the interceptor and URLConnection then throw the error (0.5.4 `API_ERROR`). Getters return false or `null`.
2. **No post-completion cooldown for legacy flights** (D9). In `failRefreshLocked`, use cooldown 0 when `flight.compatibleResponses` is true, so `refreshFlight` is cleared. Strict-only flights keep the 5 s and `Retry-After` behaviour. Followers that joined during the flight still receive its failure.
   - Edge case: a strict caller joining a legacy flight gets that flight's result. After completion it starts a new flight. Document this.
3. Check the failure-mode bounds still hold: one SDK preparation per wave, the 8-flight cap, and no separate refresh per follower (`SessionRefreshFailureModeTest` counters), subject to the shared legacy disconnect retry exception above.

Tests:
- M0 refresh-outcome matrix green.
- Rewrite `SessionRefreshFailureModeTest` RETRY_AFTER block (untagged): the second request re-attempts refresh (cite D9). Add a strict variant via `resolveAccessToken` that keeps the suppression.
- `SessionBackpressureContractTest.retryAfterBackpressureIsBoundToOwnerForSecondsAndHttpDate` stays: it uses `resolveAccessToken`.
- Update tests that depend on `FakeBackend`'s bare 401 meaning "cleared" (`LostRefreshResponseTest`, stress tests) after M0 step 5. Add an explicit bare-401 row asserting "kept + failure" for untagged and strict.
- `SessionRefreshPolicyTest` removal cases already send `front-token: remove`; they stay.

**Exit:** refresh matrix green; F2 suites (`SessionRefreshPolicyTest`, `SessionResourceBoundsTest`, `SessionBackpressureContractTest`, `SessionLifecycleTest`) green.

### M3 — Recovery at legacy entry points (R20, R24; D10)

Files: `SuperTokens.java`, `SessionState.java` (`resolutionTerminalLocked`, `applyOrdinaryResponse` recovery branch, `absentUnauthorised`), `SessionTransport.java`.

1. **`signOut`** in RECOVERY_REQUIRED or terminal UNAVAILABLE (not a pending replacement):
   - Attempt `SessionState.clear(owner)` best effort, so a successful ABSENT write ends the sticky recovery.
   - Dispatch `SIGN_OUT` and return. Never throw for these states.
2. **`attemptRefreshingSession`** in those states: dispatch `UNAUTHORISED`, return `false`. Extend `absentUnauthorised` to cover recovery.
3. **401 in recovery, untagged:** the recovery branch of `applyOrdinaryResponse` returns the RECOVERY_REQUIRED result with an `UNAUTHORISED` batch, matching the ABSENT branch (~L1033-1037).
4. `doesSessionExist`, `getAccessToken` and `getUserId` already behave like "no session". Pin them in the M0 state matrix.
5. **D10:** keep throwing on a new commit failure for untagged responses. Add a CHANGELOG line and a `SESSION_OWNERSHIP.md` section.

Tests: M0 state matrix green, `LegacyRecoveryRequestTest` additions (sign-out clears recovery and survives restart; refresh returns false; events), and `LegacyEventContractTest` recovery rows.

**Exit:** no legacy entry point throws a recovery error. The strict APIs still report RECOVERY_REQUIRED (`explicitAndTaggedRecoveryRemainErrors...` stays green).

### M4 — Smaller parity fixes (R23, R25, R26, R27, R28; D11, D12)

1. **R23:**
   - `Utils.getTokenForHeaderAuth` returns the stored internal token when the snapshot is AVAILABLE. Use `SessionState.projectedValueLocked` semantics, not `snapshot.getTokens()`.
   - Pre-init `getAuthorizationHeaderIfExists` reads stored values like 0.5.4.
   - Add rows to `PreInitGetterParityTest` and the shape matrix.
2. **R25:** allow an untagged promotion during an in-flight refresh when the response does **not** rotate the refresh token and the identity is unchanged.
   - The flight stays valid because the refresh revision is unchanged. The later refresh completion is last-write-wins, as in 0.5.4.
   - Responses that rotate the refresh token stay ignored, because they would invalidate the flight's lineage and turn followers' results into `STALE_OWNER`.
   - Test: refresh blocked on a latch, payload update arrives, refresh completes. Check the event order against the oracle.
3. **R26 (D11):** legacy refresh path:
   - Thread a `legacy` flag through `SuperTokensHttpURLConnection.refreshTransport()`. Legacy callers get no `RefreshDeadline` and no explicit timeouts (platform defaults); `resolveAccessToken` keeps the 10 s deadline.
   - Follow same-origin 3xx redirects manually (reuse the `signOut` redirect loop, ≤ 20, `isResponseInScope` on every hop). Cross-origin stays rejected.
   - Tests: a slow refresh beyond 10 s succeeds via `doesSessionExist`; a same-origin redirect succeeds; a cross-origin redirect fails.
4. **R27:**
   - Characterize the 0.5.4 sign-out wire headers with the oracle (custom `rid`, custom `Authorization`, duplicates).
   - Match them unless that sends the SDK's Bearer token to a place F1 forbids.
   - Extend `SignOutBodyParityTest` or add `SignOutHeaderParityTest`.
5. **R28 (D12):** in `SessionTransport.isResponseInScope`, accept a same-host `http → https` upgrade where both ports are scheme defaults. Re-baseline the matching rows in `SessionRedirectBoundaryTest` / `RedirectOriginTest` (cite D12); keep the downgrade, host-change and port-change rows.

**Exit:** each item has a test that fails before and passes after; F3 suites green with only the D12 rows changed.

### M5 — Production hygiene (R29, R30)

1. **Remove the `if (config != null) SessionState.resetForTests()` branch from `SuperTokens.init`.**
   - Change `testHelpers/testapp/.../TestUtils.setInitToFalse` to call `SuperTokens.resetForTests()`. `TestUtils` is a helper, not a restored assertion, so D5 allows this.
   - Verify the testapp re-init tests still behave like master.
2. **Replace `SuperTokensInterceptor.beforeDispatchForTests`** with a package-private static seam in `SessionTransport` that tests set and reset. No mutable instance field on a public class.
3. **Documentation cleanup:**
   - Remove machine-specific temporary directory references from tracked files.
   - Move run evidence (`docs/compatibility-wiki/*`, `*_RESULTS_*.md`, `INTEGRATION_READINESS.md`, handoff files) out of the repository or into a single short summary.
   - Keep `COMPATIBILITY_PLAN.md`, this plan, `SESSION_OWNERSHIP.md`, `CHANGELOG.md` and the runbooks needed to execute tests (`testHelpers/testapp/RUNNING.md`, `app/src/test/compatibility/CI_RUNBOOK.md`).
   - Fold `SESSION_FIX_CHANGES.md` and `REFRESH_TRANSPORT_MIGRATION.md` into `SESSION_OWNERSHIP.md` (COMPATIBILITY_PLAN M7.4).
4. Add `__pycache__/` to `.gitignore`.

**Exit:** `git grep -n 'var[/]folders'` is empty, the unit suite is green, and testapp re-init tests pass.

### M6 — Integration coverage and qualification

New testapp classes only; restored 0.5.4 test classes stay unmodified (D5).

| Test class (testapp) | Scenario | Backend support needed |
|---|---|---|
| `TransferMethodMismatchLiveTest` | Header-config SDK with the server forcing cookie transfer: login, protected request, expiry refresh, `signOut`, for OkHttp, Retrofit and URLConnection. Also the reverse. | Runtime toggle `POST /test/transfer-method` (the `TRANSFER_METHOD` env var is process-wide) |
| `RefreshWithoutClearLiveTest` | Cookie mode: drop `sRefreshToken` from the cookie jar, let access expire, make a request. Assert `IOException`, front token retained, one `UNAUTHORISED`. This is Node's real `clearTokens: false` path. | None |
| `SignOutLiveTest` | `SuperTokens.signOut` in the ordinary lane: header and cookie, both transports, events, sign-out without a session, sign-out after access expiry (reactive refresh). | None |
| `LegacyEventsLiveTest` | Recording `EventHandler` across login, refresh, payload update (`/update-jwt`), revoke (`/revokeAll`), sign-out; compare with the sequence from the M0 oracle matrix. | None |
| `ParallelSessionLiveTest` | 20 parallel 401s → exactly one refresh; parallel login and sign-out; no request carries another owner's token. | None |
| `AuthHeaderMismatchLiveTest` | Fixed copy of `okhttpHeaders_testThatAuthHeaderIsNotIgnoredIfItDoesntMatchTheStoredAccessToken`. The original sends `request` instead of `request2`, so it tests nothing (bug inherited from master); leave it untouched. | None |

Qualification gates (all on the **final** commit SHA):

1. `.github/workflows/unit.yml` on the PR: `testDebugUnitTest`, `legacy054Test`, lint, japicmp, old-consumer, OkHttp 3.12 lane. Link the run in the PR.
2. Nightly OkHttp matrix (3.12.13, 4.1.0, 4.12.0, 5.x) green once on the final SHA.
3. CircleCI FDI matrix via a `test-cicd/*` branch: ordinary and Retrofit profiles including the M6 classes, Core protocol tests, and the six mutation methods. Any skip must be an explicit, named matrix exclusion.
4. Device job (API 21 and 34): restart pairs, upgrade/rollback, StrictMode, R8 smoke.
5. Fix or explicitly accept the 4 failing published cookie-upgrade rows ("Empty cookie header string" on Java 11). If they are a harness artifact, prove it on a device instead of waiving them.

**Exit:** all five gates green with run links recorded in the PR description. Copy only links into the repository, never local paths.

### M7 — Documentation and release

1. `CHANGELOG.md` 0.6.0:
   - Additions and fixes.
   - Existing integrations are intended to require no code changes, subject to qualification and the documented behavior differences.
   - A short **Behaviour changes** list: the accepted differences above, D10, D11, D12, and the sent-origin redirect boundary with its D12 upgrade exception.
2. `SESSION_OWNERSHIP.md`: D7–D12, recovery behaviour at legacy entry points, and that refresh only clears on explicit removal.
3. Update `COMPATIBILITY_PLAN.md` decisions table with D7–D12 and the regression inventory with R18–R30, and link here.

## Global guardrails

- One commit per regression, containing the fix and its test. Any changed existing assertion cites D7–D12 (or an older D) in the commit message.
- Strict paths: no change to the tagged/new-API test expectations except where a decision explicitly says so (D8 strict branch, D12).
- F1–F5 suites stay green at every commit. Run `testDebugUnitTest` and `legacy054Test` before each push.
- No new test may encode candidate behaviour for an untagged path without a matching 0.5.4 oracle row or an approved decision. This is the gap that let R18–R23 through.

## Definition of done

- R18–R23 fixed and covered by oracle comparison rows, subject to the approved exceptions. R24–R30 resolved per D10–D12 and M4/M5.
- M0 matrices green under the documented accepted differences; no other untagged row differs from 0.5.4.
- All M6 qualification gates green on the release commit, with links in the PR.
- CHANGELOG and `SESSION_OWNERSHIP.md` list every intentional behaviour difference.
