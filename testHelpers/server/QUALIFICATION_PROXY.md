# Completed-response qualification proxy

Current integrated execution: use the owned `matrix` mode in
[RUNNING.md](../testapp/RUNNING.md). It allocates backend-port + 1, verifies the
proxy child/listener identity, runs exactly the two Java methods, reconciles only
that proxy's owners, requires zero remaining jobs/owners/unresolved creations,
then stops its own child. Missing mapped-owner capability is an explicit exclusion,
not a proxy pass. The manual examples below document the original standalone
contract; do not point them at a shared backend. Earlier staging counts are
historical, not results for a new SDK/Core pair.

This separate loopback listener implements the HTTP control contract consumed by
`CoreProxyProtocolTest`. It does not initialize SuperTokens, start/stop Core,
change the normal Express middleware stack, or reset any backend database. Main
backend `/test/capabilities` remains `qualificationProxy:false`.

## Configuration and capability

Start only with the live/backend lane assigned and a verified, owned backend:

```sh
cd testHelpers/server
TEST_MODE=testing CORE_PROTOCOL_TESTS=1 \
QUALIFICATION_PROXY_TARGET=http://127.0.0.1:8080 \
QUALIFICATION_PROXY_PORT=8081 node runQualificationProxy.js
```

The target must be an explicit loopback HTTP origin; listener binds 127.0.0.1.
The CLI rejects its own target port. No redirects are followed. Normal backend
routes are unaffected. Use **one serial qualification client** per proxy process;
the existing Java control contract has no per-run bearer ID. A second arm cannot
replace live gates, but this is not authenticated multiclient isolation.

Proxy `/test/capabilities` forwards the actual backend report, preserving its
SDK/CDI/account-linking fields and qualification identities. It advertises
`qualificationProxy:true` only when explicitly enabled/configured and the report
contains `enabled:true`, `compatible:true`, `mappedGoogleSession:true`. Arm fetches
and rechecks that report. Missing/incompatible/older SDK fixture support gives
false plus `backend_fixture_unavailable`, and arm returns 503. Unreachable or
malformed capability responses return 502. These are readiness failures, not
evidence that Core itself lacks a feature. Disabled listener returns false/501.
No account-linking qualification rule or historical exclusion has changed.

## Exact Java contract

- `POST /__qualification/arm {"paths":["/auth/plugin/rownd/user","/auth/session/refresh"]}`
  returns 200 and arms one response per listed path. Only those two paths may be
  armed, at most once each. Invalid lists return 400; occupied gates return 409.
- `GET /__qualification/state` returns `gates[path]` with `claimed`,
  `responseReady`, `released`, `delivered`, `timedOut`, `status`, `hasAccess`,
  `hasFront`, `hasRefresh`, `accessChangedFromRequest`. Readiness means the actual
  upstream response has **ended**, not merely sent headers. Presence/change flags
  come from actual headers; no token is synthesized, decoded or repaired.
- `POST /__qualification/release {"path":"..."}` releases the buffered response
  unchanged. It returns 409 if no completed live response is held. Release order
  is controlled entirely by the caller.
- `POST /__qualification/release-all {}` disarms every gate and releases completed
  responses; unfinished upstream responses will pass through upon completion.
  It cancels any pending arm-capability probe so a late probe cannot install new
  gates after cleanup. It neither cancels upstream session operations nor cleans
  fixture users.

### Explicit profile-route mismatch

The current backend has no `/auth/plugin/rownd/user`. The proxy maps **only that
exact path** to the existing `/test/protected` route, preserving the query string.
Both use the real SDK `verifySession()` path needed for child-access promotion.
No Rownd integration or profile mutation is claimed. The Java test only checks
status and token promotion headers there; its subsequent native access/refresh
usability assertions still hit the real backend. No Java assertion was weakened
or edited. If the pair does not return parent-lineage/promotion headers, the Java
test must fail: the proxy never invents them.

All other allowed fixture paths retain method/path/query, original body bytes,
authorization, cookie, anti-CSRF, rid, content type and session headers. Standard
hop-by-hop/Connection-nominated headers are removed; upstream Host is the target.
Response status, body, end-to-end headers, multiple Set-Cookie values, encoding
and redirect Location are preserved. Only capability JSON is augmented; its length
and content type are recomputed and stale content validators removed.

## Extensions and resource ownership

These extensions have Node coverage but are **not called by the current Java
test**:

- `POST /__qualification/drop {"path":"..."}` destroys the downstream socket
  after a complete response is held, without sending status/headers/body. The
  upstream operation already happened; this is response loss, not cancellation
  before dispatch. No retry is performed by the proxy.
