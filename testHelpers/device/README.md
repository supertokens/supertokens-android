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

**API34 published upgrade/rollback passed all 20 stages** after the build/device
lanes were released. See [artifact/backend/device evidence](../upgradeapp/RESULTS_2026-10-29.md).
The linked README has build/device commands, 24 fake-tool test results and recovery limitations. The
preserved M3 restart evidence below allows its shared APK output to be replaced.
The review adds durable logout/failed-refresh checks, an independent backend
revocation check (backend source integrated and activated), required
candidate source/build receipts and explicit `--allow-test-only` handling based
on each actual APK manifest. Actual packaging required no `-t`; API17/R8 remain pending.

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

## Host/device readiness and limits

Read-only inspection on 2026-10-29 found an existing ARM64 `Pixel_8_API_34` AVD,
its API-34 image, and working Hypervisor.Framework support on the arm64 Mac.
No device was connected at that inspection. Booting and running a fresh artifact
are separate actions; the presence of an AVD or old APK is not a test result.

The current androidTest APK includes live-Core sources and OkHttp/MockWebServer
4.12.0. Selecting restart methods avoids live-Core execution, but does not remove
those dependencies. **This runner does not certify API 17 or the oldest OkHttp
runtime.** API 17 actual execution remains a release gap; API 19/21 results cannot
substitute. Use a separate compatible-dependency consumer for oldest-runtime work.

These scripts and documentation are uncommitted implementation work. Actual
device evidence is recorded below; no upgradeapp, StrictMode or R8 certification
is provided by this runner.

## Runner validation — 2026-10-29

`sh -n` passed. Isolated fake-adb checks passed 12 protocol scenarios plus three
argument-rejection cases: all pairs, optional install/warmed-cache, selected pair
with CRLF output, zero tests, skipped test, crash, wrong method, duplicate success,
adb failure, failed restart check, force-stop failure and install failure. They
also checked seed → force-stop → check ordering and absence of cleanup commands.
These checks validate runner behavior, not Android persistence.

Deadline regression checks are reproducible without a device:

```sh
sh -n testHelpers/device/restart-pairs.sh
python3 testHelpers/device/test-restart-pairs.py
```

They cover success and hangs during preflight, property lookup, install,
instrumentation and force-stop, checking bounded exit, retained partial logs,
termination of the owned command/child and survival of an unrelated process.
Cancellation cases send SIGTERM and SIGINT only to the runner PID during a fake
adb hang with a 60-second deadline; they require prompt runner/adb/child exit,
retained logs and survival of an unrelated process.
Additional tests inject both signals immediately after a real child is reaped:
no group signal is issued and no next command is spawned. Protocol checks cover
CRLF success plus wrong-method/class, zero/skipped/duplicate/crashed results.

Subsequently booted the existing `Pixel_8_API_34` without wiping data, on explicit
serial `emulator-5580`; `sys.boot_completed=1` and API 34 were observed within the
120-second readiness bound. That initial readiness check installed no APK and ran
no instrumentation; the subsequent fresh-artifact execution is recorded below.

## Fresh M3 artifact: API-34 device execution — 2026-10-29

**Passed: four restart pairs plus the separate warmed-cache invocation, nine tests
total, zero failures/skips.** No Gradle ran in this execution lane and no commits
were created. The artifact came from the coordinator's completed M3 build of the
integrated uncommitted working tree.

- Serial: `emulator-5580`; AVD: `Pixel_8_API_34`; observed API: **34**.
- Fingerprint: `google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys`.
- Source APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- SHA-256, checked against the supplied M3 hash before installation:
  `062ae810ba89e5e4ec9d3b05d4eea7659dbdd0c86cb3703be7f830437106ee1d`.
- Copied the verified APK into the evidence directory and verified the copy's
  hash again. Installed that copy **once**, using `adb install -r`.
- Executed available, cleared, expired-v2 and cookie-front-only seed/check pairs
  with one targeted force-stop between each seed/check. No reinstall or data
  clear occurred between paired invocations. The warmed-cache test ran separately
  after the four pairs. Runner exit status: **0**.

Evidence directory:

`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/device-restart-api34-pnvu2iuy`

It contains the exact APK, `artifact.sha256`, `provenance.json`, `command.json`,
`runner.log`, and `results/` with every instrumentation/install/force-stop output,
API/fingerprint, run settings and summary. The recorded invocation is equivalent
to:

```sh
ADB=/Users/bogdan/Library/Android/sdk/platform-tools/adb \
  sh testHelpers/device/restart-pairs.sh --serial emulator-5580 \
  --apk "$EVIDENCE/app-debug-androidTest.apk" \
  --output "$EVIDENCE/results" --warmed-cache
```

Each check executes `SessionStorageRestartTest.checkReceipt()`: the saved seed
process UUID must exist and differ from the checking process UUID; status,
adoption ID, configuration ID and all three revisions must match the persisted
receipt. Literal UUID values are not printed by these tests; the passing
assertions, not inferred PID changes, establish their restart guarantee.

The emulator and final test state were left intact. These results certify only
this artifact's selected candidate restart/warmed-cache tests on API 34. They do
not establish published-SDK upgrade/rollback, API 17, StrictMode, or R8 coverage.
