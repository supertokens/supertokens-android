# Candidate API34 process-death scenarios

Staged and executed 2026-10-29. **Both real API34 scenarios passed on fresh 0.6.0.**
See [execution results and artifact hashes](PROCESS_DEATH_RESULTS_2026-10-29.md).
Sixteen host protocol tests and isolated Java 7 compilation also pass.

## Boundary and ownership

`ProcessDeathTest` lives in `src/androidTestCandidate`, so published 0.5.4 builds
do not compile candidate-only snapshot APIs. Each invocation selects one stage.
It reuses upgradeapp's candidate APK, private SharedPreferences, real Core routes,
and ownership-aware fixture deletion. No backend or Gradle configuration changes.

Each scenario starts with an absent SDK session and creates one mapped fixture
using a raw client. Its fixture ID and user ID are committed to `death-receipt`
before any intentional kill is allowed. The later `/login` requests only create
sessions for that already tracked user; scoped owner cleanup removes all of that
user's sessions, including the response-discarded login session.

- **Login:** seed remains ABSENT. Hold `/login`'s real successful response in an
  OkHttp network interceptor, inside the SDK application interceptor. Verify access,
  refresh and front headers exist and SDK snapshot/preferences remain unchanged.
  After force-stop/restart, require the old persisted tuple and ABSENT; a later
  login must succeed, protected access must return the expected user, and refresh
  plus a second protected request must succeed with the same session handle.
- **Refresh:** seed logs in and verifies real protected access. `/throw-401` returns
  the existing backend's intentional 401, triggering the SDK interceptor's normal
  refresh transport. Hold `/auth/session/refresh`'s actual Core 200 response, verify
  it used the retained parent and returned a different refresh token. Do not use
  child access or return the response to the SDK. After force-stop/restart, require
  the unchanged persisted tuple and AVAILABLE; SDK forced refresh must recover via
  real Core parent grace. Protected identity/handle and another refresh/protected
  request must succeed. There is no synthetic rotation or fallback login here.

The network interceptor commits a receipt and atomically publishes/fsyncs
`files/death-checkpoint.json`: run, scenario, stage, phase, process UUID, PID only.
It then blocks for up to 180 seconds; watchdog expiry throws and is a failure.
**Guarantee: network response headers received, before SDK response adoption.**
It does not prove interruption during SharedPreferences apply/commit, body decoding,
or a particular filesystem write. Core parent grace is tested before child use.
R7 account-linking unsupported qualification remains separate.

## Host protocol

`tools/process_death.py` imports the existing upgrade/restart runner helpers. It
requires an explicit serial, API34, candidate build provenance, and exact SHA-256
equality between the supplied APKs and both already-installed APKs. It performs
no builds or installation. A lane owner must install candidate app/test APKs with
`adb -s SERIAL install -r ...` first; preserve data. Never uninstall or `pm clear`.

Stages are seed → hold/kill → check → cleanup, each in a new target process.
All normal stages require the existing strict exactly-one-pass parser. The held
instrumentation invocation is deliberately **not** passed through that parser:

1. Start an owned host adb instrumentation process; poll checkpoint for 30 seconds.
2. Match run/scenario/phase, receipt/process UUID, known fixture and exact live PID.
3. Require instrumentation still running immediately before target-only force-stop.
4. Require target PID absence and owned host command termination within 30 seconds.
5. After the owned adb child exits, inspect its final transcript. Reject completed
   JUnit statuses (including 0, -1 and -2), `FAILURES!!!`, normal success, and explicit
   hold/watchdog failures. Legitimate force-stop `Process crashed` output alone is
   allowed. Only then record `EXPECTED_DEATH_ONLY`, never a test pass.
6. Require the subsequent normal check and cleanup to pass before writing
   `DEATH_AND_RESTART_PASS`.

ADB probes are individually bounded (15 seconds); normal instrumentation is
bounded at 90 seconds. Poll deadlines can overrun by a bounded in-flight probe.
Timeout/cancellation reaps only the owned host command group. The protocol proves
a controlled checkpoint, a matching live-process observation, an explicit stop,
and subsequent absence; it rejects known failures in the final transcript. The
observation-to-stop race cannot fully prove external kill causality: an unreported
death in that window can be indistinguishable from force-stop. Report this limit
rather than claiming all arbitrary deaths are excluded. Stale checkpoints,
replacement PIDs observed at the probe, reported watchdog failures and failed
restart checks are rejected. Failure preserves device data/receipt. No global reset,
backend lifecycle operation, token logcat collection, or broad process kill.

Output directory is new, umask 077: run/artifact provenance, checkpoint and kill
proof, normal instrumentation logs, private typed session/receipt XML captures.
Session XML contains credentials: keep the entire directory private.

## Handoff execution (only after lanes are granted)

1. Apply the staged patch. Build candidate upgrade app and test APKs through the
   existing verified AAR/provenance Gradle invocation, using candidate variants.
2. Check exact-pair backend readiness/account-linking qualification; keep the same
   fixture-owning backend process alive throughout seed/check/cleanup.
3. Install both candidate APKs with explicit serial and `-r`; inspect testOnly and
   supply `-t` only when required. Ensure prior upgrade/death fixtures are cleaned
   and SDK session is absent without clearing app data.
4. Run:

```sh
python3 testHelpers/upgradeapp/tools/process_death.py \
  --serial "$SERIAL" --backend http://10.0.2.2:8080 \
  --app-apk "$CANDIDATE_APK" --test-apk "$CANDIDATE_TEST_APK" \
  --output "$PRIVATE_NEW_DIRECTORY" --run-id "$RUN_ID"
```

Expected actual execution: two verified deaths, two passing restart checks, plus
two seed and two cleanup passes. Do not count held invocations as JUnit passes.

For failure recovery, retain the original run/backend and inspect private
`death-receipt`. When fixtureId is known, rerun with `--cleanup-only`, original
`--run-id`, one `--scenario login|refresh`, and a new private output directory.
Cleanup is scoped/idempotent. If seed's creation response was lost and
`pendingRequestId` remains, do not retry seed or guess an ID: ask the backend owner
to resolve that exact run/scenario email/request while preserving its owner map.
No owner-creating request occurs in an intentionally killed stage.

Host-only verification:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 testHelpers/upgradeapp/tools/process_death_test.py -v
```

Sixteen tests include adversarial checkpoint/receipt/PID states, early death,
bounded missing checkpoint/ineffective kill, missing host exit, strict normal-pass
parsing, typed receipt parsing, and explicit-serial/target-only force-stop argv.
Stop-window adversaries emit JUnit failure, success or watchdog output after the
last liveness check and before stop; all are rejected. A valid controlled stop
with `Process crashed` output remains accepted as expected death only.
