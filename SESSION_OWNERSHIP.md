# Session ownership (0.6.0 candidate)

Existing 0.5.4 integrations require no code or stored-session migration changes.
The APIs below are optional additions, unavailable in published 0.5.4. This is the
behavior contract, not certification of a release artifact. Consult the
[compatibility wiki](docs/compatibility-wiki/index.md) for scoped evidence and pending
gates. Do not replace a published artifact with a local candidate under the same version.

## Atomic native state

```java
SessionSnapshot before = SuperTokens.getSessionSnapshot(context); // no network
SessionCommitResult result = SuperTokens.installSession(context, before.getOwner(),
        new SessionTokens(access, refresh, front, antiCsrf), operationCorrelation);
if (result.isSuccess()) {
    SessionSnapshot committed = result.getSnapshot();
}
```

Install validates a complete header tuple before mutation and synchronously commits all
credentials, identity, configuration, revisions and correlation in one preferences editor.
Run durable operations off the UI thread. Every explicit install changes owner, even for
identical tokens. Same-session refresh/promotion preserves owner. Snapshots defensively
copy JSON; credential export is deliberate and `toString()` is redacted.

Durable mutations serialize before validation and retain write serialization through
persistence, recovery and publication. Storage I/O runs outside the state lock;
snapshot readers do not acquire the write lock. While replacement, clear or a session-key
raw edit is pending, reads expose a tokenless `UNAVAILABLE` snapshot, not old or
uncommitted credentials. During same-owner promotion, reads retain the last committed
snapshot until the new tuple is acknowledged. Do not treat transient unavailability as
a completed logout. Legacy callbacks and observer delivery run after both locks release.

Legacy `attemptRefreshingSession` returns false and `signOut` is a network-free,
event-free no-op when they observe a marked pending replacement. This is not a blanket
rule for `UNAVAILABLE`: terminal partial raw state and genuine `RECOVERY_REQUIRED`
remain errors. A producing operation's persistence failure is still reported; explicit
resolution and tagged requests retain fail-closed behavior during pending replacement.

`SessionOwner` is opaque process-local authority, never a persisted authorization token.
`getAdoptionId()` is a separate SDK-generated durable UUID. The optional
`getAdoptionCorrelation()` is caller-supplied (nonblank, at most 512 UTF-8 bytes), committed
with the tuple. Refresh retains both. Replacement, clear and configuration changes generate
a new adoption ID; uncorrelated transitions clear prior correlation.

For a two-store transaction, persist the prior adoption ID and prospective correlation
before native install. Changed ID plus matching correlation identifies your interrupted
install; changed ID plus different/null correlation identifies supersession. Unchanged ID
without your correlation means install is not established. Unchanged ID with your correlation,
missing IDs or corrupt metadata is ambiguous and must fail closed. These receipts do not
provide exactly-once server execution or a caller's business journal.

`clearSession(context, expectedOwner)` is conditional, local and durable; it invalidates
old authority before persistence. Legacy `signOut(context)` remains a remote operation.
Storage failure returns `PERSISTENCE_FAILED`, invalidates visible credentials and attempts
a recovery tombstone. Failed commit may already have changed preferences RAM; failed cleanup
can leave older disk data. No durable logout or successful adoption is promised after failure.
Missing schema with remaining versioned metadata fails closed, rather than becoming a legacy import.

## Requests and refresh

```java
SessionRequestTag tag = SuperTokens.captureRequestTag(context);
Request request = new Request.Builder().url(url)
        .tag(SessionRequestTag.class, tag).build();
```

Default ownership begins at interceptor entry. A tag captures it earlier, at logical request
creation. URLConnection has `newRequest(URL, PreConnectCallback, SessionRequestTag)`.
Both transports pin owner/configuration through retries. A tagged stale response throws
`StaleSessionException`; an untagged stale response can return its body/status but never
mutates credentials or retries using the new owner. Callers must fence body/UI publication.
For a tagged full authentication response that establishes a new owner, the same request's
`tag.getAcceptedSnapshot()` is its adoption receipt, not an unrelated-owner substitution.

