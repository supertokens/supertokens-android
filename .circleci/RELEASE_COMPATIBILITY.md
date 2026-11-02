# Release compatibility enforcement

**Release remains blocked.** All four per-FDI upgrade selectors/export are implemented,
but historical live qualification and reviewed per-flow version plans are unresolved.
Android acceptance for four known Java11 published-cookie failures is approved but
not integrated; current gates still reject them. See
[qualification status](../docs/COMPATIBILITY_STATUS.md).

`markAsSuccess.sh` now fails before `testPassed: true` unless:

1. Every declared FDI integration lane produced a `requirements-satisfied` receipt for this exact
   `CIRCLE_SHA1` and `CIRCLE_WORKFLOW_ID`. CircleCI's existing `requires: test-unit`
   matrix dependency stays authoritative; receipts are additional checks, not
   externally supplied substitutes. Exit zero is insufficient. Each receipt is
   written only after `doTests.sh` returns zero **and** its structured per-lane
   evidence satisfies the checked-in capability policy, then transferred through
   the same workflow workspace. Source revision/digest, Circle workflow/build
   attempt, random attempt nonce, FDI and policy hash must match the context created
   before testing. Producer evidence includes the owned backend/Core endpoints,
   instance/run IDs, qualified-identity and dependency hashes, actual execution
   outcomes and fresh test reports. The publisher validates the evidence again.
2. GitHub Actions has successful **push** runs at the exact SHA and tag/branch:
   `unit.yml`, `device.yml`, `release-compatibility.yml`, and
   `nightly-compatibility.yml`. The gate checks actual workflow paths and concrete
   job names defined in these files. It checks the latest run's current attempt,
   exact repository/ref/SHA, every required job's SHA/run/attempt, and strictly
   `completed` + `success`. PR, manual and scheduled runs cannot authorize release.

Absent/pending runs wait at most 100 minutes; failed/skipped/neutral/cancelled jobs,
missing jobs in a completed run, malformed responses, pagination truncation,
authentication failures and API errors fail closed. Requests have absolute 20s
deadlines; CircleCI wraps the publisher in a 105-minute process-group timeout.
Publication itself has a 30s deadline. No cached statuses or wildcard check names.

Existing publication scope stays dev tags; production-tag docs publication and
manual/admin controls remain separate. API, device, consumers and extended matrix
workflows now run on `N.N`, `test-cicd/**`, `dev-v*` and `v*` pushes. Circle's
historical integration matrix still runs on its existing dev-tag/test-cicd scope.
This does not create a new production publisher or broaden its API permissions.

## External configuration required

`integration-policy.json` explicitly requires root-build, ordinary, retrofit,
four exact baseline `CoreSdkCompatibilityTest` methods via `-PcoreProtocolUrl`,
and published-first live upgrade on **every** declared FDI. Root build is not a
baseline Core execution. FDI **4.1 is the required R7/full-capability anchor**: Node
Core, three R7/lost-refresh JVM Core tests, six mutation tests and two proxy-ordering tests with
zero-leak cleanup must execute. If that selected backend pair is unsupported,
release is blocked; no automatic weakening or fallback. This is an explicit
proposed release policy for owner review, not a claim that 4.1 currently supports
all capabilities. Older pairs may retain `excluded` for unavailable mapped-owner/
tenant capabilities only for the separate R7 lanes; neither baseline Core nor
published upgrade can be excluded. Those R7 exclusions remain visible in the receipt and are
never described as test passes. Available optional lanes must execute successfully.
Missing/skipped/failed/excluded required lanes, empty reports, skipped tests and
excluded tasks are rejected even when the matrix process exits zero. Adding/removing
FDIs requires a matching policy update; an empty matrix cannot pass vacuously.

Upgrade requires 16 exact published-first rows per FDI: four flows for three
primary `/login` modes plus HEADER `/login-2.18`. Node/Core/FDI, observed phase
versions, exposure flags, source/attempt, fresh SDK/preparation hashes and actual
scoped/owned cleanup must match. The pinned API21 and API34 upgrade/R8 jobs add
coverage, never a substitute for this matrix. No default version expectations are
invented: all per-FDI plans remain null pending review, so the adapter fails closed.
The integrated owned selector also supports HEADER `/login-2.18`; structured
phase/cleanup export consumes actual observations. Historical live qualification
remains pending. Primary-only or API34-only results cannot pass.

