# Live integration readiness — 2026-10-29

Historical execution record. Shared-port/startServer/direct-testapp commands below
are evidence of earlier runs, not current instructions. New runs must use
[owned entrypoints](../../../../testHelpers/testapp/RUNNING.md). No results below
are reclassified by the entrypoint migration.

## Latest activation — M3 lane released, live verification passed

After M3 reported restored live **7/7** and released the backend lane, verified
Express **30705** against its startup record (16:22:20 local), parent **30704**,
exact command and server cwd before stopping only that Express via `/stop`.
Core **77488** was already absent and port 9000 had no listener; M3's completed
server log confirms its lifecycle teardown stopped Core and removed runtime config.
No running Core was stopped during this activation.

Relaunched with the documented `CORE_PROTOCOL_TESTS=1`, Node16/Java21 command;
background shell **`sh_18819c732001bc1bTD0G6qweOh`**. Restored runtime config using
`utils.setupST()` directly, then `/startst` with access-token validity 3600 and
anti-CSRF disabled. This avoided the broad-stop `/beforeeach` lifecycle route.

- Express **44535**, startup **2026-10-29 14:39:13 UTC / 16:39:13 local**.
- Owned Core **44737**, startup **2026-10-29 14:39:31 UTC / 16:39:31 local**;
  launch record binds it to Express44535/startup, installation cwd and exact argv.
- Exact pair: SDK **16.7.0**, Core **8.0.0**, plugin interface **5.0.0**.
- Evidence timestamp: **2026-10-29T14:39:37.486Z**.
- SDK implementation: `a3ab80b3c8b8e26073c4122e147ed2e40e78b163a83f4cbdd4bbd0ffbc073bf0`
- Express identity: `c7038087e1af365688f23eafc97b6473bb6242f3ddd62f875badfd5b5292a24d`
- Core identity: `4d73084f34068ce3aa8d9624a8e12c9bcb0d6db3ed02ddb13ea41aecfca22b5a`
- Effective classpath: `7bd54345abd64d174acf25692e0ddded7bfe83e3b1ee62df6cb16c04553a4c99`
- Qualifier: `a5c445d56f150994376b067a3f606fb5966911bcdc01dd829ca7b1bf2391318f`

Passed with Node16 and absolute local `INSTALL_PATH` where required:

1. `CORE_CONNECTION_URI=http://localhost:9000 CORE_PROTOCOL_URL=http://127.0.0.1:8080 npm run test:account-linking`
   — real qualification, fixture cleanup and HTTP status/state/identity verification.
2. `CORE_PROTOCOL_URL=http://127.0.0.1:8080 npm run test:core`
   — evidence gate followed by mapped-Google session/refresh/promotion/revocation smoke.
3. `CORE_CONNECTION_URI=http://localhost:9000 npm run test:core-cleanup`
   — real user/mapping/session cleanup, including partial-creation failures.
4. Post-cleanup current-identity consumption gate passed; wrong-owner shutdown
   request returned **409**; Express `/healthz` and Core `/hello` returned **200**.

Current evidence is consumable **backend_proven_unsupported / LINK_REVOKED_SESSION**,
with `cleanupVerified:true`: linking revokes the secondary session and refresh
returns `UNAUTHORISED`. **Not an R7 pass or added R7 coverage.** Both processes remain
available. Cleanup-exit failure behavior retains the isolated fake-process coverage
below; actual owned-Core shutdown was not invoked on this healthy runtime.
Actual CircleCI image/historical SDK-Core matrix remain pending. No Gradle, full CI,
emulator/device work or commits during activation. Earlier activation records below
are historical and superseded by this evidence.

## Residual devops follow-up — fixed; pre-activation verification

Follow-up to `ses_e7802c8f0ffe7wSGRW0j3ta9UX`:

- Failed HTTP qualification now overwrites candidate evidence with incomplete
  `HTTP_VERIFICATION_FAILED` evidence. Isolated tests seed valid evidence before
  each timeout, wrong HTTP status, wrong state and wrong identity, then verify the
  consumption gate rejects it. Verified HTTP success remains consumable.
- EXIT cleanup preserves original nonzero status (including 37), but changes 0 to
  1 when endpoint and fallback cleanup fail. Express termination still runs.
  Fake-process tests cover successful cleanup (0), failed cleanup (0→1 and 37→37),
  missing cleanup directory (0→1), and existing failure boundaries (37/38/39/40).
  They check Express termination and unrelated-process survival; intentionally
  surviving Core stubs are removed by test teardown.

Verification: Node 16 `npm test` passed with the evidence and cleanup cases;
`bash -n .circleci/setupAndTestWithFreeCore.sh testHelpers/server/ciCleanup.sh`
passed. This follow-up uses temporary evidence and fake processes only. No live
evidence writes, backend restart/stop, live qualification, Gradle, full CI or commits.

At this pre-activation checkpoint, changed qualifier source made the older evidence
stale and restart/requalification awaited M3. The latest activation above resolves
that dependency. Actual CircleCI image, historical SDK/Core pairs and real new-Core
shutdown remain unexecuted.

