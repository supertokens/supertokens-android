# Published API compatibility gate

M6 rank 6 / D5. Run with JDK 11 and the repository's Android SDK configuration:

```sh
./gradlew --no-daemon :app:apiCompatibilityGateTest :app:japicmp
./gradlew --no-daemon :app:regenerateOldSdkConsumer
./gradlew --no-daemon :app:verifyOldSdkConsumer :app:testDebugUnitTest \
  --tests 'com.supertokens.session.SessionLifecycleTest.precompiledPublishedSdkConsumerLinksAndExecutesAgainstCandidate'
```

`check` includes the API gate, its policy fixtures, and consumer freshness check. Regeneration is explicit and changes the checked-in `.bin`; run it only in the assigned build lane. The script alternative requires the published AAR and an OkHttp dependency classpath as its two arguments. Use JDK 11 for reproducible consumer bytecode.

## Policy

- Reuse `extractLegacy054` and its pinned AAR checksum. No candidate classes appear on the old consumer compilation classpath.
- Pin standalone japicmp 0.17.2 in an isolated build-tool configuration; no Gradle plugin or Android toolchain upgrade.
- Compare the release Java compilation output, including generated public classes, against the published `classes.jar`.
- Reject removed public classes, changed/removed public methods, constructors and fields, incompatible class changes, and changed existing class signatures.
- Reject all added methods on `EventHandler`, `CustomHeaderProvider`, and `SuperTokensHttpURLConnection.PreConnectCallback`, including default/static methods.
- Permit ordinary new public APIs. Annotations are not API signatures for this gate (`--no-annotations` permits deprecating legacy entry points). There is no member exclusion list and missing dependencies fail comparison.
- Reports: `app/build/api-compatibility/report/japicmp.xml`, `japicmp.txt`, and `disallowed-changes.txt`. Only the last report must be empty.
- `apiCompatibilityGateTest` compiles small archives and runs the real comparator: unchanged API and additions pass; removal, return-type/visibility changes and additions to each callback fail.

The expanded consumer references the old public surface, including the URLConnection wrapper's declared methods and deprecated raw setters. `exerciseAbsentSession` runs deterministic local checks. `exerciseNetworkAndMutationSurface` is a compiled linkage probe, not a network test; the API diff provides exhaustive signature coverage. Lambdas keep the fixture to a single class resource.

## Artifact audit — 2026-10-29

Observed cached published AAR SHA-256, independently checked against the existing pin:

`2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9`

- All **31** class files in its `classes.jar` have major version **51 (Java 7)**. The candidate build requests Java 8 / major 52. The claim that published 0.5.4 already required Java 8 bytecode is false. This observation alone does not establish an Android runtime minimum: D8/desugaring and dependency/runtime evidence still need separate validation.
- The published POM and Gradle module metadata declare only runtime `androidx.appcompat:appcompat:1.1.0`; neither declares an OkHttp version requirement.
- Disassembly found **42 distinct direct OkHttp method references**. Every owner/name/JVM-descriptor pair exists in the Maven Central **OkHttp 3.3.0** JAR, checked with `javap -s -p`. No published references to `Chain.call()`, typed `Request.tag(Class)`, or `Call.timeout()` were found.
- Published references include `Response.close()`, `Response.sentRequestAtMillis()`, `Response.receivedResponseAtMillis()`, and the two timestamp builder methods. These are absent from the tagged 3.2.0 source and present in 3.3.0. Thus 3.3.0 satisfies the observed direct-method linkage floor; this is **not** an executed runtime compatibility matrix or proof about transitive dependencies. Sources: [3.2.0 Response](https://github.com/square/okhttp/blob/parent-3.2.0/okhttp/src/main/java/okhttp3/Response.java), [3.3.0 Response](https://github.com/square/okhttp/blob/parent-3.3.0/okhttp/src/main/java/okhttp3/Response.java).

Requiring OkHttp 3.12 cannot be justified as an already-established published dependency floor. Resolve that compatibility question before release. This gate does not silently waive runtime-floor changes or claim to test them.

## Verification — 2026-10-29

After receiving the exclusive Gradle lane, ran all three command lines above with Temurin 11.0.25+9 and `/Users/bogdan/Library/Android/sdk`. All succeeded against the integrated, uncommitted working tree:

- **17** real compiler/comparator policy cases passed.
- Candidate report: **35** public classes (18 unchanged, 4 modified, 13 new); `disallowed-changes.txt` is **0 bytes**. Modified classes contain permitted additions; no public API incompatibility was found under this gate's policy.
- Regenerated `OldSdkConsumer.bin` against the checksum-verified published artifact: **14,163 bytes**. Freshness verification passed.
- Candidate runtime smoke: **1 test**, zero failures/errors/skips.

Oracle tasks were preserved. The unrelated import assertion conflicts were not changed or tested here. The Java bytecode/OkHttp requirement decision remains pending; API signature success is not a runtime-floor approval.
