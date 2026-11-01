# Published SDK compatibility fixture

`../resources/compatibility/OldSdkConsumer.bin` is Java 8 bytecode compiled from
`OldSdkConsumer.java` against the published JitPack artifact
`com.github.supertokens:supertokens-android:0.5.4`, not the current source.
`legacy054.sha256` owns the AAR SHA-256 pin, shared by Gradle and `regenerate.sh`.

To regenerate it from the repository root, use Temurin 11 and an Android SDK containing API 32:

```sh
JAVA_HOME=/path/to/temurin-11 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew --no-daemon :app:regenerateOldSdkConsumer
```

Gradle resolves the published AAR, verifies `legacy054.sha256`, and supplies the
Android and OkHttp compilation dependencies. The regenerated fixture is currently
an uncommitted working-tree change.

The normal test loads the resource bytecode against the candidate SDK; it does
not compile the consumer against the candidate. Review fixture changes carefully,
as recompilation changes which historical API signatures the test exercises.

## Published behavioral oracle

Implements [Batch 0C](../../../../COMPATIBILITY_IMPLEMENTATION.md#0c-build-the-054-oracle);
acceptance is [COMPATIBILITY_PLAN.md M0](../../../../COMPATIBILITY_PLAN.md#milestone-0-test-foundation-and-failing-baseline).

Run with a Gradle-6.5-compatible JDK (tested with Temurin 11.0.25) and Android API 32:

```sh
JAVA_HOME=/path/to/jdk-11 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew :app:legacy054Test :app:testDebugUnitTest
```

`extractLegacy054` resolves the published AAR through `legacy054`, verifies the pin,
then extracts `classes.jar`. `compileLegacy054Driver` sees only that jar, the Android
boot classpath and its dedicated OkHttp dependencies. It cannot compile against the
candidate. Initial dependency resolution requires network access; subsequent runs
can use `--offline`.

`Legacy054Harness` child-loads all `com.supertokens.*` classes without candidate
fallback. Android shims, JSON, OkHttp and shared interfaces come from the Android
debug JVM-test runtime. Each harness owns a fresh loader and client; close it with
try-with-resources. Keep the supplied Context strongly reachable while running it.
Reflection returns primitives, strings and maps; inspect exceptions by type name
when comparing SDK-specific exceptions from different loaders.

Put oracle test classes in `com.supertokens.session.legacy054` with names ending in
`Test`. `legacy054Test` filters this package and fails on no matching tests. Normal
Android JVM test tasks exclude it; they do not resolve or run the old SDK. The
smoke tests cover pre-init preference reads, build/static isolation, HTTP login,
refresh rotation, loader restart and intentionally shared versus fresh stores.

Use equivalent *separate* stores and backend sessions for differential comparisons.
Only sequential upgrade/rollback cases intentionally share a store. The smoke
test's small string-only preferences mock is not the full failure-aware shared
fixture and must not serve as a crash-consistency oracle. CookieHandler is global
even across loaders; tests must restore it.
