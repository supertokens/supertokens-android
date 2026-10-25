# Session ownership (unreleased candidate)

These interfaces are not in published 0.5.4. Consume a checksummed candidate AAR with
AppCompat 1.1.0 and your OkHttp runtime (tested against 4.1.0). Do not replace an existing
published version with a local candidate.

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

One refresh flight is shared across clients, getters and URLConnection for each owner/lineage.
Ordinary requests remain concurrent; only short state/persistence commits are serialized.
Ordinary promotions are suppressed while that lineage refreshes. Refresh success checks
refresh lineage independently of access revision and adopts the complete response tuple;
no JWT timestamp ordering or arbitrary merge is inferred. Removal requires exact relevant
credential revisions, including non-401 explicit removal. A delayed N clear cannot erase N+1.

`resolveAccessToken(context, owner, forceRefresh, failedAccessToken)` preserves errors and
returns the exact accepted snapshot. Legacy getters keep their error-collapse behavior.
Interrupted followers do not release a live flight. Header callback reentry returns an error.
Follower waits are bounded to 30 seconds; at most eight unfinished refresh transports are admitted
across retired owners. Replacement never waits for an old socket, but exhausted transport
capacity yields an explicit failure. Backpressure/cooldown is not a server refresh-token grace
period: Core parent reuse depends on whether a child has been used, not elapsed time.

## Transport policy

`new SuperTokensInterceptor()` retains the caller's OkHttp chain for refresh, including its
TLS, proxy, cookie jar, downstream interceptors and follow-up policy. It does **not** promise
one wire request. Its expired response buffer is limited to 1 MiB; larger bodies fail explicitly.

For one-wire refresh policy, configure a base client first:

Use an OkHttp runtime supported on the target Android version. JVM ABI tests use4.1.0;
the API34 instrumentation fixture uses4.12.0 because4.1.0 fails platform initialization
on that emulator. The library AAR does not bundle an OkHttp runtime.

```java
OkHttpClient managed = base.newBuilder()
        .addInterceptor(new SuperTokensInterceptor(base)).build();
```

The derived refresh client retains TLS, certificate pins, DNS, proxy, pool and cookie jar;
disables redirects, connection retries, authenticators, cache and application/network
interceptors; uses a one-shot body and 10-second call/connect/read/write timeouts. Extension
removal is explicit opt-in, not equivalent to arbitrary request signing/authenticator logic.
The leading caller's transport wins the shared flight. URLConnection retains platform transport,
with bounded connect/read waits, disabled redirects and streaming refresh body. Synchronous
host callbacks cannot be forcibly stopped by the network timeout. The refresh budget starts
before callback preparation: a callback returning after ten seconds cannot dispatch a late
refresh. A callback that never returns can retain its caller thread and one admitted slot;
replacement proceeds until the eight-slot bound is reached. Callback exceptions/errors retire
their slot. The SDK does not promise a bounded leading-call return for uncooperative host code.

Cookie requests retain their captured refresh revision when handling a delayed 401. A refresh
completed since that request was sent satisfies the retry, avoiding redundant cookie refreshes.
Retry exhaustion preserves the historical diagnostic and exception type: `IOException` for
OkHttp, `IllegalAccessException` for URLConnection.

## Configuration and observation

Same normalized configuration with identical custom callback objects is idempotent. Different
`Builder.build()` calls reject rather than silently changing configuration. Use
`Builder.reconfigure()` for an intentional durable clear and new owner. Origin scheme/port,
domain path, auth path, backend scope, transfer mode and retry/expiry settings contribute to
configuration identity. Callback changes also fence old operations. A→B→A cannot restore A.

This intentionally restricts historical behavior: requests to another scheme or port do not
receive managed credentials. Changing transfer mode, including through the test-only
`setTokenTransferMethod`, durably clears the session and retires its owner. Cookie-to-header
migration must establish a new complete session; changing the mode does not preserve it.

```java
SessionSubscription subscription = SuperTokens.observeSessions(context, snapshot -> project(snapshot));
project(subscription.getInitialSnapshot());
subscription.activate();
```

Registration atomically captures an initial snapshot; callbacks wait for activation. Delivery
is monotonic but coalesces pending snapshots to the newest revision (one pending per subscription).
Always reject an older revision when applying the initial snapshot or a receipt. Callbacks run
outside the commit lock, may reenter, and exceptions are isolated. `close()` prevents new callbacks;
cross-thread close waits for a running callback, while reentrant close does not wait on itself.
Legacy events are observations, not current-auth authority, and have a bounded 64-event backlog.
Consumers must use owner-stamped projections and current snapshot reads; two independent locks
do not make a native check and a host reducer atomic.

## Scope and compatibility

Strong ownership/durability guarantees apply to SDK-managed header credentials in one process.
Cookie jars and platform cookie mutation retain legacy behavior and are not fenced by header
ownership. Raw preference writes, deprecated low-level setters and multiple processes are outside
the atomic contract. Legacy setters remain binary-compatible but are unsafe for multi-call import.
Supported JWT version fields are decoded for structural coherence, not cryptographic verification.
New imports reject expired credentials; valid expired persisted tuples remain refreshable.

Fencing cannot cancel server work, undo an old request or recover an opaque token lost in transit.
Device restart, server protocol and JVM evidence qualify different layers. A force-stop after a
commit is not proof of filesystem power-loss atomicity. Downgrading an app that calls new methods
to old upstream alone causes linkage failure; coordinate rollback with its caller and recovery data.
