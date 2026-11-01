# D5 runtime compatibility — integrated implementation

2026-10-29, repository HEAD `eda605ba8453bc716b82b83c04628a4c0ac4ba3e`, uncommitted working tree. **Applied and integrated after lane release and approval of the single SuperTokens.java generic-inference adjustment.** No commits. The staging record below is historical; this integration section supersedes its pending/application statements.

## Integration evidence

Environment: Temurin JDK 11.0.25+9, Android SDK 32, existing AGP 4.1.2 / Gradle 6.5. Every Gradle invocation used `JAVA_HOME=/Users/bogdan/.local/share/mise/installs/java/temurin-11.0.25+9 ANDROID_HOME=/Users/bogdan/Library/Android/sdk`.

Successful build/gate invocation:

```sh
./gradlew --no-daemon :app:assembleRelease :app:compileDebugUnitTestJavaWithJavac \
  :app:compileDebugAndroidTestJavaWithJavac :app:generatePomFileForReleasePublication \
  :app:generateMetadataFileForReleasePublication :app:apiCompatibilityGateTest \
  :app:japicmp :app:verifyOldSdkConsumer
```

AGP integration required changing the staged Gradle wiring: Java 8 remains the Android default so AGP supplies its test lambda stubs; `android.libraryVariants` configures each **production** JavaCompile provider with source/target 7. The initial global-7 approach first lost test source-8 settings, then lacked LambdaMetafactory compiler stubs. Both failures are recorded in `integration-build.log` / `integration-build-fixed.log`; final successful wiring is in `integration-build-final.log`. Actual unit test classes are major 52 (75 classes); instrumentation test classes are major 52 (8 classes).

Fresh release AAR `app/build/outputs/aar/app-release.aar`:

- SHA-256: `07f23a480082a52832c42d3f84c69263ace544e32591e7b032f99195ef0acb9b`.
- **77 classes, all major 51**, including generated `BuildConfig`. Extracted `release-classes.jar`, `artifact-class-majors.json`, and `release-bytecode.txt` retained in the staging/evidence directory below.
- No direct `Chain.call`, `MediaType.get`, `Objects.requireNonNull`, or `invokedynamic` in the release disassembly. Optional typed tags and modern owned-refresh references remain deliberately isolated.
- Generated POM/module metadata declare only runtime `androidx.appcompat:appcompat:1.1.0`; no OkHttp dependency or JVM-version attribute was introduced. AAR metadata contains only format versions, no new min-compile-SDK constraint. Release version remains the existing 0.5.4 value; release-version changes were outside this slice.
- `apiCompatibilityGateTest`, `japicmp`, and `verifyOldSdkConsumer` passed. `disallowed-changes.txt` is empty. Existing regenerated consumer bytes were verified, not modified by this task; its runtime smoke is included in the full JVM pass.

Final integrated invocation (after storage-test edit completion):

```sh
./gradlew --no-daemon --continue :app:testDebugUnitTest :app:legacy054Test :app:lintRelease
```

**233 JVM tests + 36 published-0.5.4 oracle tests, zero failures/errors/skips.** Includes approved storage rollback rebaselines/strict controls, R17/resource bounds, R1 recovery, ownership, tagged calls, refresh, deadline and old consumer smoke. XML evidence is copied under `results/` alongside `full-integration.log`. `lintRelease` completed successfully with an empty release issue report, but emitted an old lint/JNA native-library warning on this Mac; this is not API-17 runtime certification.

Initial focused run was 137/138: `callbackPreparationPastDeadlineNeverDispatchesRefresh` incorrectly imposed the SDK deadline on the legacy caller chain. Its SDK-owned/URLConnection assertions remain; D5/R12 removes only the no-argument mode. New `LegacyRefreshRuntimeTest.slowPreparationDoesNotImposeSdkDeadlineOnCallersChain` advances the preparation clock past ten seconds and verifies successful refresh/retry, preserved owner and an uncancelled user call. This is the only existing test rebaseline in this runtime slice; storage assertions were changed by their assigned owner.

### Fresh-artifact historical runtime matrix

The standalone probe now accepts `--artifact-jar` and optional `--mockable-android-jar`. Invocation:

```sh
python3 app/src/test/compatibility/runtime/probe.py --source-root . \
  --artifact-jar "$STAGE/release-classes.jar" --output "$STAGE/artifact-session-probe" \
  --java-home /Users/bogdan/.local/share/mise/installs/java/temurin-11.0.25+9 \
  --android-jar /Users/bogdan/Library/Android/sdk/platforms/android-32/android.jar \
  --mockable-android-jar /Users/bogdan/.gradle/caches/transforms-2/files-2.1/f4764c590c0ddeeb831f8f8b2babb075/android.jar
```

Set `STAGE` to the evidence directory below. **All seven exact dependency lanes listed in the initial checks passed against the extracted release JAR**, not freshly recompiled production sources. Log: `artifact-session-probe.log`; dependency hashes: `artifact-session-probe/dependencies.sha256`.

