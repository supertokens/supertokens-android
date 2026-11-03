# SuperTokens Android SDK

<a href="https://supertokens.com/discord">
<img src="https://img.shields.io/discord/603466164219281420.svg?logo=discord"
    alt="chat on Discord"></a>

## About

This is an Android SDK that is responsible for maintaining a SuperTokens session for an Android app.

Learn more at https://supertokens.com

## Documentation

To see documentation, please click [here](https://supertokens.com/docs/guides).

## Contributing

Please refer to the [CONTRIBUTING.md](https://github.com/supertokens/supertokens-android/blob/master/CONTRIBUTING.md) file in this repo.

## Contact us

For any queries, or support requests, please email us at team@supertokens.com, or join our [Discord](supertokens.com/discord) server.

## Authors

Created with :heart: by the folks at SuperTokens.com.

## Session ownership

`SuperTokens.getSessionSnapshot(context)` returns an immutable, coherent read of
`generation`, `accessToken`, `refreshToken`, `frontToken`, `antiCsrfToken` and
`lastAccessTokenUpdate`. These contain credentials; do not log snapshots.

Use `installSession(context, expectedGeneration, new SuperTokens.SessionTokens(access,
refresh, front, antiCsrf))` to replace a session, or
`clearSession(context, expectedGeneration)` to clear it. Both return `false` without
side effects when the generation no longer matches. Successful calls increment the
generation, including installing the same user's session again. Generations start
at zero per process; never persist them or use them across processes.

`addSessionListener` / `removeSessionListener` subscribe to accepted changes.
Listeners receive the resulting snapshot and normally run synchronously **outside**
session and refresh locks, so they may install/clear or make requests. If a legacy
request callback changes the session while a refresh lock is held, notifications are
deferred until that lock is released. Callbacks may run on
different calling threads; use the snapshot's generation when performing a later
conditional write. Superseded notifications are dropped when checked before dispatch.
A callback already selected or running when another thread replaces the session
cannot be recalled; handlers should re-read the snapshot before acting on it.

`addSessionEventListener` / `removeSessionEventListener` subscribe to lifecycle
events. Each `SessionEvent` contains the event `type`, its immutable committed
`snapshot`, and that snapshot's `generation`. This is separate from a
`SessionListener`: the latter observes state changes, while lifecycle events report
the same event types as the legacy `EventHandler`. Both callback APIs run outside
the SDK's session and refresh locks and may reenter the SDK.

For `SIGN_OUT` or `UNAUTHORISED` caused by a clear, the event carries the **post-clear**
generation. A consumer may pass that generation to `clearSession` or `installSession`:
the operation succeeds only if no later replacement or clear has occurred. A token
rotation within the same session retains the generation, so it does not invalidate
that ownership. Re-read the current snapshot when the latest rotated token values
matter; the event snapshot is the state at the transition, not a live view.

The SDK rechecks ownership before each delivery, but does not serialize callbacks
with session replacement. A legacy event or generation-bearing event already
admitted for delivery can begin after a replacement completes. Legacy `EventHandler`
has no generation, so it cannot safely make unconditional session changes on that
basis. Use `SessionEvent.generation` for conditional SDK writes; unconditional UI or
other external state writes are not fenced by this API and need their own ownership
check and coordination. Pending deliveries are also dropped if their exact snapshot
was superseded by a same-generation rotation; the listener is not an audit log.
The deprecated public `Utils.fireSessionUpdateEventsIfNecessary` is a legacy-only
escape hatch: it invokes `EventHandler` directly and cannot notify generation-bearing
listeners because its arguments contain no accepted transition snapshot.

Each intercepted request and retry captures one snapshot for its headers and
response ownership. Responses processed after replacement/clear cannot write tokens,
manually save cookies, or emit session events. A callback already selected for an
earlier accepted response may overlap a later replacement. Refreshes still use the existing
transport-specific refresh locks. Token rotations within an owned session keep the
generation; immutable snapshot identity detects an intervening rotation when
deduplicating refreshes (not millisecond timestamps). SDK-observed creation and
removal also advance the generation. When importing/replacing a session, always use
`installSession`; a response carrying rotated tokens is not a replacement API.

The existing SharedPreferences keys are unchanged. Each accepted change uses one
editor and `apply()`: memory is published atomically without synchronous main-thread
disk I/O. As before, immediate process death can lose a pending disk write. This is
not a cross-process transaction or crash-recovery journal. Direct preference writes
are unsupported; deprecated token writers still work but each partial write advances
the generation. Migrate multi-token writes to `installSession` for atomicity.
If `apply()` throws, the SDK re-reads the preferences with a new generation before
propagating the error; a failing preferences implementation that also prevents reads
cannot be recovered in-process.

**Cookie limitation:** automatic OkHttp CookieJar / platform CookieManager handling
can save stale `Set-Cookie` headers before SDK response processing. Generation
fencing cannot undo those writes. Header mode (the default) is fully fenced for SDK
session state. The HttpURLConnection manual cookie-path workaround is ownership
checked and serialized with session replacement; custom cookie handlers should
not block waiting for session-changing work on another thread.

For compatibility, a refresh response without a `front-token` can emit a legacy
`UNAUTHORISED` or `SIGN_OUT` event without clearing the session. `SessionEvent`
carries the actual snapshot and generation; its event type alone does not establish
that credentials were removed.
