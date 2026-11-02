# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 0.6.0 candidate

This is an unreleased candidate. The behavior below is the intended contract;
the regression fixes and final-artifact qualification are still in progress.

#### Added

- Owner-bound snapshots, conditional `installSession`/`clearSession`, `resolveAccessToken`, request tags, `observeSessions` and explicit `reconfigure()` for coordinated native session ownership.
- Opt-in SDK-owned OkHttp refresh transport, requiring OkHttp 3.14+.

#### Fixed

- Fence delayed responses, refresh and sign-out against replacement sessions; share refresh across callers without serializing ordinary requests.
- Preserve legacy session import, repeated initialization, startup configuration changes, response expiry acceptance, custom authorization and synchronous event behavior. Commit session state atomically before callbacks.
- For untagged responses, derive session kind from response shape. A refresh 401 without removal retains the session and emits `UNAUTHORISED`; strict-only flights retain it without that event.
- Keep snapshot reads available during serialized persistence without exposing uncommitted credentials; distinguish healthy pending replacement from storage failure.
- Preserve cookie serialization across minified consumer builds with packaged consumer rules.

#### Compatibility

- The upgrade is intended to require **no code changes** or stored-session migration for existing 0.5.4 integrations. New APIs are optional and stricter. This is a goal, not a completed compatibility qualification.
- Preserve legacy outbound host matching, Java 7 production bytecode and minSdk 17. See [session ownership](SESSION_OWNERSHIP.md) for cookie, persistence and rollback limits.
- Final-SHA unit, OkHttp matrix, live integration, device and published cookie-upgrade gates remain to be qualified; see the [regression plan](REGRESSION_FIX_PLAN.md#m6--integration-coverage-and-qualification).

#### Behaviour changes

- Ordinary 401 responses cannot establish or rotate session credentials from non-removal headers, unlike 0.5.4. Normal unauthorized and refresh handling still applies.
- When complete header credentials replace an opaque cookie session without a new anti-CSRF token, the old cookie token is not carried over. Unlike 0.5.4, subsequent requests omit it until a new token is received; matching front tokens do not prove session continuity.
- Malformed successful refresh responses fail without clearing the session; 0.5.4 could report success. A foreign Bearer authorization makes an untagged request unmanaged.
- Redirected responses must remain on the sent origin to adopt, clear or retry, except for an approved same-host HTTP-to-HTTPS upgrade using default ports; downgrades and host or port changes remain out of scope.
- Concurrent callers joining a refresh share its result rather than refreshing sequentially. Legacy flights have no post-failure cooldown; strict-only flights retain backpressure.
- Approved 2026-11-02 F2 exception: a legacy-led refresh flight disconnected without a response may make at most one additional SDK dispatch; followers still share its result, and existing limits and owner/configuration/revision fences apply. The server may already have consumed the token, so replay can trigger reuse detection or invalidate the session. Strict resolution and SDK-owned OkHttp refresh do not opt into this retry; caller OkHttp may retry independently. Cookie jars are external stores whose mutations cannot be fenced or undone by SDK ownership checks.
- A failed durable commit on an untagged response throws rather than exposing uncommitted credentials, unlike 0.5.4's in-memory behavior.
- Legacy URLConnection refresh is intended to use platform/caller timeouts; strict resolution retains its 10-second deadline. In-scope URLConnection refresh redirects are limited to 20 hops; followers wait at most 30 seconds.

## [0.5.4] - 2025-03-26

### Changes

- Added new FDI version support: 4.1

## [0.5.3] - 2024-10-29

### Changes

- Added new FDI version as supported

## [0.5.2] - 2024-07-12

### Changes

- Removed redundant calls to `removeToken`

## [0.5.1] - 2024-06-13

### Changes

- Fixed session refresh loop caused by passing an expired access token in the Authorization header.

## [0.5.0] - 2024-06-06

### Changes

- Fixed the session refresh loop in all the request interceptors that occurred when an API returned a 401 response despite a valid session. Interceptors now attempt to refresh the session a maximum of ten times before throwing an error. The retry limit is configurable via the `maxRetryAttemptsForSessionRefresh` option.


## [0.4.2] - 2024-05-28

- re-Adds FDI 2.0 and 3.0 support

## [0.4.1] - 2024-05-28

- Adds FDI 2.0 and 3.0 support

## [0.4.0] - 2024-04-08

### Breaking changes

The `shouldDoInterceptionBasedOnUrl` function now returns true: 
- If `sessionTokenBackendDomain` is a valid subdomain of the URL's domain. This aligns with the behavior of browsers when sending cookies to subdomains.
- Even if the ports of the URL you are querying are different compared to the `apiDomain`'s port ot the `sessionTokenBackendDomain` port (as long as the hostname is the same, or a subdomain of the `sessionTokenBackendDomain`): https://github.com/supertokens/supertokens-website/issues/217


## [0.3.6] - 2024-03-14

- New FDI version support: 1.19
- Update test server to work with new node server versions

## [0.3.5] - 2023-09-13

- Adds 1.18 to the list of supported FDI versions

## [0.3.4] - 2023-07-31

- Updates supported FDI versions to include

## [0.3.3] - 2023-07-10

### Fixes

- Fixed an issue where the Authorization header was getting removed unnecessarily

## [0.3.2] - 2023-06-06

- Refactors session logic to delete access token and refresh token if the front token is removed. This helps with proxies that strip headers with empty values which would result in the access token and refresh token to persist after signout

## [0.3.1] - 2023-01-30

- Adds tests based on changes in the session management logic in the backend SDKs and SuperTokens core

## [0.3.0] - 2023-01-30

### Breaking Changes

- The SDK now only supports FDI version 1.16
- The backend SDK should be updated to a version supporting the header-based sessions!
    - supertokens-node: >= 13.0.0
    - supertokens-python: >= 0.12.0
    - supertokens-golang: >= 0.10.0
- Properties passed when calling SuperTokens.init have been renamed:
    - `cookieDomain` -> `sessionTokenBackendDomain`

### Added

- The SDK now supports managing sessions via headers (using `Authorization` bearer tokens) instead of cookies
- A new property has been added when calling SuperTokens.init: `tokenTransferMethod`. This can be used to configure whether the SDK should use cookies or headers for session management (`header` by default). Refer to https://supertokens.com/docs/thirdpartyemailpassword/common-customizations/sessions/token-transfer-method for more information


## [0.2.2] - 2022-11-29

- Fixes an issue with documentation generation

## [0.2.1] - 2022-11-29

- Added documentation generation

## [0.2.0] - 2022-10-12

### Breaking changes

- `SuperTokens.init` is no longer available. Use `SuperTokens.Builder` instead

## [0.1.3] - 2022-09-20

### Changes

- Adds support for frontend driver interface version `"1.15"`

## [0.1.2] - 2022-06-24

### Features

- Added support for General Errors when calling sign out
