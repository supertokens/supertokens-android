# Android integration backend

Use a **disposable local Core test installation**. The existing `/beforeeach` and
`/after` lifecycle stops Core and resets/removes its runtime configuration.
Do not run testapp and coreTest concurrently against the same installation.

## Dependencies and version selection

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm test
```

The lockfile currently resolves Node SDK 17.1.0 (CDI 5.0). A locally installed
Core 8.0.0 supports CDI 4.0, not CDI 5.0. For that pair only, use an unpersisted
matrix override, as CI does when selecting a Node SDK version:

```sh
npm install --no-save --package-lock=false --ignore-scripts --no-audit --no-fund supertokens-node@16.7.0
```

`npm ci` restores the locked dependency tree. Do not edit SDK version constants
or Core API versions to bypass compatibility checks.

## Start

From repository root, use an owned instance with separate Android/Core Java:

```sh
mkdir -p owned-integration-evidence
CORE_PROTOCOL_TESTS=1 JAVA_HOME=/path/to/jdk11 ST_TEST_JAVA=/path/to/core-jdk/bin/java \
node testHelpers/server/runOrdinary.js ../supertokens-root \
  "$PWD/owned-integration-evidence" 18081 19001 \
  node "$PWD/testHelpers/server/runOwnedTests.js" matrix "$PWD"
