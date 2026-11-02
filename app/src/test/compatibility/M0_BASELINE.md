# M0 differential baseline (partial)

## Observations before the fixes

The 2026-11-01 review baseline was `a27f6be`. The regression plan records
throwaway candidate probes for R18–R23; R18, R19, R21 and R22 were also checked
against the published 0.5.4 AAR. R20 and R23 were compared with 0.5.4 source,
not an equivalent recovery-state oracle run. Those probes are not a complete
record of failing test names.

The initial opt-in response-shape tests observed four failures on the candidate:

- R18: `LegacyResponseShapeMatrixTest.r18FrontOnlyOkHttp` and
  `r18FrontOnlyURLConnection`: oracle has a session; candidate does not.
- R22: `LegacyResponseShapeMatrixTest.r22RemovalWithAccessOkHttp` and
  `r22RemovalWithAccessURLConnection`: oracle clears the session; candidate retains it.

These rows compare session existence and user ID under header configuration,
not the full response-shape matrix. The original run used JDK 11 and
`ST_RUN_M0_RED=1`; it is historical evidence, not a current test instruction.
The later targeted red run below adds evidence for R19–R21 and R23.

## Targeted red run, 2026-11-02

An isolated tree used unchanged production code from `a27f6be`, with tests and
the legacy driver from the later tree. Six unrelated later test classes were
excluded from compilation because they use APIs unavailable at that revision:
`InFlightPromotionTest`, `RefreshRedirectMethodTest`,
`RefreshUrlConnectionParityTest`, `UntaggedStaleRetryTest`,
`CustomAuthorizationTest` and `RefreshRetryAdmissionTest`. This is not a run of
the full suite or of the original test tree.

The targeted legacy run compared the published 0.5.4 oracle with the candidate:

- `LegacyRefreshOutcomeMatrixTest.r19Bare401ThenRefresh`: oracle outcome
  `java.io.IOException:true:true:2`; candidate `false:false:false:1`.
- `LegacyRefreshOutcomeMatrixTest.r21ServerFailureThenRefresh`: oracle outcome
  `java.io.IOException:true:200:2`; candidate
  `java.io.IOException:true:java.io.IOException:1`.
- `LegacyResponseShapeMatrixTest.r23ExposedCookieUtilityOkHttp` and
  `r23ExposedCookieUtilityURLConnection`: oracle `exposed`, candidate `null`.
- `LegacyUtilityParityMatrixTest.r23PreInitAuthorizationReadsStoredTokens`:
  oracle `Bearer access`, candidate `null`.

That legacy invocation ran 17 tests: 13 failed, including these five, the four
R18/R22 tests above, and four duplicate-header tests; four strict duplicate
tests passed. Separately, candidate-only
`LegacyEntryPointStateMatrixTest.r20RecoveryRefreshBehavesAsAbsent` and
`r20RecoverySignOutBehavesAsAbsent` both failed with `IOException: Session
storage recovery is required`. These R20 rows did not run against an oracle.
The results establish these named red cases only, not the full planned matrix.

## Subsequent coverage

The opt-in guard has been removed: the R18/R22 rows now run by default in
`legacy054Test`. Later additions include:

- `LegacyRefreshOutcomeMatrixTest`: R19 bare 401 and R21 retry-after rows,
  six caller categories across several outcomes, subsequent requests, and
  disconnect/replay comparisons. It records outcomes, stored front token and
  refresh counts; it is not the complete planned matrix.
- `LegacyEntryPointStateMatrixTest`: candidate-only R20 recovery refresh and
  sign-out rows, plus selected absent, recovery and available getters/refresh.
- `LegacyResponseShapeMatrixTest`: R23 exposed-cookie utility and duplicate
  header rows on both transports, including tagged duplicate checks;
  `LegacyUtilityParityMatrixTest` compares pre-init authorization for R23.

