# Mandatory FDI upgrade integration — BLOCKED handoff

This gate is not release-enabled. All four owned selectors and observed-phase
export are implemented, including HEADER `/login-2.18`, but historical live
qualification is unperformed. Per-FDI plans remain null and fail before dispatch.
Android acceptance for the four published JVM cookie failures is approved but
not integrated; existing gates still reject those failed observations.

## Inspected producer contract

Integrated exposed code originated in approved TEMP bundle `exposed-cookie-review.yUCqMs`, particularly
`after/testHelpers/server/{runOwnedTests.js,ownedExposure.js}` and
`after/testHelpers/testapp/{runTests.js,exposureAcceptance.js}`.

Actual staged owned signature:

`node testHelpers/server/runOwnedTests.js upgrade ROOT MODE FDI LOGIN_VERSION HANDOFF_VERSION REFRESH_VERSION`

`MODE` is HEADER, COOKIE or EXPOSED_COOKIE. The original five options remain valid
for primary `/login`. HANDOFF_VERSION may instead be a quoted JSON object with
exact keys LOGIN, REFRESH, SIGN_OUT, PAYLOAD_UPDATE. Values are version strings
2–5 except SIGN_OUT, which must be ABSENT. A primary scalar explicitly normalizes
to that version for the three present flows and ABSENT for SIGN_OUT.

Historical syntax appends `/login-2.18` as a sixth option, requires HEADER and
requires the JSON handoff object; scalar historical handoffs fail before setup.
No historical defaults exist. For example, the following is **synthetic test
data only**, not a reviewed plan or live observation:

`HEADER FDI 2 '{"LOGIN":"2","REFRESH":"5","SIGN_OUT":"ABSENT","PAYLOAD_UPDATE":"5"}' 5 /login-2.18`

Policy `phaseVersions["MODE:/route"].publishedHandoff` requires that exact object
for every selector. Adapter passes it as one JSON argument. Gradle forwards
`upgrade.expectedPublishedHandoffVersions` and `upgrade.loginRoute`; Java checks
the exact keys and chooses the current flow. Export and final receipt independently
compare observed phases to their flow-specific plan. Missing/extra keys and
SIGN_OUT/value swaps reject before dispatch; swapped present-version observations
reject export/receipt against the reviewed plan.

`ownedExposure.select` produces the real `-Pupgrade.baseUrl`, `fdiVersion` and
three `expected*Version` properties. EXPOSED_COOKIE alone uses `enableJWT:true`.
The outer caller must use `runOrdinary.js` on fresh owned instances/unused ports;
the inner command alone is not an ownership-safe invocation.

The adapter validates the selector contract and dispatches all four selectors in
separate owned lifetimes. HEADER directories are `upgrade-HEADER` and
`upgrade-HEADER-login-2.18`. Backend `/login-2.18` directly requests real CDI2.18
Core sessions and emits header tokens; capability advertisement alone is not a
passing qualification. Owned qualification is derived only after all four actual
published-first flows and scoped/lifetime cleanup succeed, bound to run/source/
preparation nonce. Non-owned prior-evidence handling remains separate.

## Integrated source and remaining requirements

1. `runOwnedTests` argument parsing is reconciled: matrix accepts its release-context
   path; upgrade accepts the five primary or six historical options above. Do not feed the
   matrix context path to `ownedExposure.select`, which rejects unexpected options.
   The exclusive matrix return, upgrade/exposed setup and owner checks are preserved.
2. Run `CoreSdkCompatibilityTest` separately with `-PcoreProtocolUrl` on every FDI;
   require its four exact methods. The remaining two `CoreSessionProtocolTest`
   methods and one `CoreLostRefreshResponseTest` form the separate R7 suite. The
   baseline currently creates a mapped Google owner: inability to provide that
   fixture is a blocking test failure, not permission to exclude the baseline.
3. For each FDI, run all four flows for each required selector:
   HEADER/COOKIE/EXPOSED_COOKIE `/login`, HEADER `/login-2.18`. This is 16 required
   rows per FDI. Historical selector implementation now needs independent review
   and actual live qualification; host success is not live evidence.
4. Review and populate `integration-policy.json`'s currently-null per-FDI upgrade
   plan with actual Node/Core versions and separately expected published-login,
    per-flow published-handoff and candidate-refresh versions for each selector. Do not copy
   one known `5/5/5` header result across the matrix or infer real v2–v4 execution.
5. Actual test/runner export is integrated: `PublishedUpgradeLiveTest` writes one
   create-new JSON row after phase assertions and `UpgradeFixture.close` succeed.
   Its values come from `TokenEvidence` schema 2, not configured expectations.
   `runTests` emits the owned completion pointer only after strict exact-four
   acceptance and fresh-artifact revalidation. Transport failures/skips/cleanup
   errors cannot produce completion. SIGN_OUT handoff must be ABSENT.
6. `publishedUpgradeExport.js` aggregates fresh source/preparation nonce receipts and binds every selection to
   SDK source SHA/digest, attempt/nonce/FDI/policy, published AAR checksum and actual
   candidate AAR hash, exact Node SDK/Core versions and implementation hashes,
   owned runtime identity and tested `enableJWT/sessionJwt` configuration. All
   selected instances must use the resolved pair for that FDI. The validator in
   `upgradeEvidence.js` specifies the normalized record. Report/preparation and
   row-observation fields come from actual outputs. The published AAR pin was
   enforced by the required fresh `extractLegacy054` task; the candidate hash is
   read from its actual preparation artifact manifest.
7. Use a new owned lifetime for each selector. Wait for `runOrdinary` zero exit
   after command drain, acknowledged Core cleanup, stopped backend and immutable
   dependency check. Validate its lifecycle/absence of RETAINED before attaching
   the selection to the matrix draft. Only after **all** selectors succeed may the
   adapter replace `published-upgrade: missing` with real completed evidence.
   The final receipt writer and publisher independently validate it. `runOrdinary`
   now emits `lifecycle-completed.json` only after zero command exit, drain, Core
   acknowledgment, backend stop and dependency recheck, with no interruption.
   Export refuses absent/mismatched lifecycle completion or RETAINED evidence.

## Known hard failures

Published-cookie Java11 failures (`IllegalArgumentException: Empty cookie header
string`) are known. They cannot be skipped, converted to capability exclusions,
replaced with candidate-only runs or credited from the separately pinned API34
consumer. Release remains blocked until faithful platform execution proves the
required coverage. The user has now approved platform-specific Android acceptance
for these four failures, preserving failed JVM observations and requiring matching
backend/FDI/artifact/source bindings. That policy is not integrated here; current
API34 evidence is not blanket historical acceptance. Host synthetic tests prove
the current rejection contract, not live success.

No Gradle/backend/device/remote-CI runs were performed for this integration. Host
tests include the four recorded aa1ca82d published-cookie failures rejecting
export, release receipt and PATCH. Independent parent review and actual CI
execution remain separate. Historical live qualification and reviewed per-flow
phase plans remain prerequisites; no secrets or pins were guessed.