A supplied tag selects its captured credentials on the first wire; reactive retries may
advance within that owner. A wrapper queue may capture an implicit tag at admission to use
fresh same-owner credentials, provided it pins the originating owner before queueing and
rejects admission if the newly captured tag has a different owner. Explicit supplied tags
retain their original capture. Waiting in a queue never authorizes adopting a replacement owner.

An explicit bearer different from the captured session is unmanaged: legacy requests retain
it but cannot write session state or trigger managed refresh. Tagged requests reject it
before dispatch. An explicitly called refresh URL is raw transport and does not adopt headers.

Outbound interception retains the 0.5.4 host/backend-domain matching rule; it does not
add scheme, port or path restrictions to direct requests. Redirected responses must
match the **sent request's** scheme, host and effective port before credential adoption,
removal or managed retry. Same-origin path redirects are allowed. Out-of-scope
responses return unmanaged for untagged requests; tagged requests throw `IOException`.
In-scope redirects remain supported. URLConnection's recorded request Authorization is
not evidence of the final redirected wire's headers. Legacy OkHttp refresh also checks
final response scope; ordinary host redirect policy remains in effect.

Remote sign-out reactively refreshes server-rejected access credentials, bounded by
`maxRetryAttemptsForSessionRefresh`. Refresh and retry remain pinned to the original
owner/configuration and failed wire's refresh revision. Legacy sign-out stops on stale
ownership without dispatching/retrying new work or mutating the replacement session;
already received HTTP/body errors are preserved. Response headers are processed before
body parsing, so explicit removal may already be committed when parsing fails. Unknown
string statuses succeed except `GENERAL_ERROR`; missing status, empty and HTML bodies
retain their legacy parse errors.
Cookie sign-out forces reactive resolution when there is no access-token string;
the captured refresh revision lets it reuse an intervening refresh or join its flight.

One refresh flight is shared across clients, getters and URLConnection for each owner/lineage.
Ordinary requests remain concurrent; only short state/persistence commits are serialized.
Ordinary promotions are suppressed while that lineage refreshes. Refresh success checks
refresh lineage independently of access revision and adopts the complete response tuple;
no JWT timestamp ordering or arbitrary merge is inferred. Removal requires exact relevant
credential revisions, including non-401 explicit removal. A delayed N clear cannot erase N+1.

`resolveAccessToken(context, owner, forceRefresh, failedAccessToken)` preserves errors and
returns the exact accepted snapshot, subject to strict validation. Legacy
`attemptRefreshingSession` returns false on stale ownership. The secure payload getter
maps stale ownership to `IOException("Could not refresh session")`, never an expired
old-owner payload, replacement-owner payload or `StaleSessionException`.
These mappings apply to SDK-classified stale outcomes, not exceptions thrown by application
callbacks merely because they have the same exception type. Callback provenance and the
legacy caller's oracle-characterized wrapping/suppression remain intact. A secure getter
that starts from a tokenless unavailable snapshot reports `IllegalStateException("No session exists")`.
Interrupted followers do not release a live flight. Header callback reentry returns an error.
Follower waits are bounded to 30 seconds; at most eight unfinished refresh transports are admitted
across retired owners. Replacement never waits for an old socket, but exhausted transport
capacity yields an explicit failure. Backpressure/cooldown is not a server refresh-token grace
period: Core parent reuse depends on whether a child has been used, not elapsed time.

## Transport policy

`new SuperTokensInterceptor()` retains the caller's OkHttp chain for refresh, including its
TLS, proxy, cookie jar, downstream interceptors and follow-up policy. It does **not** promise
one wire request. Legacy 401 bodies are fully buffered before chain reuse, without a
1 MiB SDK cap; applications should account for this memory cost. The SDK does not cancel
the caller's call or impose its owned-transport deadline on that chain: caller timeouts apply.

For at most one HTTP exchange per owned refresh transport execution, configure a base client first:

The opt-in constructor requires **OkHttp 3.14+**. This requirement does not apply to
the legacy no-argument constructor. Use a runtime supported on the target Android
version; JVM linkage is not Android certification. Production classes preserve the
published Java 7 baseline and minSdk remains 17. The AAR does not bundle OkHttp.

