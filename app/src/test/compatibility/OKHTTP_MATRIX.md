# Isolated OkHttp matrix and nightly stress

Use JDK 11.0.25+9, SDK32 and Gradle6.5. These tasks do not alter the SDK dependency
declaration, ordinary test configuration or historical 3.3 probe.

```sh
./gradlew --no-daemon :app:okhttpMatrix
# Individual lanes:
./gradlew --no-daemon :app:testOkHttp312
./gradlew --no-daemon :app:testOkHttp410
./gradlew --no-daemon :app:testOkHttp412
./gradlew --no-daemon :app:testOkHttp5
```

Pinned combinations, including published transitive minimums:

- 3.12.13 / Okio1.15.0 / Kotlin1.3.41 (host test support only) / TLS BC1.60.
- 4.1.0 / Okio2.2.2 / Kotlin1.3.41 / TLS BC1.62.
- 4.12.0 / Okio3.6.0 / Kotlin1.9.10 (Okio requires this above OkHttp's 1.8.21).
- 5.5.1 / Okio3.18.1 / Kotlin2.2.21 (higher requirement in okhttp-jvm Gradle metadata).

5.5.1 is the stable release in Maven Central metadata fetched 2026-10-29:
https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml
Matching okhttp-jvm, mockwebserver, mockwebserver3 and okhttp-tls artifacts exist.
Their ordinary classfiles are Java8; JDK11 can load them. This is a JVM matrix,
not a claim that OkHttp5 supports API17. Android floor testing keeps OkHttp3.3.

Each task recompiles all `src/test/java` sources against its lane's classpath;
the SDK binary still comes from the normal variant build. The published oracle
driver is separately compiled against the checksum-verified 0.5.4 jar and the same
lane. No Core source set is added. Default AGP task dependencies supply generated
resources/mockable Android and may also compile the ordinary tests, but those test
classes and network libraries are replaced in the matrix runtime. Class-provider
checks fail on duplicates or wrong-origin libraries. JUnit is 4.13.2 in all lanes.
Resolved coordinates, hashes, runtime classpath, JUnit XML and HTML go under
`app/build/okhttp-matrix/<lane>/`. Zero discovered tests or any skip fails the lane.

## Capability coverage

No class or legacy test is excluded for 3.12. The nine owned-only scenario branches
in Backpressure, ResourceBounds, StateBoundary, TransportPolicy and TLS instead
assert the explicit constructor IllegalArgumentException, unchanged snapshot and
zero wire dispatch. They print `CAPABILITY ... owned scenario not executed`.
Those passes prove rejection, not successful owned transport. Every supported
lane executes the original owned scenario. No assumption/skip or exception
allowlist was added. `OkHttpCapabilityTest` independently checks the contract and
adds no-arg TLS/DNS/pin/refresh tests on every lane. Existing no-arg/tagged owner
fencing, URLConnection, event, storage, stress and oracle suites remain selected.

## Nightly execution

`.github/workflows/nightly-compatibility.yml` runs all four lanes independently
plus twenty actual Gradle stress invocations. PR `unit.yml` adds the 3.12 lane;
its independent seven historical probes, including 3.3, remain unchanged.

```sh
python3 app/src/test/compatibility/nightly-stress.py --output "$NEW_PRIVATE_OUTPUT"
# Deterministic operation seeds (thread scheduling still nondeterministic):
python3 app/src/test/compatibility/nightly-stress.py --output "$ANOTHER_NEW_OUTPUT" --seed-base 1592598566
```

The runner records twenty random unique seeds before starting (or the requested
consecutive range). Each call forwards seed/16 threads/500 operations/120s watchdog
through the existing `-Psession.stress.*` properties and uses `--rerun-tasks`.
It deletes only the previous stress XML before invoking Gradle, then archives the
new XML, actual command and full log per seed. Success requires the two exact
stress methods, no failure/error/skip, matching emitted seed/operation settings
and an empty emitted invariant ledger. It continues after failed seeds and fails
overall unless all twenty succeed. Host invocation timeout is 15 minutes per seed;
cancellation terminates only that invocation's owned process group. Parser-control
XML in `test-nightly-stress.py` is explicitly synthetic, never execution evidence.

## Lint, Retrofit and Core integration

`lintRelease` now explicitly aborts on errors and treats NewApi as error. No lint
baseline, suppression or broad ignore was added. New real errors must be fixed
after the build owner executes lint; static validation is not a lint pass.

Testapp retains Retrofit2.3/OkHttp4.1 by default. `-PretrofitCompatibility=true`
selects Retrofit/converter-gson2.9 and coherently forces OkHttp3.14/Okio1.17.2 only
in testapp's UnitTest configurations. Reports are separate under `retrofit29`.
CircleCI runs the original full testapp followed by this additional full lane.
No SDK production dependency changes are involved.

CircleCI installs explicit JDK11 for Android Gradle tasks; existing Core JDK21
setup remains separate. After qualification and its consumption gate it now runs
`npm run test:core`, then explicit Java CoreSessionProtocolTest,
CoreSdkCompatibilityTest and CoreLostRefreshResponseTest before testapp's lifecycle
tests. Unknown/unqualified/unsupported fixture prerequisites still fail; no blanket
historical skip is introduced. A pair-proven account-linking exclusion is not R7
success. `CoreProxyProtocolTest` remains a separate **unimplemented fixture gate**:
`qualificationProxy=false`, missing `/__qualification/*`; it is named in CI output
as unimplemented, not included among passing Core scenarios. Do not run `Core*`
unfiltered or claim complete proxy qualification.

First real Gradle dependency resolution, complete matrix execution, lint, Linux
CI and historical backend pairs remain required. Required checks/release gating
are external administration; these workflow files do not configure them.