These are test coverage descriptions, not a claim that the latest candidate or
final artifact passed them. Run with JDK 11 using `./gradlew --no-daemon
:app:legacy054Test`; the candidate-only state matrix belongs to the unit suite.

## Cross-product run, 2026-11-01

`LegacyShapeCrossProductTest` covers four prior states, two transfer methods,
ten response shapes, 200/401, and both transports. For non-removal 401 rows it
asserts the published oracle's characterized result separately from the
candidate's no-credential-header contract; this is an intentional difference,
not parity. All 80 oracle and candidate contract checks matched in this run.

The run failed four matrix tests, with the same 16 differential rows per
configuration/transport (64 total):

- 200, prior COOKIE or EXPOSED_COOKIE, shape COMPLETE or DUPLICATE_ACCESS:
  oracle retains `csrf1` in storage and on the next request; candidate stores
  and sends no anti-CSRF token. Both return 200, retain a session, and expose
  the new access and refresh tokens.
- 401, prior HEADER, COOKIE or EXPOSED_COOKIE, shape REMOVE, REMOVE_ACCESS,
  REMOVE_REFRESH or DUPLICATE_FRONT: both return 401, clear the session and
  send no session headers on the next request. Oracle events are
  `[SESSION_CREATED, UNAUTHORISED, UNAUTHORISED]`; candidate events are
  `[SESSION_CREATED, UNAUTHORISED]`.

The two focused parity tests in the same class passed. These failures are not
suppressed by the 401 exception. The strict 401 boundary and both-transport
absent-session tests passed separately. No ownership guard was relaxed.

## Remaining gaps

M0's exit criteria are not met. The cross-product above is limited to its named
shapes, states and statuses; it is not an exhaustive protocol matrix. Refresh coverage
does not exhaust the planned front-only and timeout outcomes or every caller and
transport; the entry-point/state matrix does not cover every public entry point
and state. Cookie support in the legacy driver is opt-in because URLConnection's
cookie handler is process-global; parallel cookie tests must not share a JVM.
The added cookie support has not been independently qualified. Final-SHA unit,
integration, device and published-upgrade gates remain pending; these historical
observations do not qualify a release.

The response-shape matrix now seeds COOKIE and EXPOSED_COOKIE with actual
`sAccessToken` and `sRefreshToken` cookies through OkHttp and verifies that the
transport sends both on the shaped and subsequent requests. The jar is owned by
the caller, not the SDK; response shapes in this matrix do not set or delete
cookies. Seeding uses OkHttp even for URLConnection rows: under JDK 11, the
published 0.5.4 URLConnection wrapper throws `IllegalArgumentException: Empty
cookie header string` while processing the login's Set-Cookie headers. Thus
these rows do not qualify URLConnection cookie-setting responses or Android's
platform cookie implementation. The HTTP-only cookie fixtures also do not
exercise Secure, domain, or refresh-path scoping.

## Follow-up cross-product run, 2026-11-01

With compatible ordinary 401 removal notifications emitted twice after a
successful fenced removal, all removal rows now match the oracle. The four
matrix tests still failed on 16 cookie-to-header anti-CSRF retention rows total
(four per configuration/transport). This was a direct parity assertion at the time.
See `LEGACY_CSRF_PROVENANCE_CONFLICT.md` for the ownership conflict. The focused
removal oracle, response-shape, 401 retry and refresh-outcome suites passed, as
did the focused state-boundary, transport, in-flight promotion, stale-retry,
contract and refresh policy/failure suites. Runs used isolated Gradle invocations
with Temurin 11.0.25+9 and Android SDK 32.

## Approved anti-CSRF difference

The 16 rows above are an approved intentional difference from 0.5.4: the oracle
retains and sends `csrf1`, while the candidate does neither when complete header
credentials replace an opaque cookie session. The matrix separately checks both
behaviors and still compares all other fields. This does not change the existing
non-removal 401 contract or exempt any other rows; see
`LEGACY_CSRF_PROVENANCE_CONFLICT.md` for the scope and rationale.
