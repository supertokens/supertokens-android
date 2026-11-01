# Published-driven upgrade matrix (M6 ranks 1/3)

## Shared seam

`src/upgradeHarness/java` builds a test-only jar containing the existing child-first
`Legacy054Harness`, `PublishedUpgradeScenario`, and a persisted cookie bridge.
The loader still refuses candidate fallback for missing `com.supertokens.*` classes.
The one existing `src/legacyDriver` driver is compiled exclusively against the
checksum-pinned published 0.5.4 jar. Neither driver nor published SDK is put on the
candidate test compile/runtime classpath; their paths are loader system properties.

Root JVM tests and standalone testapp consume the same harness jar. Candidate reset
uses the existing test-only method reflectively, without adding a production API.
The standalone build retains one candidate AAR and explicitly depends on root
`:app:preparePublishedUpgradeArtifacts` for candidate AAR, harness jar and driver.

## Deterministic synthetic suite

`legacy054.Legacy054UpgradeTest`: 48 rows, header/cookie/exposed-cookie ×
login/refresh/signout/payload-update × synthetic access-token versions 2–5.
Every initial flow is executed by the real published SDK, then its preferences are
flushed/restarted and the candidate imports the same stores. Active rows compare
existence, uid, exposed access token and payload before candidate protected access,
refresh, another protected request, payload update and signout.

Logout rows first assert absence, then verify login remains reachable; they never
require a protected 200 while logged out. Payload updates retain candidate owner
identity and emit exactly one ACCESS_TOKEN_PAYLOAD_UPDATED event. Owner must be
non-null before either continuity assertion.

`UpgradeTokenEvidenceTest` adds 12 controls (three modes × four flows) using real
published login v2, refresh v5 and payload promotion v4. It independently checks
login, post-operation handoff and candidate refresh observations, and rejects a
wrong configured version at each phase. Logout handoff is explicitly ABSENT.

Cookie rows use actual PersistentCookieJar/SharedPrefsCookiePersistor, with separate
SDK and cookie preference namespaces. A new jar reloads persisted cookies at the
candidate boundary. URLConnection reads/writes that same jar through CookieHandler.
Exposed-cookie responses have an exposed access header but **no refresh-token
header**. Synthetic dispatch asserts cookie authentication, not invented JWT bearer
refresh credentials. Header rows disable cookie fallback (published signout needs
a non-null CookieHandler, supplied with ACCEPT_NONE). Every wire also checks the
explicit synthetic FDI selector; this does not certify that FDI against real Core.

Run after wiring review:

```sh
./gradlew :app:legacy054Test --tests '*Legacy054UpgradeTest'
./gradlew :app:legacy054Test --tests '*UpgradeTokenEvidenceTest'
```

## Externally provisioned live suite

`PublishedUpgradeLiveTest` has 16 potential rows: 12 `/login` scenarios plus four
header `/login-2.18` scenarios. It does not start/stop Core, call beforeeach/startst,
or reconfigure backend recipes. Run each prepared Node/Core/FDI member with a long
enough access-token lifetime, separately in plain-cookie and enableJWT configs.

```sh
node testHelpers/testapp/runTests.js upgrade \
  -Pupgrade.baseUrl="$ST_TEST_BACKEND_URL" \
  -Pupgrade.fdiVersion=1.16 \
  -Pupgrade.expectedNodeVersion=16.7.0
```

The example selects one member, not the entire matrix. The task fails if every row
is skipped. Existing standalone tests/assertions are unchanged; this class is only
selected by the separate task.

`upgradeLiveTest` declares the published jar, driver directory and supplied receipt
file contents as inputs. Up-to-date reuse and build-cache reuse are disabled:
the externally provisioned backend can change independently of local files.

Capabilities come from actual `/test/capabilities` (Node version, reachable and
compatible Core, advertised CDI versions) and `/featureFlags`. Unavailable or
incompatible Core fails; unknown capability routes fail. Header transfer requires
Node 13+; exposed-cookie requires Node 14+ and sessionJwt enabled. Unexposed-cookie
rows run when that exposure is disabled, including pre-14 enableJWT configurations
(embedded JWT support is not access-token exposure). These boundaries follow the Node SDK changelog
(13.0.0 header transfer; 14.0.0 exposed-cookie configuration) and this test server's
`getConfig`, not inferred token versions.

`/login-2.18` additionally requires advertised CDI 2.18 and
`-Pupgrade.legacy218Evidence=/absolute/path/validated-route.json`. That prior
compatibility-test receipt must contain `status:"passed"`, `route:"/login-2.18"`,
matching `nodeVersion`, `fdiVersion`, and `coreCdiVersions`. No receipt is fabricated
by these tests. This is semantic pair validation, not cryptographic process identity.

Logs record structured schemaVersion 2 observations: `publishedLogin`,
`publishedHandoff` after the selected published operation, and `candidateRefresh`.
Each has status PRESENT and the observed tokenVersion, or ABSENT/null for logout
handoff. Versions come from access JWTs (persisted cookie when unexposed), never
the route name or login version carried forward. Actual Node/CDI/feature data and
requested FDI are logged; tokens and cookie values are not.

`upgrade.expectedTokenVersion` optionally asserts every present phase, including
`/login-2.18`. Phase-specific overrides are `upgrade.expectedPublishedLoginVersion`,
`upgrade.expectedPublishedHandoffVersion`, and `upgrade.expectedCandidateRefreshVersion`.
Logout always expects ABSENT handoff. Select homogeneous rows with `--tests` when
routes/flows require different expected versions; e.g. v2 login does not establish
v2 handoff after published refresh or payload promotion. Without configured
expectations the actual observations are still recorded, but a specific version
coverage target is not asserted. Missing JWT version is classified as v5 only with
kid/RS256/JWT header shape.

Passing synthetic rows does not prove real token versions 2–5 were exercised. Every
required live FDI member, both feature configurations, available validated 2.18
routes, and Gradle standalone artifact wiring still require execution/qualification.