Each lane additionally runs actual SDK login/adoption → protected 401 → header refresh → retry → token rotation → sign-out, preserving an arbitrary application tag. `HistoricalSessionProbe` uses a JDK HTTP server, the existing TestPreferences/Base64 test shims and AGP's mockable Android JAR. The first fixture attempt omitted the sign-out `front-token: remove` header and correctly failed its logout assertion; the final server emits the protocol header. These are JVM HTTP/header-mode smoke tests, not Android, TLS, cookie, persistence-device, or complete cross-version suite certification.

Remaining: physical/emulated API 17, historical consumer build pipelines, R8 optional-class isolation, broader runtime matrix scenarios and device upgrade/rollback. No raised legacy runtime minimum was adopted. The initial staging patch is retained for provenance and does not include the final AGP wiring correction or integration tests; current working-tree files are authoritative.

## Initial staging handoff (historical)

Staging directory:

`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/android-runtime-d5`

- `runtime-compatibility.patch`: assigned production files, two new internal adapters, production Java 7/test Java 8 Gradle configuration.
- `source7-inference-approval.patch`: **separate scope approval required**, one explicit `<String, String>` type argument on `Collections.emptyMap()` in `SuperTokens.java`. Without it, the current full production snapshot fails source-7 compilation. No behavioural change intended.
- `complete-production.patch`: both patches combined; do not apply it as well as the individual patches.
- `before/`, `candidate/`, `base-sources.sha256`: exact input snapshots and changed-file baseline hashes. Unrelated snapshot files are not patch payload.
- `probe/`: isolated javac outputs, downloaded dependencies and `dependencies.sha256`; `probe/bytecode.txt` contains the fresh isolated disassembly.

Both individual patches passed `git apply --check` against the shared tree at handoff. Recheck after the active lane finishes. Do not replace entire files from the snapshot: that would overwrite concurrent import work. The patch preserves the existing R17 branches and API-gate `apply from` wiring. It touches no storage, import, API gate, or consumer fixture files.

The only shared additions from this task are this document and `runtime/RuntimeCompatibilityProbe.java` / `runtime/probe.py`. They are outside default Android source sets; the API gate compiles its old consumer by explicit filename.

## Decisions and capability boundary

Published 0.5.4 classes are Java 7. Its audited direct OkHttp references link against 3.3.0; this is not certification of every runtime/dependency combination. No new global OkHttp or Android minimum is introduced.

- Legacy no-argument interception uses only historical APIs. Refresh reuses `chain.proceed`, never looks up or cancels the user's `Chain.call`, and relies on caller transport timeouts. Shared refresh follower/cooldown/flight policies remain unchanged.
- Fixed content type uses `MediaType.parse`, available in 3.3.0.
- `OkHttpSessionTags` selects its reader once using a synthetic request. Only a missing typed-tag method at that probe selects the historical reader. No reflection, runtime-version parsing, or catch surrounding user callbacks/network execution. Typed access is isolated in a nested implementation class. Historical arbitrary application tags are ignored and never overwritten; historical `SessionRequestTag` objects in the untyped slot are recognized. Modern runtimes retain the typed-slot contract and preserve the independent application tag.
- New SDK-owned refresh remains explicit opt-in. `OwnedOkHttpRefresh` probes the **base RequestBody API**, not our overriding subclass, before deriving a client. Missing `isOneShot()` produces a clear constructor error before session/network mutation. Its public-contract threshold is **OkHttp 3.14**, not 3.12. Newer Android requirements of optional modern OkHttp do not become requirements of the legacy no-argument path.
- Owned refresh retains call/connect/read/write limits, independent cancellation, one-shot body, disabled redirects/retries/authenticators/cache, and removed host interceptors. Modern timeout method references live in the owned adapter. No silent fallback to weaker refresh policy.

### Why 3.14

Source evidence:

- [3.12.13 RequestBody](https://github.com/square/okhttp/blob/parent-3.12.13/okhttp/src/main/java/okhttp3/RequestBody.java) and [3.13.0 RequestBody](https://github.com/square/okhttp/blob/parent-3.13.0/okhttp/src/main/java/okhttp3/RequestBody.java) lack public `isOneShot()`.
- [3.14.0 RequestBody](https://github.com/square/okhttp/blob/parent-3.14.0/okhttp/src/main/java/okhttp3/RequestBody.java) introduces that contract.
- [3.14.0 RetryAndFollowUpInterceptor](https://github.com/square/okhttp/blob/parent-3.14.0/okhttp/src/main/java/okhttp3/internal/http/RetryAndFollowUpInterceptor.java) checks one-shot bodies before following up, including retryable server responses.
- [3.12.13 follow-up handling](https://github.com/square/okhttp/blob/parent-3.12.13/okhttp/src/main/java/okhttp3/internal/http/RetryAndFollowUpInterceptor.java) disables HTTP-408 retry when connection retries are disabled, but HTTP 503 with `Retry-After: 0` can still replay. 3.13 has the same limitation. Cached 3.3.0 `HttpEngine.followUpRequest` disassembly additionally shows its 408 branch can repeat without consulting `retryOnConnectionFailure`.

An overriding `isOneShot()` method alone links on old runtimes but is ignored there. Checking only call-timeout support would accept insufficient runtimes.

## Java / Android choices

Production source/target becomes 7. Assigned lambdas and method references become anonymous implementations with final captures. Test Java compilation tasks retain source/target 8; the pre-existing legacy driver/API-consumer tasks already specify 8.

No public methods were removed, and no interface default methods added. Source audit found no remaining production Java-8 language constructs in the copied snapshot after the assigned conversions. The one out-of-scope generic-inference adjustment is separately staged above.

Isolated bytecode contains no `invokedynamic`, `Chain.call`, or compiler-generated `Objects.requireNonNull`. Existing Java-7 try-with-resources still emits two `Throwable.addSuppressed` calls (interceptor and `SuperTokens`). Major 51 alone does not prove API-17 safety: verify these calls through historical desugaring/build pipelines and the actual device floor. Android APIs were compiled against SDK 32 here, not an API-17 boot classpath. Modern AGP success does not establish historical consumer-toolchain compatibility.

## Executed isolated checks

Temurin JDK **11.0.25+9**, SDK-32 `android.jar`, no Gradle. Exact invocation:

```sh
python3 app/src/test/compatibility/runtime/probe.py \
  --source-root /private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/android-runtime-d5/candidate \
  --output /private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/android-runtime-d5/probe \
  --java-home /Users/bogdan/.local/share/mise/installs/java/temurin-11.0.25+9 \
  --android-jar /Users/bogdan/Library/Android/sdk/platforms/android-32/android.jar
```

Results:

- Whole copied production source compiled with `-source 7 -target 7`, **including the separately proposed inference fix**; all **76** resulting classes have major **51**. Compiler warns that a Java-7 bootstrap classpath was not supplied; this is not a Java-7 platform API audit.
- Seven separate `java -Xverify:all` processes passed: OkHttp/Okio **3.3.0/1.8.0**, **3.10.0/1.14.0**, **3.11.0/1.14.0**, **3.12.13/1.15.0**, **3.13.0/1.17.2**, **3.14.0/1.17.2**, **4.1.0/2.2.2**. Only the 4.1 lane adds Kotlin stdlib 1.3.41. Okio versions for 3.x were checked against their published parent POMs.
- Untagged/arbitrary-tag handling and no-argument constructor loading passed throughout. Historical untyped session tags passed on 3.3/3.10; typed session tags plus independent application tags passed from 3.11.
- Owned constructor rejected 3.3–3.13 with the intended message. On 3.14/4.1, actual SDK-derived clients made exactly one request for **408, 503 + Retry-After: 0, 307, and 401**, using a probe one-shot body. These exercise the dependency transport contract; they do not execute the SDK's complete refresh/session state machine.

The probe uses a JDK loopback HTTP server, not MockWebServer, so old runtime lanes cannot accidentally inherit newer test dependencies. Downloads are version-specific; observed hashes are written to the output manifest. Use a fresh output directory when changing production sources to avoid stale javac classes.

## Pending integration commands and release evidence

After lane release and approval of the inference fix:

```sh
git apply --check "$STAGE/runtime-compatibility.patch"
git apply --check "$STAGE/source7-inference-approval.patch"
# Coordinator applies the reviewed patches, then the sole Gradle executor runs:
./gradlew --no-daemon :app:assembleRelease :app:compileDebugUnitTestJavaWithJavac \
  :app:compileDebugAndroidTestJavaWithJavac :app:lintRelease
./gradlew --no-daemon :app:testDebugUnitTest --tests '*UntaggedStaleRetryTest' \
  --tests '*SessionLifecycleTest' --tests '*SessionResourceBoundsTest' \
  --tests '*SessionBackpressureContractTest' --tests '*SessionRefreshPolicyTest'
```

Also run all API_GATE.md commands, the integrated suite and a real legacy refresh/login/sign-out runtime matrix. Confirm source/target task ordering in AGP (not executed here). Inspect the **final AAR** class versions and dependency references; compare generated/public classes through japicmp. Run unchanged old-consumer builds, API-17 verification/execution, and a minified R8 consumer to check optional adapter isolation. Preserve stale-tag rejection and delayed-owner R17 coverage, and explicitly test that legacy refresh deadlines never cancel user calls. Run full owned-refresh fault/deadline tests on supported runtimes, including disconnect-after-send and late callback preparation.

These remain release gaps. The isolated adapter/linkage checks are not fresh-artifact, Android-runtime, old-toolchain, or complete session-runtime certification.