### Owned published-upgrade selector and export

Use fresh owned instances and unused ports through `runOrdinary.js`; the inner
`runOwnedTests.js` command alone is not ownership-safe. The inner signature is
`upgrade ROOT MODE FDI LOGIN_VERSION HANDOFF_VERSION REFRESH_VERSION`, with
MODE `HEADER`, `COOKIE`, or `EXPOSED_COOKIE`. Primary `/login` accepts a scalar
handoff or a quoted JSON object with exactly `LOGIN`, `REFRESH`, `SIGN_OUT`, and
`PAYLOAD_UPDATE`: present versions are strings 2–5 and `SIGN_OUT` is `ABSENT`.
The scalar normalizes to that version for present flows and `ABSENT` for sign-out.
Historical `/login-2.18` is a sixth option, requires HEADER and the JSON object;
it has no inferred defaults. Policy's `phaseVersions["MODE:/route"].publishedHandoff`
requires the object for every selector. The adapter passes it as one JSON argument;
Gradle forwards `upgrade.expectedPublishedHandoffVersions` and `upgrade.loginRoute`.
Do not pass a matrix release-context path to the upgrade selector.

Each FDI requires four flows for HEADER, COOKIE and EXPOSED_COOKIE `/login`, and
HEADER `/login-2.18`: 16 published-first rows. EXPOSED_COOKIE alone enables JWT.
Use a separate owned lifetime per selector. `CoreSdkCompatibilityTest` runs with
`-PcoreProtocolUrl` on every FDI and its four exact methods are mandatory. The
separate R7 tests cannot substitute; missing mapped-owner capability blocks the
baseline. Capability advertisement is not a passing historical execution.

Plans remain null until reviewed against the resolved Node/Core pair and per-flow
observations; do not infer historical token versions. Export requires observed
phase rows after scoped fixture cleanup, exact-four acceptance and fresh artifacts.
It binds each selection to source SHA/digest, attempt/nonce/FDI/policy, both AAR
hashes, Node/Core versions and implementation hashes, runtime identity and exposure
configuration. Require `runOrdinary` success after command drain, acknowledged Core
cleanup, backend stop and unchanged dependencies, plus matching lifecycle completion
and no RETAINED record. Only all successful selectors permit completed upgrade
evidence; final receipt and publisher independently validate it. Failed JVM cookie
observations remain failures unless correctly bound approved native acceptance is
integrated and passes; a host mock or API34-only run is not historical qualification.

- CircleCI publishing context: `GITHUB_ACTIONS_READ_TOKEN`, a fine-grained GitHub
  token restricted to `supertokens/supertokens-android`, **Actions: read** and its
  mandatory Metadata: read. No checks/status write, workflow dispatch, contents
  write or administration permission. Keep it out of PR contexts. GitHub App
  installation tokens with Actions read work too, but must remain valid through
  polling (one-hour installation tokens need an externally managed refresh).
- Existing `SUPERTOKENS_API_KEY` publishing permission remains required in Circle.
- GitHub repository variables `COMPAT_ROOT_SHA`, `COMPAT_CORE_SHA`,
  `COMPAT_PLUGIN_SHA`, `COMPAT_NODE_SHA`: reviewed, mutually compatible, immutable
  40-character revisions of the four public SuperTokens repositories. Missing
  pins deliberately block the consumer job; no invented versions/default branches.
- GitHub secret `COMPAT_CORE_SETUP_API_KEY`: credential needed by the root's
  existing `utils/setupTestEnvLocal` / testing-config setup. Limit its upstream
  scope to test setup if supported. It is used only in the push/manual consumer
  setup step, never in a PR or `pull_request_target` workflow. Use only trusted
  release refs/manual refs; repository write access is the trust boundary.
- Selected Core/Node revisions and test setup must support real account-linking
  fixture capability. `runOwnedTests.js qualify` checks the actual running
  instance; unavailable/proxy capability is a failure, never a substitute pass.