The published legacy API's direct references link against OkHttp 3.3.0. Seven historical
JVM runtime lanes (3.3.0, 3.10.0, 3.11.0, 3.12.13, 3.13.0, 3.14.0 and 4.1.0) also exercised
the recorded candidate's header login/refresh/sign-out flow. This is scoped runtime
evidence, not just static linkage, nor API17/TLS/cookie or every-toolchain certification.
See [runtime evidence](app/src/test/compatibility/RUNTIME_COMPATIBILITY.md).

```java
OkHttpClient managed = base.newBuilder()
        .addInterceptor(new SuperTokensInterceptor(base)).build();
```

The derived refresh client retains TLS, certificate pins, DNS, proxy, pool and cookie jar;
disables redirects, connection retries, authenticators, cache and application/network
interceptors; uses a one-shot body and 10-second call/connect/read/write timeouts. Extension
removal is explicit opt-in, not equivalent to arbitrary request signing/authenticator logic.
Client configuration is captured at construction; objects such as the cookie jar remain
shared references, not deep copies. Ordinary requests still use the application's client.
`CustomHeaderProvider` cannot replace arbitrary signing, body transformations or proxy
authentication. Invalid base clients fail at construction; configuration failures are
not retained as session cooldowns, allowing a follower to retry with its own transport.
The leading caller's transport wins the shared flight. URLConnection retains platform transport,
with bounded connect/read waits, disabled redirects and streaming refresh body. Synchronous
host callbacks cannot be forcibly stopped by the network timeout. The refresh budget starts
before callback preparation: a callback returning after ten seconds cannot dispatch a late
refresh. A callback that never returns can retain its caller thread and one admitted slot;
replacement proceeds until the eight-slot bound is reached. Callback exceptions/errors retire
their slot. The SDK does not promise a bounded leading-call return for uncooperative host code.

