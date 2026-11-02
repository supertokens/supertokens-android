# Device restart pairs

`restart-pairs.sh` runs the four existing `SessionStorageRestartTest` seed/check
pairs in explicit order. Each check asserts a different process UUID from its
persisted seed receipt, plus durable session state. This is candidate-to-candidate
restart coverage, **not** published-SDK upgrade/rollback certification.

## Published upgrade / rollback runner

`upgrade.sh` orchestrates the standalone two-flavor
[`upgradeapp`](../upgradeapp/README.md). It requires four explicit APK paths, a
candidate AAR hash, device serial and backend URL. It validates artifact identity,
uses install-r without data clearing, and captures private typed preference XML
through published seed → candidate check → published mutation → candidate check.
Scenarios cover login B, refresh, logout and cookies, followed by scoped cleanup.

Historical device results are preserved in the private archive; they do not qualify a new artifact.

## Build and run

Wait for the exclusive Gradle lane, then build fresh using the documented toolchain:

```sh
JAVA_HOME=/path/to/temurin-11 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew --no-daemon :app:assembleDebugAndroidTest
```

Use a booted, unlocked test device. Always select its serial explicitly:

```sh
adb devices -l
sh testHelpers/device/restart-pairs.sh \
  --serial emulator-5554 \
  --apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --output /path/to/new-results-directory
```

Python 3 is required; no GNU `timeout` or third-party Python modules are needed.
Set `ADB=/path/to/adb` or `PYTHON=/path/to/python3` if necessary.
`--apk` installs once before any tests. Omit
it only when you have already installed a fresh APK yourself; the runner cannot
establish artifact freshness. Save the APK/hash and build log alongside results.
The library test APK instruments its own package, `com.supertokens.session.test`.

- Default: available, cleared, expired-v2, cookie-front-only pairs.
- `--pair available|cleared|expired-v2|cookie-front-only`: select one pair.
- `--warmed-cache`: additionally run `testRealPreferencesWarmedCacheAndConcurrentSnapshots`
  as a standalone test after the selected pairs.
- Output directory must be new. It contains individual instrumentation output,
  force-stop/install logs, device API/fingerprint and completed-pair summaries.
- Every adb invocation has a host deadline: `--command-timeout` defaults to 30
  seconds (preflight, properties, force-stop), `--install-timeout` to 120 seconds,
  and `--test-timeout` to 120 seconds per instrumentation invocation. Positive
  fractional seconds are accepted; deadline values are recorded in `run.txt`.
- Every invocation must report exactly one passing test, matching the selected
  method and class. Zero tests, skips, crashes and failures are rejected even if
  adb exits successfully. Failure leaves logs and device state for diagnosis.

There is no reinstall, uninstall or data clear between seed/check. Each seed
itself replaces the fixture's session preferences; use a dedicated test device.
The runner force-stops only its explicit test package between paired invocations.
The shell entrypoint uses `exec` to become the Python runner, retaining the same
PID. The runner starts each host command in its own process group. On
deadline or interruption it kills only that group, including its attached child
processes, before reaping the leader; it never runs `adb kill-server` or stops the
emulator. Partial output and timeout diagnostics stay in the invocation log.
SIGTERM or SIGINT sent only to the runner PID records cancellation. The command
wait checks it within 0.1 seconds and bounds cleanup reaping to two seconds,
rather than waiting for the configured command deadline. There is no intermediate
supervisor PID to signal. A process group is signaled only before its leader is
reaped, which pins its identity against PID reuse. If cancellation arrives after
a successful wait, the runner exits without signaling that completed command.
No signal is sent to the runner's own process group.
Detached processes, including an adb server, are not group members. Remote
instrumentation may still be running after the client times out: the runner
stops immediately and leaves device state for inspection rather than issuing
cleanup commands. Never run the whole restart class unordered.

## Runtime limits

The current androidTest APK includes live-Core sources and OkHttp/MockWebServer
4.12.0. Selecting restart methods does not remove those dependencies. This runner
does not certify API 17 or the oldest OkHttp runtime; use a compatible-dependency
consumer and actual API17 execution. Preserve failure logs and owned device state.
