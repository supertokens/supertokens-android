# Opt-in backend mutation coverage (M6 rank 8)

`BackendMutationCoverageTest` targets an already-running, exclusively assigned
backend. It never calls `/beforeeach`, `/startst`, `/after`, or a global reset.
Each method creates a unique mapped Google fixture owner and acknowledges cleanup
through `/test/cleanup-owner` in `@After`. Cleanup also removes a tenant created by
`/login-tenant`. Cleanup failure is a test failure, not ignored output.

From the repository root, use the dedicated optional-Retrofit lane:

```sh
mkdir -p owned-integration-evidence
CORE_PROTOCOL_TESTS=1 JAVA_HOME=/path/to/jdk11 ST_TEST_JAVA=/path/to/core-jdk/bin/java \
node testHelpers/server/runOrdinary.js /path/to/supertokens-root \
  "$PWD/owned-integration-evidence" 18081 19001 \
  node "$PWD/testHelpers/server/runOwnedTests.js" mutation "$PWD"
```

The owned runner forwards only the explicit `CORE_PROTOCOL_TESTS=1` opt-in and
generates instance-scoped URLs. Qualification and capability preflight precede
the portable two-process build. Acceptance requires exactly the six named methods,
6 passed, 0 skipped, 0 failed, per-owner cleanup assertions and final Core/backend
cleanup acknowledgment. Missing capabilities fail this lane. Previous ordinary-suite
assumption skips remain **not passes**. See [entrypoints](RUNNING.md) for receipts.

Six live methods assert:

1. `/update-jwt`: changed snapshot payload, same owner, exactly one payload event.
2. `/update-jwt-with-handle`: unchanged local payload until a real SDK refresh,
   then changed payload, same owner, one payload and one refresh event.
3. Out-of-band `/logout`: candidate ignores that raw response's removal headers;
   next managed rejected-access request performs a real Core refresh, receives 401,
   clears the snapshot and emits exactly one UNAUTHORISED event.
4. Same flow for `/revokeAll` on the unique fixture user.
5. `/session-claims-error`: 403 with expected claim ID, unchanged snapshot,
   zero refresh wires and no lifecycle events.
6. Capability-gated `/login-tenant`: a non-public tenant in the initial snapshot,
   retained tenant/user/handle/owner through a real SDK refresh.

The revocation tests use existing `/throw-401` to trigger access rejection without
sleeping or reconfiguring Core expiry. Revocation and refresh are real Core calls;
the initial 401 trigger is a harness route. These tests do **not** claim stateless
JWT validation discovers server revocation before expiry or database checking.
Refresh wire counts are per-client network-interceptor observations, not shared
backend counters. Public explicit refresh in the handle/tenant tests uses its real
URLConnection path and is checked by outcome/snapshot/events.

R7 account-linking is separate. Node 16.7/Core8's documented `LINK_REVOKED_SESSION`
outcome remains unsupported for same-handle primary-UID change, not a passing test.

Staging proof: compilation only for these six live tests; no backend lifecycle or
mutation calls were run. Actual live execution, capability/skip accounting, all
cleanup acknowledgements and current-AAR testapp Gradle execution remain required.
If fixture creation commits but its response is lost, no receipt is available to
this test; preserve server-owned cleanup evidence rather than using a global reset.