## Devops review follow-up — three lifecycle/identity findings fixed and active

Addressing `ses_e7802c8f0ffe7wSGRW0j3ta9UX`, without Gradle/full CI execution:

1. **EXIT cleanup:** new `ciCleanup.sh` preserves the original status before any
   cleanup commands, calls the owned-Core shutdown endpoint while Express is alive,
   falls back to the same ownership-checked record when Express is unavailable,
   then terminates and waits for its own Express job. Core is now launched as a
   direct Java child with captured server PID/start and Core PID/start/cwd/argv.
   Shutdown uses bounded TERM (5s) / KILL (2s), rechecking ownership before signals.
   The endpoint verifies the caller's expected Express PID. No broad process kills
   or database disposal. A pre-existing Core not launched by that server is untouched.
2. **SDK identity:** hashes installed SDK JS/JSON/native implementation files,
   including entrypoint wrappers/package metadata, while excluding nested dependency
   packages. Express records this digest **before requiring the SDK**. Qualification
   checks the same running Express PID/start/cwd/argv and unchanged digest; same
   semantic version/export flags no longer hide modified implementation or a stale
   loaded process. A coordinated Express restart + requalification is required.
3. **Core classpath:** validates exact known argv, not substring inclusion, and
   fingerprints every JAR name/content in both effective wildcard directories.
   Dependency replacement, extra/competing JARs and prepended/appended classpath
   entries invalidate identity. New owned launches capture the complete digest
   before Java starts. The existing local Core is verified via exact argv/cwd,
   listener and all artifact/directory mtimes preceding startup; this is practical
   launch verification, not protection against malicious filesystem timestamp forgery.

Tests before activation: `npm test` passed, including controlled shell failures
after `/startst`, during qualification (HTTP failure exercises fallback), and at
the Gradle boundary. Stub command harness invokes no actual Gradle or Core: each
original exit code (37/38/39) is preserved, both owned subprocesses are gone and an
unrelated live process survives. Focused identity tests cover same-version SDK code
mutation, stale Express startup, dependency-JAR replacement, extra JAR, exact
classpath prepend/append/extra flag rejection and PID/start/cwd ownership changes.
`bash -n .circleci/setupAndTestWithFreeCore.sh testHelpers/server/ciCleanup.sh` passed.

Activation planned: verify and restart only this agent's Express PID 19921 through
`/stop`, preserving existing Core PID 77488. It was launched by an earlier server,
so the new server will not claim shutdown ownership of it. Then verify revised
identity, real qualification/gate and HTTP smoke. Linux/CircleCI-image execution,
actual historical SDK/Core combinations and actual new-Core shutdown in that
image remain pending; stub coverage is not represented as those executions.

Activation completed **2026-10-29 14:22:20 UTC / 16:22:20 local**, after verifying
old listener PID 19921, startup parent 19920 and server cwd. Only `/stop` was used
before relaunching the same documented `CORE_PROTOCOL_TESTS=1` Java21/Node16 command.
Current Express PID **30705**, parent **30704**, background shell
**`sh_1880a5450001Eof1FB4eSEznSq`**. Core **77488** stayed running throughout.

Post-activation verification:

- `npm test` passed again, including a fourth controlled failure with Express
  already exited: status **40** preserved, fallback removes owned Core stub,
  unrelated process survives. No actual Gradle invocation in any harness case.
- `bash -n .circleci/setupAndTestWithFreeCore.sh testHelpers/server/ciCleanup.sh`
  and `git diff --check` passed.
- With absolute local `INSTALL_PATH`, `CORE_CONNECTION_URI=http://localhost:9000`
  and `CORE_PROTOCOL_URL=http://127.0.0.1:8080`, `npm run test:account-linking`
  passed qualification + HTTP identity comparison, and `npm run test:core` passed
  gate-first consumption followed by live session/refresh/promotion/cleanup smoke.
- HTTP `/test/shutdown-core` with a wrong expected Express PID returned **409**.
  With the actual new PID it returned `stopped:false / NO_OWNED_CORE`, preserving
  pre-existing Core77488; `/hello` still returned 200. No local Core shutdown/reset.

New consumable evidence at **2026-10-29T14:22:26.768Z**:

- SDK implementation: `a3ab80b3c8b8e26073c4122e147ed2e40e78b163a83f4cbdd4bbd0ffbc073bf0`
- Express startup: `4b06c542e3d5ce021788b9f2eb64e9c621644f612f30a61d665e91818108d83d`
- Core runtime: `8d4092f5bebed8c8ad67ebf2c95336a57dca76005b76ed5dc0c2b29926e200b7`
- Complete effective classpath: `7bd54345abd64d174acf25692e0ddded7bfe83e3b1ee62df6cb16c04553a4c99`
- Combined qualifier: `96af6c9dc367c3bf1181d65cf9692399f76a2483bf7b5bd6d9b7da0b27d98621`

