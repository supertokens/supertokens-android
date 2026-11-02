# Runtime compatibility runbook

Use JDK 11, Android SDK 32 and the assigned Gradle lane. Published 0.5.4 production
classes use Java 7 bytecode. The legacy interceptor retains caller transport policy;
the optional owned interceptor requires OkHttp 3.14 or newer. These facts do not
certify API 17, TLS, cookies or historical dependency combinations.

```sh
./gradlew --no-daemon :app:assembleRelease :app:compileDebugUnitTestJavaWithJavac \
  :app:compileDebugAndroidTestJavaWithJavac :app:generatePomFileForReleasePublication \
  :app:generateMetadataFileForReleasePublication :app:apiCompatibilityGateTest \
  :app:japicmp :app:verifyOldSdkConsumer
./gradlew --no-daemon --continue :app:testDebugUnitTest :app:legacy054Test :app:lintRelease
./gradlew --no-daemon :app:testDebugUnitTest --tests '*UntaggedStaleRetryTest' \
  --tests '*SessionLifecycleTest' --tests '*SessionResourceBoundsTest' \
  --tests '*SessionBackpressureContractTest' --tests '*SessionRefreshPolicyTest'
```

Run the historical runtime probe against the freshly extracted release classes:

```sh
python3 app/src/test/compatibility/runtime/probe.py --source-root . \
  --artifact-jar "$STAGE/release-classes.jar" --output "$STAGE/artifact-session-probe" \
  --java-home "$JAVA_HOME" --android-jar "$ANDROID_SDK_ROOT/platforms/android-32/android.jar" \
  --mockable-android-jar "$MOCKABLE_ANDROID_JAR"
```

Use a fresh external output directory and the matching mockable Android JAR. The
probe uses a JDK loopback server and records dependency hashes; JVM header-mode
results are not device, TLS, cookie or complete runtime qualification. Run the
[API gate](API_GATE.md), unchanged old-consumer builds, API17 execution and minified
R8 consumer checks on the final artifact. Verify stale-owner and delayed-response
fencing, legacy caller deadlines, and owned-refresh disconnect and callback bounds.
Historical results, failures and artifact details are retained in the private archive.