```

This provisions, qualifies and tests the selected Node/Core pair without using
shared 8080/9000. Use `qualify` instead of `matrix` for qualification only, or
`mutation` for the strict six-method optional-Retrofit lane. See
[entrypoints and receipts](../testapp/RUNNING.md). Direct `node .` with only
`INSTALL_PATH`, old `startServer` and direct testapp Gradle are unsupported.

`/healthz` establishes only that Express is listening. `/test/capabilities`
reports Core reachability, the SDK/Core CDI intersection, and available fixtures.
Unsupported fixture creation returns **501**, never synthetic credentials.
The long-lived, anti-CSRF-disabled setup above is for coreTest's existing protocol
contract. Restored testapp manages its own one-second/anti-CSRF settings unchanged.

## Fixture contract

- `POST /test/email-change-owner`: accepts `{email, recipe: "google"}`. Creates
  a real Core third-party recipe user through the SDK's manual creation API,
  maps it to a unique external ID, verifies the stored mapping/provider, then
  creates a session. Returns `google`, `fixtureId`, `userId`, `handle`, `refreshToken`, plus
  normal SDK session headers/cookies. This is **not an OAuth exchange** or an
  email-change operation; it supplies the mapped recipe owner expected by coreTest.
- `GET /test/protected`: SDK `verifySession()`; returns verified user and handle.
  Access promotion headers come from Core/SDK, not the route.
- `POST /test/revoke-session`: accepts `{handle}`, revokes through Core, then deletes
  the fixture's user and mapping if this process owns that handle.
- `POST /test/cleanup-owner`: accepts `{fixtureId}` from creation, deletes the
  owned user (including its sessions) and mapping. Idempotent; unknown IDs are not
  deleted. Always call this in client `finally`, even when assertions fail.
- `GET /test/capabilities`: reports fixture configuration and pair compatibility.

`CORE_PROTOCOL_TESTS=1` enables mapped-user creation/revocation and the ThirdParty
recipe. Older SDKs missing the necessary mapping/manual-creation APIs are excluded
explicitly. Backend API errors still fail; a positive capability flag is not proof
that every Core feature succeeds. `npm run test:core` verifies the actual path.

The `matrix` command above runs `npm run test:core` and the existing Android Core
test classes with generated owned URLs after exact-instance qualification.
Android Gradle uses Java 11 independently of the Core JDK.

`CoreProxyProtocolTest` additionally requires `/__qualification/*` controls and
`/auth/plugin/rownd/user`. The main backend still advertises `qualificationProxy:
false`. An opt-in, separate loopback listener is described in
[`QUALIFICATION_PROXY.md`](QUALIFICATION_PROXY.md); it forwards the profile alias
to the real `/test/protected` verifier and holds completed upstream responses.
Its proxy capability is conditional on explicit test configuration and the
backend's compatible mapped-owner fixture. Node loopback tests qualify proxy
mechanics, not Core behavior. Do not use an unfiltered `Core*` run as a passing
claim for that suite.

## Account linking: current M2 exclusion

`POST /link-accounts` is a qualification gate, not a substitute login or mapping
operation. Caller-supplied account IDs are never acted on.
`/test/capabilities.accountLinking` separates SDK methods, backend behaviour and
fixture availability:

- `unqualified`: no matching evidence, changed pair, disabled/missing APIs, or
  unavailable Core. `refreshUserIdChange: null`; HTTP **503 QUALIFICATION_REQUIRED**.
  This is a failing gate, never permission to skip R7.
- `backend_proven_unsupported`: exact-pair evidence observed real linking revoke
  the session and refresh fail. `refreshUserIdChange: false`; HTTP **501
  UNSUPPORTED_TEST_CAPABILITY**. An explicit exclusion, not an R7 pass.
- `pair_proven_unsupported`: installed SDK inspection proves missing account-linking
  APIs **and** a live request proves SDK/Core CDI compatibility. This qualifies an
  older matrix pair, not a claim that Core itself lacks linking. False, HTTP **501
  UNSUPPORTED_TEST_CAPABILITY**. Missing fixture/cleanup APIs on a linking-capable
  SDK still fail; they are not accepted as a backend exclusion.
- `fixture_unimplemented`: exact-pair evidence observed changed UID and unchanged
  handle/tenant. `refreshUserIdChange: true`, `fixtureAvailable: false`; HTTP **501
  FIXTURE_UNIMPLEMENTED**. Test gate fails loudly until the actual fixture exists.

There is no version allowlist. The qualifier writes a local consumable JSON result
only after actual SDK/Core behaviour and cleanup checks, binding the observation
to exact Node SDK/Core versions, installed SDK implementation digest, Express startup
identity, the complete effective Core classpath artifact set and qualifier source hash.
By default it is `.account-linking-qualification.json` (gitignored); override with
`ACCOUNT_LINKING_QUALIFICATION_FILE` for a CI artifact. The server reads this file
on each capability request; use the same file for server and test runner.

Identity comes from `INSTALL_PATH/version.yaml` and every JAR matched by
`./core/*:./plugin-interface/*`, plus optional dynamically loaded `ee/*.jar`
(names and bytes, including dependencies/extra JARs), plus
runtime verification: the installation's recorded owned PID must own the selected
Core listener, run `io.supertokens.Main` with the test classpath, and have that
installation as cwd. The classpath is validated as one exact argv value with the
expected launch arguments, not a substring; prepended/appended competing entries
and extra classpath flags are rejected. JAR and directory mtimes must precede
process startup; new launches also capture the complete artifact digest before
starting Java and compare it later. PID/start/cwd/argv
metadata is hashed into the evidence; a restart invalidates it. Linux `/proc` and
macOS `ps`/`lsof` are supported. Stale markers, wrong cwd, unrelated listeners and
artifacts replaced after launch fail identity verification. Express records its
installed SDK JS/JSON/native files (excluding nested dependency packages) before
loading the SDK. Evidence verification requires the same running Express PID/start
and unchanged SDK digest, so same-version code changes and stale loaded servers fail.
This is practical local
launch verification, not remote or cryptographic runtime attestation; the qualifier
refuses remote/nonstandard URLs. Changed Node/Core/plugin/qualifier identities cannot
inherit an older exclusion. No evidence is bundled as a generic exclusion.
HTTP verification consumes candidate evidence. A timeout or failed status/state/
identity assertion overwrites it with incomplete `HTTP_VERIFICATION_FAILED`
evidence, so the consumption gate fails even after a previously valid exclusion.

Installed Node SDK 16.7.0 supports real `createPrimaryUser`/`linkAccounts` (CDI 4.0).
Local Core 8.0.0 performs linking but revokes the secondary recipe user's sessions.
Observed: session existed before linking, link succeeded, session disappeared,
refresh returned `UNAUTHORISED` (HTTP 401 through the existing server route).
There is no post-refresh UID/handle/tenant to compare. This pair cannot reproduce
M2's changed primary UID with the **same** handle and tenant.

Reproduce with the owned `qualify` mode above. It runs the live qualifier and
consumption gate inside the private dependency snapshot with generated Core/backend
URLs and a per-instance evidence file. `matrix` consumes that same evidence before
Core tests. Do not reuse a receipt against a new instance.

It exits nonzero for unknown/mismatched/incomplete evidence and qualified-supported
pairs awaiting a fixture. Run the live qualifier to establish evidence for each
new pair/runtime; retain the resulting JSON with matrix results. `npm run test:core`
consumes this gate before its HTTP smoke; it cannot pass without qualification.

`.circleci/setupAndTestWithFreeCore.sh` now starts the test Core through the existing
server lifecycle after readiness, runs the actual qualifier for each selected
SDK/Core pair, then consumes its evidence gate before Android builds/tests. It does
not run the mapped-Google smoke across older SDKs lacking that separate fixture.
Core restarts invalidate runtime-bound evidence; requalify before another `test:core`.

The test creates two fresh **unmapped** Google recipe users, makes the first primary,
creates the second's session, links it to the first through supported SDK APIs,
then attempts refresh. All fixture sessions/users are explicitly deleted and
read back absent in success/failure paths; unrelated users are untouched. Cleanup
errors are surfaced, not reported as successful qualification. A crash or cleanup
failure still requires disposal of the dedicated test database, as below.

On this pair, a successful test run reports **UNAVAILABLE**, not an R7/M2 pass.
If another pair preserves the session and changes UID on refresh, the test records
that evidence and fails `QUALIFIED_SUPPORTED_PAIR_REQUIRES_FIXTURE`. Do not change
expected handle/tenant or mint a replacement session. HTTP smoke via
`CORE_PROTOCOL_URL` runs after qualification so it checks the matching server result.

## Cleanup boundaries

Fixture ownership is registered before user creation. Creation errors trigger
cleanup, including finding the unique Google recipe user after a lost creation
response. Cleanup uses `deleteUser(..., false)` and `deleteUserIdMapping`, then
checks user/mapping absence. Failed cleanup stays registered for retry and fails
the request visibly. `/beforeeach` and `/after` clean tracked fixtures **before**
stopping Core; a cleanup failure prevents those lifecycle operations.

The registry is process-local. A server crash/restart can lose it; fixtures from
older server versions are also untracked. `/after` removes runtime configuration,
**not the database**. Use a disposable Core database and dispose/reset that database
explicitly after crashes or older runs. This server does not automatically delete
untracked users, mappings or database files. Do not point it at shared user data.

`npm run test:core-cleanup` is a child-only check against an already provisioned
owned instance, using its generated environment; it is not a shared-server command.

This exercises real user/mapping/session deletion, plus failures injected after
real Core writes. It is not an email-change, account-linking or OAuth test.

CI and local callers use `runOrdinary.js` cleanup: verify command-worker drain,
require authenticated Core cleanup acknowledgment, then stop the owned backend.
Unproven drain/cleanup retains resources and evidence with a failing exit. No
`ciCleanup.sh` fallback or arbitrary-PID kill is used by the migrated caller.
The historical cleanup helper/tests remain for their existing contract, not as
the current CI lifecycle. Never apply them to a shared backend.
## M6 tenant/backend mutation fixtures

**2026-10-30 qualification:** former staged/Core8-execution-pending status is
superseded for local Node16.7.0/Core8.0.0 with its installed optional EE module
preserved. Dedicated mutation **6/6 passed**, including tenant login/refresh and
scoped cleanup. Ordinary and optional Retrofit each separately recorded **121
passed / 6 skipped / 0 failed**; those skips are not mutation passes.
See [portable execution evidence](../../docs/compatibility-wiki/status-2026-10-30.md#portable-owned-entrypoints--reviewed-local-execution).
This qualifies neither non-EE behavior nor historical pairs/Linux. Each runtime
must qualify its capabilities; no license or feature-check bypass was used.

`GET /test/capabilities` includes `nonPublicTenantSession`, gated by
`CORE_PROTOCOL_TESTS=1`, mapped-owner APIs, installed multitenancy APIs and compatible
Core CDI. This flag alone is fixture/API readiness, not live qualification: tenant
creation errors remain failures rather than being converted to unsupported passes.

`POST /login-tenant` accepts `{ "email": "unique@example.com" }`, creates a random
non-public tenant with `thirdPartyEnabled: true`, creates a real Google recipe user
and mapped session there, and returns `fixtureId`, `tenantId`, `userId`, `handle`.
The caller cannot choose an arbitrary tenant or user to mutate. Session headers are
the real `Session.createNewSession` output. Use `/test/cleanup-owner` with that
fixture ID; it deletes only the owned user/mapping/tenant, verifies deletion and
retains failed cleanup for retry. Existing public fixtures keep their old behavior.

Installed Node 16.7.0 uses `Multitenancy.createOrUpdateTenant/getTenant/deleteTenant`
and `ThirdParty.manuallyCreateOrUpdateUser(tenantId, ...)`; multitenancy is initialized
automatically by that SDK. These signatures were inspected, not inferred from newer
docs. The earlier Core8 live-execution requirement is satisfied only within the
dated local qualification above; other pair/configuration lanes remain open.

`backendMutations.js` selects the installed payload/revocation APIs. Node16.7 lacks
`Session.updateAccessTokenPayload` and root `SuperTokens.revokeAllSessionsForUser`;
the old routes would throw TypeError. The modern branches use handle-based
`Session.mergeIntoAccessTokenPayload` and `Session.revokeAllSessionsForUser` while
retaining older API branches. Handle updates retain replacement semantics for
custom claims and do not fabricate a changed payload in the current request object.

Local checks: `node fixtureOwners.test.js`, `node coreProtocol.test.js`,
`node backendMutations.test.js`, `node ownerSessionState.test.js`. They use fake SDK
operations/disabled fixture routes, not a live tenant/session qualification. Tenant
tests include scoped sibling preservation, unknown IDs, known completed cleanup,
creation-response loss, partial creation, deletion retry, and preexisting-tenant
collision rejection. No test uses a global backend reset.