**Approved 2026-10-30 — preserve legacy; strict opt-in.** F2 bounds shared SDK
flights, not caller OkHttp's internal HTTP exchanges. Historical no-arg refresh
may replay after a lost response; modern no-arg refresh's one-shot body is an
intentional improvement, not universal control over host extensions/follow-ups.
No migration or historical strict transport is required. Owned refresh's bound
is per execution, not exactly-once server processing or one exchange per owner
forever. An owned waiter cannot strengthen a flight led by a legacy caller.
The [decision and test evidence](docs/compatibility-wiki/recovery-2026-10-30.md#approved-transport-contract-correction)
explicitly reconcile the original absolute D5 parity wording with this improvement.

Cookie requests retain their captured refresh revision when handling a delayed 401. A refresh
completed since that request was sent satisfies the retry, avoiding redundant cookie refreshes.
Retry exhaustion preserves the historical diagnostic and exception type: `IOException` for
OkHttp, `IllegalAccessException` for URLConnection.

## Configuration and observation

Repeated legacy initialization/`Builder.build()` is a no-op, even with different callback
objects or configuration; it does not replace active settings. Differences may produce a
best-effort warning. Use `Builder.reconfigure()` for an intentional runtime durable clear
and new owner. Runtime configuration identity fences in-flight work; A→B→A cannot restore A.

Across launches, changed configuration (including backend domain or cookie/header transfer
mode) does not clear the session. Persisted configuration identity is metadata, not a startup
logout trigger. Cookie/header migration preserves the existing session. The public test-only
transfer helper is not a substitute for explicit runtime reconfiguration.
Paths, query and fragment in `apiDomain` are ignored; put URL prefixes in `apiBasePath`.
Negative refresh retry limits normalize to zero.

```java
SessionSubscription subscription = SuperTokens.observeSessions(context, snapshot -> project(snapshot));
project(subscription.getInitialSnapshot());
subscription.activate();
```

Registration atomically captures an initial snapshot; callbacks wait for activation. Delivery
is monotonic but coalesces pending snapshots to the newest revision (one pending per subscription).
Always reject an older revision when applying the initial snapshot or a receipt. Callbacks run
outside both mutation locks, may reenter, and `RuntimeException` failures are isolated.
Offers are deferred until the outer writer releases; a terminal partial raw state can
be observed without an extra durable record write or deletion of its partial tuple.
`close()` prevents new callbacks;
cross-thread close waits for a running callback, while reentrant close does not wait on itself.
Legacy `EventHandler` events are separate: synchronous on the transition-producing thread,
outside state/write locks, with no stale-owner filter or backlog cap. The complete state is
committed before callbacks. Handler failure does not roll it back (unlike the published
SDK's possible partial writes). Exception wrapping/suppression remains caller-specific,
as characterized by the 0.5.4 oracle; there is no universal unwrapped-exception guarantee.
A shared refresh emits once on its leader. Followers may return the committed result while
the leader's handler is blocked and do not inherit that handler's later failure. This avoids
reentrant deadlock; it is not a global ordering guarantee across independent operations.
Legacy events are observations, not current-auth authority.
Consumers must use owner-stamped projections and current snapshot reads; two independent locks
do not make a native check and a host reducer atomic.

## Scope and compatibility

Strong ownership/durability guarantees apply to SDK-managed header credentials in one process.
Cookie jars and platform cookie mutation retain legacy behavior and are not fenced by header
ownership. SDK low-level session-key setters participate in write serialization and use
acknowledged commits, including metadata invalidation in the same edit. They preserve
sequential legacy construction but do not make several setters one atomic import, nor
provide the explicit install API's durable success receipt. External preference writers
and other processes do not participate and remain outside the ownership/durability contract.
Supported JWT version fields are decoded for structural coherence, not cryptographic verification.
Explicit `installSession` rejects invalid/incomplete or expired header tuples. Legacy import
instead recovers readable front-token identity, including missing refresh tokens, opaque
access tokens and missing update markers (a marker is synthesized). Readable credentials
with contradictory old schema-1 metadata and no fingerprint receive one-time recovery;
this cannot distinguish corruption from older-SDK edits. Unreadable legacy front tokens
appear absent rather than permanently blocking login. Recovery/unavailable state permits
ordinary credential-free login requests; a valid authentication response can replace it.
Front-token identity is authoritative for compatible legacy imports; full header-tuple
validation is not retroactively imposed on them. Metadata fingerprints detect tuple
disagreement, not authenticity or proof that an older SDK made the edit. Neither parsed
JWT fields nor local fingerprints replace server-side authentication.

Legacy response acceptance rejects expiry based on neither device time nor server `Date`.
Device time drives bounded proactive refresh; explicit installation/resolution remains strict.
Malformed refresh success retains the session. Same known session handle and tenant may
refresh to a different user ID while retaining ownership; a different handle/tenant is rejected.
Cookie sessions may expose access tokens without refresh headers; managed Authorization
requires both header credentials. Non-Bearer custom Authorization remains managed without
being overwritten; a foreign Bearer stays unmanaged.

Supported pre-init getters read preferences without initialization, migration writes or
network refresh. Empty/coherent startup avoids an eager metadata-only write; legacy import
can require a one-time commit. This is not a promise that cold preference reads or durable
mutations are safe on the UI thread.

Fencing cannot cancel server work, undo an old request or recover an opaque token lost in transit.
Device restart, server protocol and JVM evidence qualify different layers. A force-stop after a
commit is not proof of filesystem power-loss atomicity. Downgrading an app that calls new methods
to old upstream alone causes linkage failure; coordinate rollback with its caller and recovery data.

The packaged consumer rules preserve the cookie serialization wrapper and its hooks.
An API34 minified two-mapping consumer qualified that fix for its recorded artifact;
it did not certify API17, arbitrary old obfuscated names or the latest candidate.
See [R8 evidence](testHelpers/upgradeapp/R8_SMOKE.md). The separately observed cookie-store
empty-URI-path edge case remains open; that qualification used an explicit `/` URI.
API17 execution, Linux CI and published-upgrade/R8 reruns against the latest release
artifact remain pending. Earlier successful runs must not be transferred to a new AAR.