Still **backend_proven_unsupported / LINK_REVOKED_SESSION**, not an R7 pass.
No credentials printed, commits, Gradle or full CI runs. Actual CircleCI image,
historical live pairs, and real new-Core startup/shutdown under CI remain pending.

## CI consumption and runtime identity follow-up — active

Scope: narrow `.circleci/setupAndTestWithFreeCore.sh` integration plus server
qualifier/gate/tests/docs. No Gradle command or full CI execution.

- CI matrix startup now exports its exact installation/evidence paths, enables
  fixture routes, starts Core via existing `/beforeeach` and `/startst`, runs the
  **actual** qualifier for the installed pair, then runs the consumption gate
  before accepting an R7 exclusion or proceeding to existing Android tasks.
- `npm run test:core` now consumes the gate first; an entrypoint test proves a
  missing identity exits nonzero before HTTP smoke executes.
- Historical SDKs are inspected for actual missing `createPrimaryUser`/`linkAccounts`
  exports. A live `/apiversion` response must also prove SDK/Core CDI compatibility.
  Only that measured combination yields `pair_proven_unsupported`, distinct from
  `backend_proven_unsupported` (real linking revoked the session). Unsupported
  fixture APIs on a linking-capable SDK, unreachable Core, unsupported CDI,
  incomplete/unknown evidence and supported-but-unimplemented flow still fail.
  No SDK version cutoff or blanket historical skip was added.
- Runtime identity now validates the installation PID marker against the actual
  port 9000 owner, Java Main/test classpath, cwd and process start time. Both JAR
  mtimes must precede startup. The runtime metadata fingerprint is bound into
  evidence, alongside SDK API inventory and combined qualifier implementation
  fingerprint; restarts, replaced artifacts and pair changes require qualification.
  Linux uses `/proc` socket/cwd data; macOS uses `ps -ww` and `lsof -nP`.
  This is practical local launch verification, not cryptographic attestation.

Pre-activation verification: `bash -n .circleci/setupAndTestWithFreeCore.sh` passed;
`npm test` passed, including historical missing-API/live-CDI preflight, rejection
of offline/incompatible Core, current unsupported/supported/unknown evidence,
wrong-runtime/changed-artifact checks and failing `test:core` gate consumption.
Actual local runtime inspection matched Core 8.0.0 PID 77488, its installation cwd,
classpath and listener. Historical SDK/Core combinations have unit coverage for
preflight policy; their actual CI matrix executions remain unrun, not claimed green.

Activation plan recorded before execution: reverify ownership of only this agent's
8080 server PID 3699, stop it through `/stop`, launch the same documented command,
preserve Core PID 77488/database, then run actual qualification + evidence gate +
HTTP smoke. No `/after`, `/beforeeach`, `/startst` or Core restart during local activation.

Activation completed **2026-10-29 14:05:51 UTC / 16:05:51 local**. Verified old
PID 3699, its startup parent 3698 and cwd before `/stop`; the original background
shell completed. Restarted using the exact `CORE_PROTOCOL_TESTS=1`, Java21/Node16
PATH command documented below. Current Express PID **19921**, parent **19920**,
background shell **`sh_187fb3c09001oQdbXX7Wgy2rfM`**. Core stayed **PID 77488**,
database preserved. Live runtime identity fingerprint:
`90444571d005ef551bd2542e534180ddb50930b38743177d354f90899045671a`.

Post-activation evidence:

1. Before requalification, HTTP `POST /link-accounts` returned **503
   QUALIFICATION_REQUIRED / QUALIFICATION_PAIR_MISMATCH** for the older artifact.
   This confirms stale evidence does not silently exclude R7.
2. From `testHelpers/server`, with Node16 PATH:

   ```sh
   INSTALL_PATH=/Users/bogdan/src/supertokens/supertokens-root \
   CORE_CONNECTION_URI=http://localhost:9000 CORE_PROTOCOL_URL=http://127.0.0.1:8080 \
   npm run test:account-linking
   ```

   Passed real linking/cleanup qualification and HTTP exact-identity comparison.
   New evidence at **2026-10-29T14:06:01.573Z**, SDK **16.7.0**, Core **8.0.0**,
   plugin **5.0.0**, actual runtime above, combined qualifier fingerprint
   `eeaa29e6f4cd6f662afe0ea39478ddcf1b08989345eab3b45180936fc45b037b`.
   Result **backend_proven_unsupported / LINK_REVOKED_SESSION**, not an R7 pass.
3. `INSTALL_PATH=/Users/bogdan/src/supertokens/supertokens-root CORE_PROTOCOL_URL=http://127.0.0.1:8080 npm run test:core`
   visibly ran the evidence gate first, accepted that measured exclusion, then
   passed live mapped-owner login/refresh/promotion/revocation/cleanup smoke.
4. Repeated `npm test`, shell syntax check and `git diff --check`: passed.

