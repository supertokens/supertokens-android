# Native session ownership changes

As of **October 26, 2026**, uncommitted review fixes over `136f1033b655a73c82097bcfc859c07cfadad3b2`.
October 25 approval is historical. Fresh review found redirect-scope and sign-out
regressions; fixes await independent review. **Not published or release-approved**.

## October 26 review fixes

Cookie follow-up: independent review accepted redirect/header-mode fixes but reproduced
cookie sign-out skipping refresh. The new cookie2 candidate uses the ordinary transports'
null-access force-refresh policy with the original captured refresh revision. Eight new
cookie regressions cover actual cookie transport, retry bound, refresh expiry, completed
refresh reuse, in-flight joining and replacement/clear/reconfiguration cancellation.
Fresh full host suites: **154 passed each debug/release**; AAR builds/lint completed.
Earlier frozen candidates remain archived and are superseded; independent re-review pending.

- Both transports authorize the final response URL against captured configuration
  before adopting/removing credentials or refreshing. Foreign responses remain
  unmanaged for untagged work and reject tagged work. In-scope redirects still work.
  Legacy OkHttp refresh checks final scope too; URLConnection manual cookie handling
  uses the final allowed URL.
- Remote sign-out restores bounded reactive refresh for server-rejected access tokens,
  retaining original owner/configuration and failed refresh lineage. Replacement at
  initial response, refresh or retried sign-out cannot acquire/clear the new owner.
- Fresh host regressions: 57 passed (48 redirect matrix cases, nine sign-out cases).
  Before fixes, 44 of those 57 failed. Original reviewer probes reproduced 3/3
  failures, then passed 3/3 after fixes. Full default JVM: 146 passed each debug/release;
  debug/release AAR builds and lint passed. No fresh device/live-Core qualification
  is claimed by this upstream host work.

## Why this changed

Concurrent refresh, authentication, sign-out and delayed HTTP responses could write
credentials belonging to different sessions. A caller-side lock could coordinate
Rownd calls, but could not make upstream credential storage, caches and other HTTP
clients share one atomic ownership boundary.

Rownd first introduced a conservative coordinator in its `bbe7ab9` task commit.
This upstream change supplies the missing native authority; Rownd `b7d5069` then
adopts it and removes broad serialization of ordinary network requests. Rownd still
owns its authentication/verification transactions, recovery journal and UI state.

## Final architecture and behavior

- [SessionState](app/src/main/java/com/supertokens/session/SessionState.java) owns
  short synchronized state transitions. Network calls run outside that commit lock.
  Explicit installation, clearing and reconfiguration invalidate the old opaque
  process-local owner; even installing identical credentials creates a new owner.
  Refresh and same-session token promotion retain ownership.
- [SharedPreferencesSessionStorage](app/src/main/java/com/supertokens/session/SharedPreferencesSessionStorage.java)
  synchronously commits the complete credential tuple, identity, configuration,
  revisions and adoption metadata together. A durable adoption ID and optional
  caller correlation identify interrupted cross-store adoption; neither substitutes
  for the caller's business journal. Invalid/partial persisted state fails closed.
- [RefreshCoordinator](app/src/main/java/com/supertokens/session/RefreshCoordinator.java)
  shares one refresh flight per owner/lineage across getters, OkHttp and
  URLConnection. Ordinary requests remain concurrent. Refresh lineage is separate
  from access-token revision: a late promotion/removal cannot overwrite or erase a
  newer tuple. Promotions are suppressed during that lineage's refresh.
- [SuperTokens](app/src/main/java/com/supertokens/session/SuperTokens.java) adds
  `getSessionSnapshot`, conditional `installSession`/`clearSession`,
  `resolveAccessToken`, `captureRequestTag` and `observeSessions`. Resolution returns
  explicit status/error plus the accepted snapshot; legacy getters retain their
  error-collapse behavior. Local durable clear and remote `signOut` are distinct.
  Remote sign-out pins its original owner so its late response cannot clear B.
- [SessionRequestTag](app/src/main/java/com/supertokens/session/SessionRequestTag.java)
  captures ownership at logical request creation. Tagged stale work throws;
  untagged stale responses may return their body/status but cannot mutate the
  current session or retry under its replacement. Consumers must also fence UI
  publication. Authentication responses expose their accepted snapshot as a receipt.
