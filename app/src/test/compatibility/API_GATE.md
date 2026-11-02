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

Historical audit and verification observations are preserved in the private archive.