Activated route source `coreProtocol.js` SHA-256:
`3466a2c0c7ed33e994193e43fb542b282d837e12cc8bef0d2a85095f130388fc`.
CI script SHA-256: `93d29b292aeb02ac102bda73f9abddd7c2878fbb8c2b373892f8501c7680d760`.
No Gradle/full-CI execution or commits. Linux runtime verification and historical
pair execution still await CI; local macOS runtime verification and current pair
were exercised. R7 remains excluded only for a measured pair/runtime outcome;
supported/unimplemented and unknown evidence still fail the acceptance entrypoint.

## Previous pair-specific qualification review follow-up

Addressed medium review `ses_e783420dbffeof0xUkWXCzrkNM`: generic false/501 no
longer represents every Node/Core pair. Added exact-pair evidence and a failing
consumption gate. **The running server was not restarted during this follow-up;
PID 3699 still served the prior HTTP gate until the later coordinated restart above.** Core was
untouched. Existing fixture cleanup and normal session routes remain unchanged.

New capability states / `/link-accounts` responses:

- `unqualified`: `refreshUserIdChange:null`, 503 `QUALIFICATION_REQUIRED`.
  Unknown/missing/changed pair evidence cannot silently exclude R7.
- `backend_proven_unsupported`: matching evidence observed link revocation plus
  `UNAUTHORISED` refresh; false, 501 `UNSUPPORTED_TEST_CAPABILITY`.
- `fixture_unimplemented`: matching evidence observed changed primary UID with
  the same handle and tenant; true but `fixtureAvailable:false`, 501
  `FIXTURE_UNIMPLEMENTED`. Test runner exits nonzero until the real fixture exists.

`accountLinkingQualification.js` binds observations to Node SDK version, exact
Core version and JAR SHA-256, plugin-interface version/JAR SHA-256, and qualifier
source SHA-256. Identity is read from the configured local `INSTALL_PATH`; Core
must have been started from that installation, as verified for this local run.
This is not remote binary attestation; qualification refuses remote/nonstandard
URLs, and missing installation identity fails rather than inventing a version.
No generic version allowlist or prepackaged exclusion file was added.

The live runner now writes `.account-linking-qualification.json` after real
behaviour/cleanup checks, atomically, without IDs/tokens. It can instead write
`ACCOUNT_LINKING_QUALIFICATION_FILE` for a CI artifact. Server and gate consume the
same result and recheck current identity; changing any identity field invalidates
it. Starting a new qualification writes incomplete evidence first, so a failed
attempt cannot leave an older exclusion consumable. Supported outcomes are recorded **before** the runner fails loudly, so their
fixture gap cannot be mistaken for backend lack of support.

Verification (Node 16 PATH as in earlier commands):

1. `npm test` — passed. Added cases for current observed unsupported evidence,
   missing/unknown identity/evidence, changed Node/Core/plugin/qualifier identity,
   qualified-supported awaiting fixture, incomplete cleanup evidence, and disabled
   HTTP 503. Existing real fixture cleanup tests remain green.
2. `INSTALL_PATH=/Users/bogdan/src/supertokens/supertokens-root npm run test:account-linking-gate`
   **before qualification** — expected exit **1**, `unqualified: QUALIFICATION_REQUIRED`.
3. `INSTALL_PATH=/Users/bogdan/src/supertokens/supertokens-root CORE_CONNECTION_URI=http://localhost:9000 npm run test:account-linking`
   — actual linking still revokes the secondary session; cleanup verified; wrote
   initial evidence at `2026-10-29T13:53:16.475Z` for SDK **16.7.0**, Core **8.0.0**,
   plugin **5.0.0**, hashes already recorded below.
4. Same `test:account-linking-gate` command **after qualification** — exit **0**,
   explicitly **EXCLUDED with exact-pair evidence; not an R7 pass**.
5. Re-ran the live qualifier after adding pre-run evidence invalidation — same
   proven-unsupported result and verified cleanup; refreshed the runtime artifact.

The new entrypoints are consumable by the matrix/CI owner; no CI workflow, Gradle,
Android or testapp assertion changed here. Evidence is a local gitignored runtime
artifact, not a source-controlled exemption. HTTP activation requires restarting
only the owned 8080 server through the documented procedure, preserving Core9000;
then run the qualifier with `CORE_PROTOCOL_URL=http://127.0.0.1:8080` to compare
HTTP state and exact identity. Prior unconditional-gate observations below are
historical and superseded by this source change.

Residual R7 gap: local Core revokes the session, so the required same-session UID
transition remains untested. An unknown pair must be genuinely qualified. A
supported pair must then receive the actual fixture and Android acceptance tests;
it cannot pass or be excluded merely because this fixture is unimplemented.

## M2 refresh-user-ID prerequisite — account-linking blocker

