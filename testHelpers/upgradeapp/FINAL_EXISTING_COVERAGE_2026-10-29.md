# Final existing coverage and rank 8/13/14 integration

Follow-up: [2026-10-30 recovery](../../docs/compatibility-wiki/recovery-2026-10-30.md)
records the subsequently completed tenant repair, acknowledged original-owner
cleanup, six live passes, proxy qualification and the new matrix compilation blocker.
The pending-cleanup state below is historical.

## Existing coverage: green on 0.6.0

- Full `testHelpers/testapp :app:testDebugUnitTest --rerun-tasks`: **117/117**,
  zero failures/errors/JUnit skips, 5m27s. Historical assertions unchanged.
- Actual API34 `emulator-5580` published upgrade/rollback: **20/20 stages**,
  including login-B, refresh, logout, cookie and all four scoped cleanups.
- Actual R8: **one unmapped setup + two minified passes + cleanup**. A/B apps
  nondebuggable; application DEX cookie-store names differ `r8a.v6` → `r8b.v6`.
  Test DEX has no SDK copy. The runner checked A evidence linkage, hashes, signer,
  mapping/DEX correspondence and unchanged app data. All three owners cleaned.
- Eight R8 host protocol checks passed before device execution.

Testapp used the fresh debug AAR below. Upgrade and R8 used the **exact requested
release AAR**, freshly rebuilt with source/build manifest unchanged before/after:

- Debug SHA-256: `42f0ea8f23839b97c9c895a6076e1da1fb441a24a2b2176d79047a3fd4c2dcc1`
- Release SHA-256: `406c3a6640bc1f3a7d00aae58d98fd274938fa307ed6f8b94fdfc3f477c59908`
- Source/build manifest SHA-256: `049538d53489fff0098b0f05b6c52c56178342629fb773c13f231360d8f423be`
- R8 A app: `0374db8226463a8a65bc03a4d2223b22e39600cc2b3f97decbde689a202ecb53`
- R8 B app: `0e595a58e69c4431a380fa0b463ae27802d7d178bafbd6a226024a4f27e5b22d`
- Mapping A: `4d60189a31e88d03c76bc0f32363b371629b29426b8c512374d4661eaaea673e`
- Mapping B: `360fe459f4065a2ce7e1dbdcd5eba91490d4d69b8170034deffb81a1cfedc0d1`

This is Node SDK **16.7.0 / Core 8.0.0**, not the FDI matrix. Feature flags:
`sessionJwt:false`, `sessionClaims:true`, `v3AccessToken:true`. The 117-test run
reported no JUnit skips; feature-specific branches do not certify unavailable
features or other versions. Account-linking was requalified after Core lifecycle
changes and remains **LINK_REVOKED_SESSION / R7 excluded**, not passed.

Testapp's own lifecycle stopped Core. Restored its documented test configuration
through setupST/startst, with validity 3600 and anti-CSRF disabled, without an extra
global reset. Upgrade/R8 ran to cleanup before any backend source activation.

## Reviewed rank 8/13/14 patches: integrated, live blocker found

All three reviewed patches applied cleanly: server, testapp, JVM tests. No SDK
production change. Rebuilt candidate with fresh source/build provenance; its debug
AAR is byte-identical to the debug hash above. The SDK source/build manifest also
remains unchanged. Four focused Node scripts and **5/5 new JVM tests** passed.

Activated server changes only after device runs/cleanup: verified the old Express
PID/cwd, stopped its owned Core using shutdownOwnedCore, terminated only that
Express process and restarted with Node16, Java21 and `CORE_PROTOCOL_TESTS=1`.

Actual `BackendMutationCoverageTest`: **6 executed, 5 passed, 1 failed, 0 skipped**.

Passed with acknowledged scoped cleanup:

- payload update: preserved owner, one payload event, no refresh wire;
- handle payload update visible after refresh;
- raw logout followed by rejected access/refresh clears once;
- raw revokeAll followed by rejected access/refresh clears once;
- claims 403 neither refreshes nor clears.

**Failed:** `nonPublicTenantLoginAndRefreshRetainTenantAndOwner`, at fixture login:
expected HTTP 200, received **500** (`BackendMutationCoverageTest.java:68`). Tenant
capability was advertised true. This is a failure, not an unsupported-feature skip.

### Concrete fixture/API incompatibility

`fixtureOwners.js:55` probes an absent tenant with `Multitenancy.getTenant` before
creation. Its SDK implementation expects a `TENANT_NOT_FOUND_ERROR` response to
return undefined. Actual Core8 responds **HTTP 400**, “AppId or tenantId not found”.
The SDK throws; cleanup at `fixtureOwners.js:10` repeats the same failing probe.
Tenant-delete verification at line 41 has the same assumption.

Read-only probes distinguish this from unavailable multitenancy or Android failure:

- existing public tenant lookup: 200;
- tenant listing: 200, generated fixture tenant absent;
- exact failed tenant lookup: 400;
- exact external-ID mapping lookup: 200 `UNKNOWN_MAPPING_ERROR`;
- exact user lookup: 200 `UNKNOWN_USER_ID_ERROR`.

Failure occurs before tenant/user creation, before Android receives a session.
No Android SDK defect is established. A fixture repair should use an actual-pair
compatible absence check (for example the tenant-list API), preserve genuine
errors, and cover creation and deletion verification; no repair was applied here.

### Pending scoped reconciliation — do not reset it away

Known failed owner/tenant ID:
`android-test-7cddcd02407108f01aa52e44fbbfa6f0`.

The exact-ID `/test/cleanup-owner` retry returned **500**. Core has no corresponding
tenant/user/mapping, but the running Express owner map retains the pending record.
**Cleanup is not acknowledged.** Preserve this backend process for the fixture
repair/manual scoped reconciliation. Do not retry tenant creation, globally reset,
or claim six live passes. Other five live fixtures acknowledged cleanup.

## Evidence / handoff

Private root:
`historical external evidence: final-existing-060-06bg4wfu`

Contains full testapp log/XML, source manifests and build receipts, archived APKs,
all upgrade/R8 typed XML/transcripts/signatures/DEX evidence, rank 8/13/14 Node/JVM
and live logs/XML, server log, exact-ID cleanup failure and read-only diagnosis.
`combined-results.json` separates green existing coverage from the live blocker.
Raw captures are private and may contain credentials.

Backend shell handle: `sh_1889c3cd2001q0qoM0XJCy5CVc`. Express/Core remain healthy;
no running suite remains. Lanes released for coordinator-directed reconciliation.
No commits or publication.
