# Release-gate integration — blocked, pending independent review

Integrated the reviewed scaffold from `release-gate-review.e6ymTm/review.patch`,
verified SHA-256
`ca7f44a4afff2c068f1a0ef277875852e7f3ce0af1a4e712573c683ebb453879`.
Existing files were rebased as targeted hunks, including the already-integrated
exposed/upgrade runner. No old orchestration file replaced the current version.

## Required behavior

- Every declared FDI requires the four exact `CoreSdkCompatibilityTest` methods
  in a dedicated `-PcoreProtocolUrl` invocation. Root build is not a substitute.
  The three R7/lost-refresh methods remain a separate capability-dependent lane;
  the reviewed full-capability anchor policy is preserved.
- Every FDI also requires published-first live upgrade: all four flows in three
  primary modes plus the historical `/login-2.18` selector. Missing/excluded/failed
  upgrade evidence cannot authorize publication. API34 coverage is additional.
- `runOwnedTests` retains current upgrade/exposed configuration and identity gates;
  the optional matrix context is parsed separately rather than passed as exposure
  options. Existing ordinary/Retrofit/mutation dispatch is preserved.
- The publisher still checks the exact source SHA, policy, workflow/build attempt,
  random nonce, required lane evidence, and GitHub repository/ref/event/workflow/
  run/job-attempt identities. Positive integer ID guards are unchanged.

## Actual primary export now implemented

`PublishedUpgradeLiveTest` emits create-new per-flow JSON **after** actual phase
assertions and `UpgradeFixture.close` succeed. Observed versions come from the
published/candidate `TokenEvidence`, never from expected values. Rows include the
actual Node/FDI/exposure configuration and owned run/preparation nonce.

`runTests` publishes an owned completion pointer only after exact-four acceptance,
fresh-artifact revalidation and the existing completed receipt. The exporter reads
those report/preparation/row files and actual Core/SDK identity; skipped/failed
rows, mismatched phase plans or source/nonce/configuration cannot be exported.
The published pin was verified by fresh `extractLegacy054`; the candidate AAR hash
comes from the actual preparation manifest.

`runOrdinary` emits `lifecycle-completed.json` only after command success, command
drain, Core cleanup acknowledgment, backend stop, dependency-integrity recheck and
no interruption. The exporter and matrix finalizer require that record, matching
owner/instance/lifecycle hashes and no RETAINED marker before issuing receipts.

The adapter uses bounded fresh owned lifetimes for all four selectors, including
HEADER `/login-2.18`. It retains partial results as a draft; all selectors must
pass before attaching an upgrade lane. Handoff expectations are exact per-flow
maps; primary scalar CLI normalization remains supported.

## Release remains blocked

- All per-FDI phase/version plans remain `null`. No Node/Core versions, historical
  token versions, backend revision pins or credentials were guessed. Missing plans
  stop the adapter before dispatch.
- Historical-route selection/export is implemented but not live-qualified.
- Android acceptance is approved but not integrated for four actual published 0.5.4 Java11 failures:
  COOKIE and EXPOSED_COOKIE REFRESH/SIGN_OUT. Source reports from the qualified
  `aa1ca82d327e3d749bb13ee0a126d23e70f904c623cca28de79b2e285f634377` AAR are recorded
  in `fixtures/published-cookie-failures.json`, with original report hashes. Host
  tests feed those exact failed rows through export/receipt/PATCH rejection.
  Current gates still reject them. Future Android acceptance must preserve failed
  JVM evidence and bind backend/FDI/artifact/source; current API34 results do not
  automatically qualify historical pairs.

## Verification performed

- 164 release host tests pass, zero failures/skips: API identity/type checks,
  fake-clock polling, required baseline/upgrade policy, actual failed-row rejection,
  exporter freshness/configuration/phase/cleanup faults, and publisher composition.
- Current `npm test` and `npm run test:entrypoints` pass. The latter includes the
  actual supervisor control path with all external effects mocked: success plus
  command/drain/Core-cleanup/backend-stop/dependency/interruption failures. Only
  complete success produces the lifecycle receipt.
- The changed Java test exporter and its fixture compile with the recorded JDK11
  and existing classpath, output isolated in TEMP. This is compile-only, not a
  fresh Gradle artifact or a test/platform execution.
- YAML, shell/embedded shell and JavaScript syntax checks; real job-name/trigger
  resolution; exact Core test identity checks; mandatory publisher gates and
  native Circle dependency; whitespace and source-preservation checks.
- All `app/` SDK sources and existing build configuration are byte-identical to
  task entry. Earlier qualification is retained as prior evidence, not rerun.

## Proof and review handoff

Audit directory:
`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/release-gate-integration.b6ec680c`

Contains `pre-source-hashes.json`, exact `before/` snapshots, `task.diff`,
`post-source-hashes.json`, `proof-sha256.txt`, `validation.txt`, host TAP/npm logs,
and compile command/output. Small before-snapshots reconstructed from inverse
hunks were checked against the independently captured task-entry hashes.
Independent parent review is requested; this report is not that review.

No Gradle, live backend/device, hosted CI, real HTTPS status query, publication,
commit or external write was performed. HTTP API tests mock the request boundary;
TLS/DNS/authentication/rate limiting/socket deadlines and real Linux images remain
unverified. Synthetic success fixtures are not live evidence.

External configuration remains user-owned: Actions-read token in the publishing
context, existing publishing credential, dedicated test-setup credential, four
reviewed backend revision pins, and reviewed per-FDI expectations. Hosted KVM/
images/toolchains and actual API schema behavior need controlled validation.
Workflow permissions remain read-only; no privileged PR trigger, secret injection
into PRs, branch-protection mutation or weakening of external/manual checks.