**Current Node SDK 16.7.0 / local Core 8.0.0 cannot exercise R7 via account linking.**
This is not a missing SDK API or a user-ID mapping substitute: real
`AccountLinking.createPrimaryUser` and `linkAccounts` both returned OK. A fresh
secondary user's persisted session existed before linking and was absent afterward.
`refreshSessionWithoutRequestResponse` then returned `UNAUTHORISED` with
"Session missing in db or has expired". An initial probe through the running
server's `/auth/session/refresh` likewise returned 401. No post-refresh UID was
issued, so same-handle/tenant UID-change acceptance was **not** demonstrated.

Source inspected (installed SDK, not guessed API signatures):

- `supertokens-node/lib/build/recipe/accountlinking/index.d.ts`: supported
  `createPrimaryUser(recipeUserId)` and `linkAccounts(recipeUserId, primaryUserId)`.
- `.../accountlinking/recipeImplementation.js:68–100`: CDI requests to
  `/recipe/accountlinking/user/primary` and `/recipe/accountlinking/user/link`.
- Local Core source `src/main/java/io/supertokens/authRecipe/AuthRecipe.java:308–315`
  explicitly revokes all sessions for the newly linked recipe user because its
  user ID changed. This source supports the observed running-JAR behaviour;
  source/JAR provenance caveats and hashes remain below.

Implemented a safe explicit gate rather than changing the feature requirement:

- `POST /link-accounts` returns 501 `UNSUPPORTED_TEST_CAPABILITY`, capability
  `refreshUserIdChange`, with an exclusion reason. It does not accept/act on
  caller-supplied user IDs or mutate accounts.
- `/test/capabilities.accountLinking` reports SDK API availability independently
  of `refreshUserIdChange: false`. The reason distinguishes disabled fixtures,
  absent SDK APIs, unreachable/incompatible Core, and an unqualified
  session-preserving fixture. CDI compatibility alone never enables this flag.
- `qualifyAccountLinking.js` uses fresh unmapped recipe users; no external user-ID
  mappings, authentication emulation or replacement session after linking.
  It compares pre/post primary UID, handle and tenant if refresh succeeds, and
  otherwise records the real unsupported outcome. It revokes every known session,
  deletes only its own recipe users with `removeAllLinkedAccounts=false`, discovers
  a newly created user after a lost creation response, and verifies absence.
  Cleanup failures are explicit; crash/failed-cleanup disposal caveats still apply.
- `accountLinking.test.js` covers unavailable gates, UID/handle/tenant checks,
  lost creation/link responses and cleanup continuation after one deletion fails,
  while retaining an unrelated user. These are fault-seam tests, not Core parity.
- `accountLinking.live.test.js` exercises the actual SDK/Core path and fails if
  a different pair now supports the transition, requiring the real fixture flow
  to be implemented/qualified rather than silently enabling this stub.

Pre-activation verification:

```sh
cd testHelpers/server
PATH=/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH npm test
PATH=/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH \
  CORE_CONNECTION_URI=http://localhost:9000 npm run test:account-linking
```

Both passed; live output explicitly says **UNAVAILABLE**, all probe users/sessions
cleaned, **not an R7 pass**. Next achievable action: obtain or identify a Core build
with a supported account-link operation that preserves the secondary session, then
rerun this qualifier. No known minimum version for that changed behaviour is claimed.
Do not alter Core session rows, substitute mapping, or create a new handle to bypass
the blocker. Android M2 tests/acceptance remain owned by the implementation agent.

Activation plan recorded before execution: only this agent's server PID 95662 on
8080 would be restarted through `/stop`, preserving Core PID 77488. Ownership was
rechecked via listener PID, parent 95661 startup script and server cwd. This restart
completed **13:47:52 UTC / 15:47:52 local**, using the exact `CORE_PROTOCOL_TESTS=1`
startup command below. Current server PID **3699**, parent **3698**, same server cwd;
background shell **`sh_187eac56a001tLb0XSQ7Jo9brY`**. Core remains PID **77488**.
No Gradle, testapp assertion, Android source or database reset involved.

Post-activation commands (same Node 16 PATH as above):

```sh
CORE_CONNECTION_URI=http://localhost:9000 CORE_PROTOCOL_URL=http://127.0.0.1:8080 npm run test:account-linking
CORE_PROTOCOL_URL=http://127.0.0.1:8080 npm run test:core
```

Both passed: linking qualification confirms the unsupported behaviour and the
live `/link-accounts` route returns 501 with the explicit reason; existing mapped
fixture login/refresh/protected/revocation/cleanup smoke remains green. Capability
readback reports `sdkApiAvailable:true`, `refreshUserIdChange:false`, reason
`NO_QUALIFIED_SESSION_PRESERVING_LINK_FIXTURE`. All probe-created users/sessions
cleaned; no claims about earlier untracked data. `git diff --check` passed.

Activated source SHA-256:

- `coreProtocol.js`: `6f3e2082b67d703c5b42ee374f5e30ccc29fa9003f69ae59dec400e0bae53b40`
- `accountLinkingCapability.js`: `60b73223398ec8c17d6c005bf07f6b934725230f165208826d28bd6aa10fdab9`
- `qualifyAccountLinking.js`: `73eb17bf48a8fba511b074724e854974862a23b89d5bf347d423b8a7b83737ce`

