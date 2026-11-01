# Refresh transport migration (unreleased)

These APIs exist in this source tree, not in the published 0.5.4 artifact. A native
release and consumer dependency update are required before production adoption.

## Existing no-argument interceptor remains functional

`new SuperTokensInterceptor()` refreshes through the caller's existing OkHttp
chain. It preserves that client's TLS, proxy, cookie jar, interceptors and
authenticator behavior. It never substitutes a default client or URLConnection.

Native getters and both HTTP transports share owner-scoped refresh flights,
classified failures and cooldowns. However, the legacy chain can perform its own
redirects, authenticator follow-ups and retries. **The no-argument constructor does
not guarantee one wire refresh request per retry window.** A shared flight led by
a legacy interceptor inherits that limitation, even if other waiters use the
configured constructor. Token-write fencing and response validation still apply.

## Opt into the strict OkHttp refresh transport

Finish configuring a base client, then pass it to the additive constructor:

```java
// Fully configure TLS, certificate pins, cookies, DNS and proxy settings first.
OkHttpClient base = configuredClient;
OkHttpClient authenticated = base.newBuilder()
        .addInterceptor(new SuperTokensInterceptor(base))
        .build();
```

The refresh client is derived with `base.newBuilder()`. It preserves configured
TLS/trust, hostname verification, certificate pinning, cookie jar, DNS, proxy and
connection pool settings. These are captured at interceptor construction;
subsequent builder/client changes are not copied. Objects such as a cookie jar
remain shared references, not deep copies.

For refresh requests specifically, the derived client disables HTTP and HTTPS
redirects, connection retries, authenticators and proxy authenticators. It clears
application and network interceptors and disables caching. A one-shot POST body
also prevents OkHttp's automatic replay of a sent refresh. Ordinary API requests
continue using the application's client configuration.

The strict bound is **at most one HTTP exchange per owned transport execution**,
not exactly-once server processing or one exchange per owner forever. It cannot
strengthen a shared flight led by the legacy transport. The 2026-10-30 approved
policy preserves legacy behavior and keeps this migration optional; modern
no-arg one-shot behavior is an improvement, not a general host-extension fence.

This is an explicit opt-in because excluded extensions may be essential to an
application. `CustomHeaderProvider` can supply refresh headers; it is **not** a
replacement for arbitrary request signing, body transformations, custom transport
interceptors, authenticator challenges or proxy authentication. If refresh needs
those capabilities, do not migrate without a separately supported integration.
The legacy constructor remains available with its weaker wire-count guarantee.

Invalid base-client input is rejected at construction, before shared coordination.
Local `CONFIGURATION` failures are not retained as session cooldowns; callers
joining such a failed flight can reacquire with their own transport.

## Rownd integration

When upgrading to a native artifact containing these changes, construct Rownd's
OkHttp interceptor with its fully configured base client. Migrate all relevant
OkHttp consumers before claiming strict mixed-caller wire bounds. URLConnection
getters still use their existing platform transport; OkHttp configuration is not
silently applied to them. The transport of the caller leading the shared flight
performs refresh, and other callers share the result.

Use `SuperTokens.getSessionSnapshot(context)` for non-refreshing identity and
generation reads, and `resolveAccessToken(context, expectedGeneration,
forceRefresh, failedAccessToken)` for error-preserving resolution. Capture the
generation at operation start. Use generation-checked `installSession` and
`clearSession` instead of direct preference writes.

### Generation-bound remote sign-out

For direct bridge callers, use the additive overload:

```java
long owner = SuperTokens.getSessionSnapshot(context).generation; // At operation start.
SuperTokens.signOut(context, owner);
```

This overload captures the owner, access credential, anti-CSRF credential, configured
sign-out URL (including its configured tenant/base path), auth mode, routing ID,
expiry status and header provider under the native owner lock. Header callbacks run
outside the lock; their output is copied and ownership rechecked before dispatch.
Credential-bearing headers use the captured session, not callback-selected values.
No network operation holds the owner lock. Late responses cannot clear a replacement
owner. Stale ownership throws `SessionRefreshException` with `kind == OWNER_CHANGED`.

The overload does not refresh or reselect credentials. An absent session is a
network-free no-op with the existing sign-out event. A successful response clears
only the captured owner; HTTP errors, `GENERAL_ERROR` and malformed bodies retain
credentials. An expiry-status response returns normally and clears only if the
server explicitly supplies `front-token: remove`; otherwise it throws an HTTP
error rather than claiming that expired credentials were revoked.

This stronger overload currently requires header mode with **no global
`CookieHandler` for the duration of the operation**. URLConnection cannot pin an
ambient cookie manager per request; unsupported cookie selection is rejected before
network with `CONFIGURATION`, rather than risking another owner's cookies. Callback
`Cookie`/`Cookie2` headers are also rejected. The existing `signOut(context)` signature
retains implicit refresh, request retries and legacy cookie behavior; its intercepted
requests now check the generation captured at entry before credential selection and
dispatch. Legacy cookie-mode dispatch does not acquire the new stronger guarantee.

Do not replace Rownd's existing captured-session revocation after local sign-out
with this overload: clearing locally advances the generation, so that older
generation is intentionally rejected. This API is for direct bridge remote sign-out
while its captured native owner is still installed.

## Deadlines and validation boundaries

Refresh network operations have a 10-second deadline and explicit transport
timeouts. Synchronous application callbacks, including `CustomHeaderProvider`,
are outside that deadline and must return promptly. A blocked callback keeps its
owner flight active; waiters have bounded waits but cannot interrupt the callback.
Ownership is rechecked after header callbacks and before sending refresh, and
response writes remain fenced against stale owners/token versions.

Successful header-mode refresh must supply a nonempty access token different from
the rejected credential and valid, unexpired front-token metadata before any
native token write. An omitted refresh-token header may retain an existing
nonempty refresh credential; an explicit empty header is rejected. Malformed
successes retain a shared `INVALID_RESPONSE` without partially replacing tokens.

These local checks are not cryptographic authorization. External cookie jars and
automatic cookie-manager writes remain outside native generation fencing. The
strict transport policy does not establish full cookie-mode ownership guarantees
or bound arbitrary synchronous application callbacks.