- [FrontToken](app/src/main/java/com/supertokens/session/FrontToken.java) and
  [AntiCSRF](app/src/main/java/com/supertokens/session/AntiCSRF.java) use native state
  rather than independent stale credential caches. Activated subscriptions deliver
  monotonic, coalesced snapshots outside the commit lock; legacy events are observations.
- [Utils](app/src/main/java/com/supertokens/session/Utils.java) enforces configured
  scheme, effective port and path boundary. The follow-up normalizes both URL paths,
  including scheme-less inputs, without reopening cross-origin interception.
  Equivalent default ports match. Changed `Builder.build()` configuration rejects;
  intentional `Builder.reconfigure()` clears durably. Cookie-to-header changes now
  require fresh authentication rather than silently migrating credentials.

## Integration implications

Use the [API and transport guide](SESSION_OWNERSHIP.md) for adoption examples.
Run durable operations off the main thread; handle persistence failure explicitly.
Do not import credentials through several deprecated setters or raw preferences.
Validate complete token identity/expiry coherence; JWT parsing is not signature verification.

`new SuperTokensInterceptor()` preserves the host chain and its retry/redirect
policy; it is **not** a one-wire guarantee. The opt-in constructor taking a base
OkHttp client derives an isolated refresh transport, preserving TLS/proxy/cookie
configuration but removing interceptors/authenticators/retries/redirects/cache.
Its network budget is 10 seconds; followers wait at most 30 seconds. Eight unfinished
refresh transports bound resource use across retired owners. Uncooperative host
callbacks can still hold a caller and slot indefinitely. The AAR does not bundle
OkHttp; tested JVM 4.1.0 and API34 fixture 4.12.0 are different runtime scopes.

Rownd requires these new APIs: published Android **0.5.4 is incompatible** with
the migrated caller. Hub's capability-aware verification wait complements Rownd's
transaction lifetime; it does not implement native session ownership itself.

## Recorded validation

These are completed October 25 qualification runs, not tests rerun for this document.
The full-suite reports and independent follow-up review were reconciled with the
commits and [JVM](app/src/test/java/com/supertokens/session/),
[Core](app/src/coreTest/) and [device](app/src/androidTest/) test sources.

- Default JVM: **89 passed each debug/release**. Live-Core JVM: **97 passed each**,
  including the default tests plus eight Core methods, not 97 additional tests.
- Revised [legacy supported-contract suite](testHelpers/testapp/app/src/test/java/com/example/example/):
  **117/117 each debug/release**, zero skips. Original runs had **6 failures of 117**
  per variant; correcting fabricated CDI expiry reduced that to **3 failures**.
  Explicit exact-origin/configuration-clear assertions and the normalization repair
  produced the new pass. Historical failures remain failures; strict expiry stayed intact.
- Device: **9 ordinary passes plus 4 preserved-storage seed/check pairs**, covering
  all 17 discovered methods. These are separate invocations, not 17 ordinary passes.
- Candidate build/lint, exported ABI and downstream resolved-provider checks passed
  for archived candidates. These do not qualify an as-yet unpublished release AAR.

## Remaining limits and release gates

Strong guarantees cover SDK-managed header credentials in **one process**. Ambient
cookie jars, external preference writers and multiprocess access are outside them.
Failed persistence cannot promise durable logout; local fencing cannot undo server
effects or recover a lost opaque token. Restart fixtures do not prove power-loss or
instruction-exact crash atomicity. API17/live V1, additional device/API levels,
licensed Core8 nonpublic tenancy, external OAuth UI and Google aging remain unqualified.

Downstream full repeats passed, but first SDK/app runs each had one unresolved UI
timeout (WebView readiness and manage-account Email label). Retries did not prove
them fixed. Hub's configured parity suite is missing and remains unexecuted.

Before release: assign an owner/version, publish and verify the artifact, pin it in
Rownd, qualify release-artifact/staging smoke and coordinated preserved-storage
upgrade/rollback. Never downgrade upstream alone beneath callers of the new API;
retain Rownd journals/history and recovery sentinels.

## Commit map

- `ec5ba961501cf081b0c12ef9ed169d8178ba4e10`: native ownership, atomic storage,
  refresh/transport fencing, public APIs and regression coverage.
- `136f1033b655a73c82097bcfc859c07cfadad3b2`: URL normalization and supported-contract
  legacy fixtures, including Core-issued expiry.