- `POST /__qualification/reset {}` cancels this listener's jobs/upstreams, clears
  gates/counters/history and advances both the gate generation and the separate
  accounting epoch. Late completions cannot enter the new epoch. It preserves
  the fixture ownership ledger and unresolved
  creation marker; it is never a backend reset.
- State includes per-path `requests`, `responses`, `ready`, `delivered`, `dropped`,
  `aborted`, `failed`, `timedOut` counts and the last 64 metadata-only events.
  Counts are since reset, not since arm. Arm advances only `generation`; a request
  begun before arm still records its response and terminal outcome in the same
  `accountingEpoch`. State and event records expose both values; event generation
  is the gate configuration at emission, not a claim that the request used a gate.
  `delivered` means Node's downstream
  response `finish`, **not proof the SDK committed it**. Control/capability probes
  internal to arm are not counted as application requests.
- `GET /__qualification/owners` returns only known fixture IDs/handles, never
  tokens. Recovery can issue real `POST /test/cleanup-owner` for those IDs. Cleanup
  and revocation forwarding reject IDs/handles not created through this proxy.
  Successful real cleanup removes only the endpoint-authoritative entry:
  `/test/cleanup-owner` uses `fixtureId` and its `cleaned` acknowledgment;
  `/test/revoke-session` uses `handle` and its `ownerCleaned` acknowledgment.
  Conflicting extraneous fields never select an additional ledger entry.
  32 bounded tombstones support
  retries. Backend ownership remains authoritative; the proxy cannot clean an
  unrelated user's session by accepting an arbitrary handle.

Defaults: two gates, 16 active forwarded requests, eight control readers, 32
active/creating fixtures, 1 MiB each request/response, 4 KiB controls, eight-second
gate/upstream/request deadline, 32 total sockets with an absolute 16-second socket
lifetime. Body overflow fails, never truncates a forwarded success. Client abort,
drop, timeout, reset and process shutdown free owned buffers/timers/transports.
SIGINT/SIGTERM closes only this listener's sockets. Unknown normal routes including
`/beforeeach`, `/startst`, `/stop`, `/after` are not forwarded.

A lost fixture-creation response can leave a real backend fixture without a
known ID. Such an outcome increments `unresolvedCreations`, blocks new creation,
and remains marked through reset. Preserve the original backend process and
request identity; reconcile with its owner. Restarting the proxy loses its
in-memory ownership ledger and is not cleanup. No global reset or guessed ID is
an acceptable recovery. The Java test stores its handle late, so a failure before
that assignment may require cleanup through `/__qualification/owners`.

## Verification and future live execution

Local mechanics only (Node 16, ephemeral loopback HTTP fixture servers; no SDK/Core
initialization and no active backend contact):

```sh
node qualificationProxy.test.js
# or npm run test:qualification-proxy
```

Tests cover both release orders and exact gate metadata/counts; byte/header/query
forwarding; completed-body readiness; drop; client abort/late release; reset and
late upstream completion; direct-upstream isolation; disabled/old SDK capability
reports; scoped cleanup; upstream reset; unresolved creation; request/response/
fixture limits; capacity/deadline; shutdown. Fixture headers are explicitly
synthetic and test forwarding only, not Core semantics.

Staged verification: **24/24 loopback scenarios passed** on Node 16.14.0; includes
release-all fencing a delayed arm probe and malformed owner-response handling.
Review regressions cover two-owner conflicting fields for both cleanup endpoints,
independent upstream survivor liveness, survivor listing/ownership and retries;
ungated delivery/failure/abort across arm; and reset/late-completion accounting.
This is not a live Core qualification.

**Not executed during staging.** After applying/reviewing the patch and receiving
the backend/Gradle lane, verify real backend readiness and start the proxy above.
Then from the repository root (Temurin 11 and Android SDK environment):

```sh
./gradlew :app:testDebugUnitTest --offline \
  -PcoreProtocolUrl=http://127.0.0.1:8081 \
  --tests com.supertokens.session.CoreProxyProtocolTest
```

Expected discovery is two Java methods. Preserve Gradle XML plus proxy state and
backend identity privately, and verify both fixture cleanups. The current Java
suite asserts rather than skips absent support. Do not label unavailable Core,
missing fixture APIs, unknown capabilities, or missing parent/promotion fields as
a pass or measured backend exclusion.

`CoreLostRefreshResponseTest` currently discards a raw refresh response in-process;
it does not use the new drop endpoint. Drop-based Core grace, retries after a lost
response, multiclient isolation, account-linking profile semantics, device proxy
connectivity and historical live pairs remain separately unverified/unsupported.
Run only explicitly selected tests; no blanket Core* success or skip claim.
