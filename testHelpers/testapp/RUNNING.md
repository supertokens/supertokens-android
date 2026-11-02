# Owned integration entrypoints

Run from the repository root, sequentially in an exclusive build lane. Install the
selected Node SDK in `testHelpers/server` and build the selected Core/plugin pair
first. Node 16.7+ (or newer supported Node), Android SDK 29/32 and Java 11 are needed
for the Android builds; `ST_TEST_JAVA` selects the Java executable for Core separately.
Choose an existing writable evidence directory and unused ports (not 8080/9000).

```sh
mkdir -p owned-integration-evidence
JAVA_HOME=/path/to/jdk11 ST_TEST_JAVA=/path/to/core-jdk/bin/java \
node testHelpers/server/runOrdinary.js /path/to/supertokens-root \
  "$PWD/owned-integration-evidence" 18081 19001 \
  node "$PWD/testHelpers/server/runOwnedTests.js" ordinary "$PWD"
```

Repeat with `retrofit` for the full optional Retrofit 2.9 / OkHttp 3.14 profile.
Both profiles explicitly exclude `BackendMutationCoverageTest`, `ExposedCookieLiveTest`,
and `PublishedUpgradeLiveTest`; those classes belong to their dedicated modes. The
exclusions are recorded in the test report and are not evidence of passing those lanes.
All other skips still block the release receipt.

For the dedicated **six-method optional-Retrofit mutation lane**, add the explicit
`CORE_PROTOCOL_TESTS=1` environment assignment and replace `ordinary` with `mutation`.
It provisions and qualifies the exact owned Node/Core instance, requires nonproxy
mapped-owner and tenant capabilities, and accepts only the exact six named methods
with 6 passed, 0 skipped and 0 failed. Each test's existing `@After` assertion
requires `/test/cleanup-owner` HTTP 200 with `cleaned:true`. The outer runner must
also finish `DRAINED → CORE_CLEANUP_ACKNOWLEDGED → BACKEND_STOPPED` with no RETAINED file.

`matrix` also requires `CORE_PROTOCOL_TESTS=1`: actual pair qualification, its gate,
`npm run test:core`, root clean/build and the three existing Core test classes,
strict six-method optional-Retrofit mutation acceptance, an owned proxy with
exactly two live qualification methods and zero remaining fixture/transport jobs,
then both full testapp profiles. Missing mapped-owner/tenant capabilities produce
explicit pair-scoped exclusions, never passing mutation/proxy results. Incompatible
or unreachable backends fail. The proxy uses backend port + 1 (also required unused),
is identity-checked before requests/signals and stopped before final backend cleanup.
This is CircleCI's selected Node/Core matrix path. Per-step reports, exclusions and
proxy cleanup receipts live in the owned installation's `matrix/` directory.
`qualify` performs the same exact-instance qualification without Gradle execution.
Every invocation owns a new instance; qualification is not reusable after a restart.

The portable `node testHelpers/testapp/runTests.js MODE` child entrypoint runs root
artifact preparation and standalone Gradle as **two processes**. Both force task
execution; no offline/cache-copy assumptions or task exclusions are used. An
exclusive `.integration-lock`, per-invocation nonce, source/artifact digests and
Java/Node/versioned-source receipts guard the handoff. Testapp validates at
configuration and execution, rejecting direct Gradle invocation and stale receipts.
The lock blocks competing entrypoint runs. If interrupted, prove worker drain
before manually removing a stale lock. Do not edit sources during a run.

Fresh per-run XML, task selection, cases, classpath and artifact receipts live under
`testHelpers/testapp/app/build/integration-receipts/<nonce>`. Backend identities,
qualification, logs and cleanup audits live under the selected evidence directory.
Retain both, including failures. Receipt digests detect accidental staleness; they
are not a security boundary against a caller deliberately forging build inputs.

Published upgrade callers use `node testHelpers/testapp/runTests.js upgrade
-Pupgrade.baseUrl=... -Pupgrade.fdiVersion=...` within their separately provisioned
owned instance. Only `-Pupgrade.*` options are accepted there; this entrypoint does
not create upgrade protocol/negotiation evidence or choose a matrix member.

No Linux/CircleCI or mutation result is implied by this wiring; validate those
lanes explicitly before reporting acceptance.
