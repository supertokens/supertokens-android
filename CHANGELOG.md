# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
