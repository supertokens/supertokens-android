# Ordinary 401 session headers: compatibility boundary

The checksum-pinned published 0.5.4 oracle saves ordinary response headers before
checking status. In `LegacyShapeCrossProductTest`, non-removal 401 responses can
establish a session from absence or replace/promote credentials in an existing
session. The candidate does not adopt non-removal candidates on non-2xx responses in
`SessionState.applyOrdinaryResponse`. This is distinct from refresh endpoint 401
handling.

Exact legacy retry behavior conflicts with request ownership. In the absent +
front-token 401 case, 0.5.4 establishes a session and retries the same original
request against that newly established session. The request was captured under
the absent owner; a replacement invalidates that owner. Retargeting the original
request to the new owner would defeat the F1 rule against replaying A's work as
B. The same issue arises for a 401 that replaces an existing identity. A
same-owner promotion still requires the captured access and refresh revisions,
configuration, in-flight refresh and redirect checks; simply removing the
status checks and capturing current state is not a safe fix.

The approved contract intentionally ignores non-removal credentials on ordinary
401 responses rather than adopting them as 0.5.4 does. Normal unauthorized and
refresh handling still applies and can change or clear the session; this is not
a promise that every 401 preserves it. Strict response validation remains in
place, as do owner, configuration, revision and in-flight refresh fences. Removal
responses are not covered by this exception.

`LegacyShapeCrossProductTest` independently checks the oracle's characterized
outcome and compares the candidate with the same 401 without credential headers.
This tests the safe contract, not parity. `SessionStateBoundaryTest` covers the
strict path and both transports with an initially absent session.

## Oracle exception

`Legacy401RetryOracleTest` establishes an initially absent session from a 401
front token. Published URLConnection retries the original 401 response until
its configured maximum, throws `IllegalAccessException` with the maximum-limit
message, and makes no refresh request. The published bytecode shows the retry
branch compares the captured and current local session state/marker after
saving response headers, and `newRequest` throws that exception at the retry
limit. This is not a reflection access failure. The harness unwraps
`InvocationTargetException` to its original cause.

Reproduction: `./gradlew :app:legacy054Test --tests
'com.supertokens.session.legacy054.Legacy401RetryOracleTest'` passes;
historical 200 and removal-row failures and follow-up results are archived privately.
See the approved anti-CSRF exception in the adjacent provenance boundary. These runs
do not establish final-candidate or release qualification.