## Previous coordinated restart and HTTP verification

Restart completed **2026-10-29 13:38:46 UTC / 15:38:46 local** after coordination
confirmed no other live-backend tests were running. Latest cleanup routes are active.

Ownership verified before shutdown: port 8080 listener PID **77339**, cwd
`/Users/bogdan/src/supertokens/supertokens-android/testHelpers/server`, parent
**77338** running `sh ./testHelpers/startServer ../supertokens-root 8080`, matching
this agent's earlier background launch `sh_187c7a9ad0010L0LW1dPEIYKKU`.
Used only `curl --max-time 5 -sS http://127.0.0.1:8080/stop`; expected curl exit 52
confirmed process termination via the original shell completion notification.
No broad kill, `/after`, `/beforeeach`, `/startst`, database reset or disposal.

Started from repository root with this exact command:

```sh
CORE_PROTOCOL_TESTS=1 \
PATH=/Users/bogdan/.local/share/mise/installs/java/21/bin:/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH \
JAVA_HOME=/Users/bogdan/.local/share/mise/installs/java/21 \
./testHelpers/startServer ../supertokens-root 8080
```

Current server PID **95662**, parent **95661**, cwd unchanged. Background shell:
`sh_187e270e200168Wp6WJuiZLy37`. Node **16.14.0**, Node SDK **16.7.0**, CDI **4.0**.
Java on its startup PATH: **21.0.2**. Core **8.0.0** stayed running on port 9000
as the **same PID 77488**, with `/hello` returning `Hello` after restart and smoke.
The startup helper starts Express only; no Core restart was needed. Core's existing
3600-second validity configuration remains; Express recipe defaults are reinitialized
on startup (`getConfig(true, false)`), rather than preserving prior in-memory settings.

Uncommitted server source loaded at restart, SHA-256:

- `index.js`: `44d350c2e1663d0844df23ff94c3dea7ec86e547d24f77a77febc548dba9eaf6`
- `coreProtocol.js`: `da395905a46ae7e5012b29df6342d6b4b87f542a6c11a8e41ba2f9753f030bc8`
- `fixtureOwners.js`: `d960594430f1cccbb95d1b334a0d8e609206ce31b4fa91fc9bda2fc551a97546`

HTTP smoke, through the restarted server:

```sh
cd testHelpers/server
PATH=/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH \
  CORE_PROTOCOL_URL=http://127.0.0.1:8080 npm run test:core
```

**PASS:** capability reports Core reachable/compatible and mapped fixture available;
invalid creation 400; unauthenticated protected 401; real mapped fixture creation;
refresh rotation; authenticated protected 200 with Core promotion; session revocation
with `ownerCleaned: true`; rejected refresh 401; finally cleanup HTTP 200.

An additional Node HTTP/readback smoke created one uniquely named fixture through
`/test/email-change-owner`, authenticated through `/test/protected`, then called
`/test/cleanup-owner` twice: **200 / cleaned true**, then **200 / cleaned false**.
Readback through the supported SDK against live Core verified the created user
absent, mapping `UNKNOWN_MAPPING_ERROR`, and session information absent. Refresh
through the server then returned **401**. A final idempotent cleanup also returned
200. No credentials, user IDs or tokens printed; only assertion summary emitted.
All smoke-created fixtures were cleaned. Earlier old-server untracked data was not
deleted; the process-local registry caveat below still applies.

No Gradle commands or commits. Only this report changed during restart verification.

## Cleanup review follow-up

Addressed review `ses_e783420dbffeof0xUkWXCzrkNM` in server code only; no Gradle
commands or server restart performed during that review. The coordinated restart
above subsequently activated cleanup and verified the HTTP routes. The original
live route/test results below predate this follow-up.

- Added `fixtureOwners.js`: records ownership before creation, cleans partial
  creation failures, discovers the uniquely identified recipe user after a lost
  creation response, and retains failed cleanups for retry.
- Successful cleanup calls real SDK `deleteUser(userId, false)` and
  `deleteUserIdMapping`, verifies user/mapping absence, and removes its registry
  entry only after success. User deletion also revokes that user's sessions,
  verified against local Core.
- `/test/revoke-session` now cleans the owned fixture user/mapping as well;
  `/test/cleanup-owner` supports idempotent `finally` cleanup by returned fixture ID.
  It cannot delete arbitrary unregistered IDs. Live test `finally` checks cleanup
  response status instead of silently accepting failure.
- `/beforeeach` and `/after` clean tracked owners before stopping Core; cleanup
  failures are surfaced and prevent shutdown/reset rather than dropping the registry.
- Tracking is **process-local**, not crash-durable. Existing old-server fixtures
  and users orphaned across crashes are untracked. `/after` does **not** delete the
  database. A disposable database must be explicitly disposed/reset after such
  runs; no broad database cleanup was performed or claimed here.
- Fixture remains manual mapped Google recipe creation; no email-change,
  account-linking or OAuth coverage is claimed.

