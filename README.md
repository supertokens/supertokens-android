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

**Cookie limitation:** automatic OkHttp CookieJar / platform CookieManager handling
can save stale `Set-Cookie` headers before SDK response processing. Generation
fencing cannot undo those writes. Header mode (the default) is fully fenced for SDK
session state. The HttpURLConnection manual cookie-path workaround is ownership
checked and serialized with session replacement; custom cookie handlers should
not block waiting for session-changing work on another thread.