- Hosted Linux KVM, Android default API21 and API34 x86_64 root-capable images, obsolete
  API17/API19 x86 images, JDK11/17/21 and legacy Maven dependencies must be available.
  GitHub workflows use only `contents: read`; checkout credentials are not persisted.
- Circle executor must provide Node16+, GNU `timeout` and enough permitted job
  runtime for 105 minutes. Read-token rotation and external CI availability remain
  operator responsibilities. No admin branch-protection assumption or changes.

Existing externally required/manual checks remain in place. An administrator may
separately require these concrete jobs on branches after real runs establish them;
this patch neither changes rulesets nor claims merge-queue support.

## Owned device fixture and evidence

New consumer job builds the pinned Core through the existing root build entrypoint,
checks the Main JAR and plugin artifacts, pins the Node SDK before npm resolution,
and records source/JAR/dependency hashes. Root setup may swallow nested failures;
the artifact checks and subsequent live qualification prevent a green empty build.
No cached backend/artifact can stand in for the running instance.

Candidate AAR must come from the exact clean checkout and its fresh build receipt.
Published 0.5.4 upgrade/rollback uses the existing checksum-enforcing runner, all
four scenarios and cleanup. R8 uses the same candidate receipt, an unmapped seed,
mapping A, mapping B linked to A's evidence, and cleanup; distinct actual mapped
cookie-store names and SDK application DEX ownership are enforced by the existing
runner. The API21 and API34 jobs are configured for this scope; they do not prove
historical obfuscation recovery or qualification without successful bound runs.

All backend execution goes through `runOrdinary.js`, with exclusive temporary
source/dependency copies, ownership IDs, listener checks and command draining.
Ports 18081/19001 are private to the hosted job. Device access uses
`http://10.0.2.2:18081`; no reuse of unguarded 8080/9000 or shared services.
Emulator boot: 600s; consumer stage group: 35m plus 60s shutdown grace; whole job:
90m. Existing runners bound individual adb commands/tests/installations.

Public artifacts: APKs/AAR, mappings, clean source/build receipt, dependency lock,
backend source/JAR hashes, runtime/acceleration provenance, passed-stage summary
and private-evidence hashes. Raw preferences, live tokens, logcat, backend and
instrumentation logs stay private on the disposable runner and are **not uploaded**.
Failure may leave owned fixtures until runner disposal if ownership-safe cleanup
cannot be proven; it never resets unrelated services or converts cleanup failure
into success. Detailed failure recovery needs an explicitly private retention
mechanism; none is silently provisioned here.

## Verification and source references

Host-only: `node --test .circleci/requiredCompatibility.test.js .circleci/integrationReceipt.test.js .circleci/publishedUpgradeExport.test.js` uses fake API
responses and a fake publisher command path. It covers stale SHA, missing/skipped
jobs, wrong workflow/ref/repository/attempt, failed/newer runs, pending, API errors,
pagination and stale/missing integration receipts. It also covers malformed IDs,
missing required fields, empty FDI configuration, fake-clock polling deadlines and
zero-exit excluded-matrix -> real receipt rejection -> publisher PATCH not called.
The positive composition passes only when every required lane has execution/report
evidence. No remote statuses are changed. HTTP mocks replace the API boundary;
they do not test real TLS/authentication/rate-limit responses or socket deadlines.

Actual GitHub/Linux, Core build/qualification, emulator and publication execution
are unrun. The first controlled dev tag must establish the pinned backend/image
availability and demonstrate that intentionally failing one required job prevents
the final PATCH. Do not label this rollout verified from host tests alone.

Sources inspected: repository `.circleci/{config.yml,config_continue.yml,doTests.sh,
setupAndTestWithFreeCore.sh,markAsSuccess.sh}`, existing device/nightly/unit workflows,
`runOrdinary.js`, `runOwnedTests.js`, consumer README and R8_SMOKE.md.
Upstream build convention: https://github.com/supertokens/supertokens-root/blob/master/utils/setupTestEnv
(reviewed source, actual CI execution uses configured pinned SHA).
GitHub attempt/job schema:
https://docs.github.com/en/rest/actions/workflow-jobs#list-jobs-for-a-workflow-run-attempt