Verification, with Node 16.14.0 and installed SDK 16.7.0:

```sh
cd testHelpers/server
PATH=/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH npm test
PATH=/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH \
  CORE_CONNECTION_URI=http://localhost:9000 npm run test:core-cleanup
```

Both passed. Focused fault-injection tests cover mapping failures before/after
write, lost creation response, session creation failure, user/mapping deletion
failure followed by retry, idempotent success cleanup, unowned IDs and abandoned
responses. The separate real-Core cleanup check passed user/mapping/session
absence after successful cleanup, plus cleanup after session failure and lost
creation/mapping responses following real Core commits. It runs in a separate SDK process
and touches only its newly created users; the running server was not stopped.
Updated HTTP route smoke (`npm run test:core`) subsequently passed after the
coordinated restart recorded above.

## Result

**Live backend available.** Restored testapp tests now reach Core: **4 pass,
3 semantic failures**. All **6 non-proxy Android coreTest tests pass**, and the
new server live smoke verifies real mapped Google recipe users, refresh rotation,
child-access promotion and revocation. No fake backend substituted for Core.

The backend is left running on port **8080**, Core on **9000**, with
`CORE_PROTOCOL_TESTS=1`, access validity 3600 seconds, anti-CSRF disabled for the
existing coreTest protocol contract. Restored testapp sets its own configuration
through `/beforeeach` and `/startst`; do not run those suites concurrently.
Original background server shell ID: `sh_187c7a9ad0010L0LW1dPEIYKKU`, superseded
by the coordinated restart above. This section records the initial run configuration.

## Provenance and environment

- Android HEAD: `eda605ba8453bc716b82b83c04628a4c0ac4ba3e`; foundation, oracle and
  production R1 work are uncommitted concurrent changes. Results describe the
  working tree, not the clean commit. This task changed only server files and this report.
- Existing sibling root: `/Users/bogdan/src/supertokens/supertokens-root`, HEAD
  `58241d5ef772ae7fcc6405f288696878a8ceaac2`, no tracked changes after execution.
- Sibling Core source HEAD: `191b59db0d667faf0a148081eb46322981c05bda`, two existing
  dirty entries observed. Existing JARs were used, not rebuilt or attributed to a
  published release or clean source tree.
- `core/core-8.0.0.jar` SHA-256:
  `abe2aa2f6ad6a81a2a0b5aa56b27ffc091e2b1b0b93cf9a1e9c04a439312301b`.
- `plugin-interface/plugin-interface-5.0.0.jar` SHA-256:
  `1c61bb8364bc4d122a952370659209538962f01035bbac645e1431e143f9d88c`.
- Core Main.class major version 65 requires Java 21. Used
  `/Users/bogdan/.local/share/mise/installs/java/21`.
- Android Gradle used `/Users/bogdan/.local/share/mise/installs/java/temurin-11`
  (11.0.25+9); SDK variables point to `/Users/bogdan/Library/Android/sdk`.
- Node executable: `/Users/bogdan/.local/share/mise/installs/node/16/bin/node`,
  version 16.14.0, matching the existing integration workflow's Node major.
- `INSTALL_PATH`, `TEST_MODE`, `SUPERTOKENS_API_KEY` were initially unset.
  No secret values read or disclosed; no credentials or broad system installs required.
- Ports 8080/9000 initially closed. Existing `temp/config.yaml`, Core and
  plugin-interface artifacts made the local lifecycle usable. No sibling source
  modifications or root environment rebuild performed.

## Dependency mismatch and resolution

From `testHelpers/server`:

```sh
export PATH=/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH
npm ci --ignore-scripts --no-audit --no-fund
npm test
```

The locked SDK is **17.1.0**, declaring CDI **5.0** only. Core `/apiversion`
advertises 2.x, 3.0 and **4.0**, but not 5.0. The first live testapp run therefore
crashed Express at `/login` with the SDK's explicit incompatibility error; the
first test saw EOF, later tests connection refused. These are **environmental**
failures, not candidate session regressions.

Selected a compatible local matrix dependency without changing manifest/lock pins:

```sh
npm install --no-save --package-lock=false --ignore-scripts --no-audit --no-fund supertokens-node@16.7.0
```

SDK **16.7.0** declares CDI **4.0** and successfully negotiates with this Core.
The local `node_modules` retains this override; `npm ci` restores locked 17.1.0.
The only package.json edit is adding tests, not changing dependencies.

## Startup and commands

Read `CONTRIBUTING.md`, `testHelpers/startServer`, server lifecycle helpers and
`.circleci/setupAndTestWithFreeCore.sh`. The documented root `startTestingEnv`
delegates to a script that cleans/rebuilds sibling projects and obtains config;
that was unnecessary with existing artifacts. Used the supported server entrypoint
and lifecycle APIs instead:

```sh
CORE_PROTOCOL_TESTS=1 \
PATH=/Users/bogdan/.local/share/mise/installs/java/21/bin:/Users/bogdan/.local/share/mise/installs/node/16/bin:$PATH \
JAVA_HOME=/Users/bogdan/.local/share/mise/installs/java/21 \
./testHelpers/startServer ../supertokens-root 8080

curl --fail -X POST http://127.0.0.1:8080/beforeeach
curl --fail -H 'Content-Type: application/json' \
  -d '{"accessTokenValidity":3600,"enableAntiCsrf":false}' \
  http://127.0.0.1:8080/startst
curl --fail http://127.0.0.1:8080/test/capabilities
```

`/hello` returned `Hello`; capabilities report SDK/Core compatible,
`mappedGoogleSession: true`, `qualificationProxy: false`.

For every Gradle command below:

```sh
export JAVA_HOME=/Users/bogdan/.local/share/mise/installs/java/temurin-11
export ANDROID_HOME=/Users/bogdan/Library/Android/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

### Restored testapp

Built current candidate using root `./gradlew :app:assembleDebug --console=plain`.
From `testHelpers/testapp`:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*testThatOldSessionsStillWork*' \
  --tests '*testThatRefreshingOldSessionsWorksFineWithExpiredAccessToken' \
  --tests '*ConfigTests.testShouldDoInterceptionBasedOnUrl' --console=plain
```

**Seven tests executed; four passed and three failed**, with compatible Node/Core:

- PASS: both transports' `testThatOldSessionsStillWorkAfterRefreshing`.
- PASS: both transports' `testThatRefreshingOldSessionsWorksFineWithExpiredAccessToken`.
- FAIL: `com.example.example.android.ConfigTests.testShouldDoInterceptionBasedOnUrl`,
  assertion at line 178 (historical cross-port acceptance).
- FAIL: `com.example.SuperTokensOkHttpTest.okhttp_testThatOldSessionsStillWorkWhenUsingHeaders`,
  exception at line 1022; server saw login 200 then protected GET 401, with no refresh.
- FAIL: `com.example.SuperTokensRetrofitTest.retrofit_testThatOldSessionsStillWorkWhenUsingHeaders`,
  exception at line 719; same server sequence.

The three remaining failures are **semantic** and belong to Batch 4 (D1/D3), not
backend readiness. No testapp assertions or one-second lifetimes changed.
Artifacts: `testHelpers/testapp/app/build/test-results/testDebugUnitTest/` and
`testHelpers/testapp/app/build/reports/tests/testDebugUnitTest/`.

### Added endpoints and checks

Added `coreProtocol.js`, opt-in recipe configuration, ThirdParty reset support,
`coreProtocol.test.js`, `coreProtocol.live.test.js`, and server README.
Endpoints: `/test/capabilities`, `/test/email-change-owner`, `/test/protected`,
`/test/revoke-session`. Unsupported mapped-user fixtures return 501. Credentials,
mapping/provider readback, verification, refresh and promotion use actual SDK/Core APIs.
Manual Google recipe creation is not an OAuth exchange or an email-change operation.

- `npm test`: existing recipe-reset and legacy-CDI tests plus default-disabled
  capability/501 checks pass. No Core required by the disabled assertions.
- Before Core startup, capabilities explicitly reported Core unreachable and
  mapped fixture unavailable.
- `CORE_PROTOCOL_URL=http://127.0.0.1:8080 npm run test:core`: passed real mapped
  owner creation, input rejection, unauthenticated 401, refresh rotation, protected
  child-access promotion without refresh header, revocation and rejected refresh.

From repository root:

```sh
./gradlew :app:testDebugUnitTest -PcoreProtocolUrl=http://127.0.0.1:8080 \
  --tests com.supertokens.session.CoreSessionProtocolTest \
  --tests com.supertokens.session.CoreSdkCompatibilityTest --console=plain
```

**Six tests passed, zero failures/skips**: two delivery-order lineage tests plus
four URLConnection/OkHttp header/cookie lifecycle tests. XML artifacts in
`app/build/test-results/testDebugUnitTest/` record 2 + 4 tests, not an empty filter.
`git diff --check` passed.

## Remaining exclusions and next action

- `CoreProxyProtocolTest` requires a separate qualification proxy with
  `/__qualification/*` and `/auth/plugin/rownd/user`. Not implemented, not executed,
  and explicitly reported unavailable. Do not claim the full coreTest source set green.
- Only one live SDK/Core pair qualified. Locked SDK 17.1.0 still needs a CDI 5.0
  Core; full FDI/version-matrix, exposed-cookie-token feature, account-linking user-ID
  changes, device and lost-response parity remain separate coverage.
- Parent-token grace was not characterized; this does not promote FakeBackend to an oracle.
- Next achievable work: run Batch 4's scope/migration fixes against the three now-
  reproducible failures; provision a matching newer Core for the locked SDK matrix.
- Shutdown when no longer needed: `POST /after`, then `GET /stop` (curl gets an
  expected empty response on process exit). The existing lifecycle creates/removes
  disposable runtime files under the sibling installation, not its database.
