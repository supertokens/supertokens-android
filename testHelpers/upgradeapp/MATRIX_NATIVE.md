# Native matrix upgrade producer and verifier

Opt-in coverage: COOKIE/EXPOSED_COOKIE × REFRESH/SIGN_OUT, actual `/login`, unmodified published 0.5.4 then candidate. Existing continuation scenarios and `--scenario all` retain their behavior. The device subreceipt is not independently release acceptance; the fixed archive verifier derives acceptance facts from device, host and server evidence.

## Coordinator lifecycle

Use existing `tools/upgrade.py` (or `../device/upgrade.sh`) with the four APK paths, candidate AAR SHA/build provenance, explicit serial/backend/new private output, plus:

```
--scenario matrix-native --matrix-config /absolute/input.json
--run-id <owned-runId> --device-lease-root <canonical-precreated-0700-directory>
```

Use a device loopback origin `http://127.0.0.1:<port>`. Host context backend is also explicit loopback HTTP. Producer owns `adb reverse --no-rebind` for those ports; existing mappings at that device port are rejected. Other mappings/apps are preserved.

1. **Before JVM fixtures:** create first native row's backend control/challenge using the supervisor, and run full producer argv with `--preflight-only --output <owned>/native-device-preflight`. It checks tools, artifacts, provenance, device/process-query availability, prior fixture ownership and routing; acquires the exclusive serial lease. No install, instrumentation or backend request. Exit 0 retains lease/routing and writes `native-preflight.json`.
2. **JVM suite:** preserve actual failures. Device lease remains held throughout. Do not dispatch native rows unless JVM classification permits them.
3. **Each native row:** full argv plus `--device-lease-session <owned>/native-device-preflight --output <JVM receipt>/native/<nativeNonce>`. Each row has its own nativeNonce/challenge/flow, but the original `leaseNonce`. Borrowing checks live owner bytes/inode and atomically acquires `borrower.json`. No duplicate lease acquisition. After scoped cleanup, force-stop/drain only fixture packages, retain routing/selector lease, write `native-device.json`.
4. **Finalize that backend row:** supervisor calls `/test/native/finalize` only after native child drain, then copies server `events.jsonl`/`complete.json` to sibling `<JVM receipt>/native-backend/<nativeNonce>`. Never add them to the exact device manifest.
5. **Coordinator finally:** original preflight input/full argv plus `--device-lease-session <preflight-dir> --release-lease-only --output <owned>/native-device-close`. Checks no unresolved app owner, force-stops/drains fixture packages, restores routing, releases exact lease and writes `native-lease-closed.json`. This also applies when JVM fails or no native rows are needed. Failed original runs stay failed. Unknown creations/owners or interrupted live borrower prevent release and require reconciliation.
6. Verify all native rows **after shared lease close**. This may precede outer Core/backend shutdown. Final release export rechecks unchanged original/sibling backend evidence and requires existing outer cleanup proof.

All output directories must be new. Never clear app data/cookies, uninstall, delete retained lease markers or retry ambiguous fixture creation to manufacture success. An unreconciled `borrower.json` after abrupt process death blocks subsequent borrowers and automatic release; handled failures drain their own commands before releasing the borrower slot.

## Input schema

`schema:1`, `kind:"matrix-native-input"`:

- Exact `mode`, `flow`, `route:"/login"`.
- `runId`, `instance`, parent JVM `preparationNonce`, per-row `nativeNonce`, preissued `challenge`, shared `leaseNonce`: lowercase 48-hex. `leaseNonce` is the first preflight nativeNonce. Never include finalizeKey.
- `deviceBackend`: normalized command-line origin; `candidateAarSha256`; pinned `publishedAarSha256`.
- `qualifiedIdentitySha256`, `dependenciesSha256`, `nodeSdkSha256`, `coreSha256`, `coreManifestSha256`, `executionSourcesSha256`, `planSha256`.
- `context`: release sha/workflow/attempt/nonce/fdi/sources/policySha256/canonical host backend/core.
- `plan`: review reference, exact nodeVersion/coreVersion, row-scoped `phases` (`publishedLogin`, `publishedHandoff`, `candidateRefresh`). Present versions are strings 2–5; SIGN_OUT handoff is explicitly `"ABSENT"`. No historical scalar inference.
- `toolchain.apksigner`: `{path,sha256,jarPath,jarSha256}`, obtained with `native_device.signer_identity(executable)` from the actual discovered wrapper and sibling `lib/apksigner.jar`. Producer checks the pin before execution; verifier independently invokes that pinned tool against archived APK bytes. Coordinator collector supplies the trusted expected pin, not a receipt-selected executable.

`tools/test_matrix_native.py` contains synthetic examples, not approved historical plans.

## Instrumented semantics

Published class `MatrixNativePublishedTest#runStage`, candidate class `MatrixNativeCandidateTest#runStage`, shared `MatrixNativeStage`:

- Isolated unmanaged challenge request validates actual backend-owned identity. Isolated `/test/email-change-owner` allocation persists pending request before dispatch and fixture ownership before remaining response parsing. Its cookies never reach the SDK store.
- SDK-managed `/login` uses that fixture user, selected FDI and `x-st-native-fixture-id`. Captures actual `x-st-native-session-handle` without a protected request.
- Observe login token version, immediately invoke published refresh OR signout, then observe/persist handoff. No intervening protected call. Published signout checks durable local absence and real Core absence for the captured login handle.
- Candidate process checks uid/access/payload/cookie continuity or absence. After SIGN_OUT it logs in the same fixture user through `/login`, capturing the new handle.
- Protected `/` → owner capture → refresh/version observation → same owner → protected `/` → `/update-jwt` → same owner, exact payload and one payload-update event → final signout/local absence/actual login-handle revocation.
- Separate cleanup process performs independent known-owner cleanup, refuses pending creation, flushes persistence. Existing `CleanupRecovery` preserves original failures.

Explicit requests and SDK internal callbacks supply FDI/run/native-nonce/stage headers. Ordinary managed requests use native URLConnection and persistent Android cookies; the JVM harness's ordinary OkHttp requests/persistor differ. The selected internal SDK refresh/signout remains unmodified. Client operation/callback records are never mistaken for wire evidence; verifier checks actual server observer records.

## Artifacts and receipts

APK code authority is external to assets: the existing `tools/build_provenance.py` producer now has a `--native-apks` mode. With an already-produced `--candidate-aar` and `--candidate-build-provenance`, it runs the four fixed debug app/test assembly tasks through the root wrapper, with clean, rerun-tasks and no-build-cache. It verifies candidate receipt/hash, uses Gradle's checksum-verified published dependency, checks unchanged source/AAR inputs before and after compilation/archive, and saves `native-build.json`, `build.log` and four APKs. The receipt binds compilation sources/resources/wrapper, command/interval/exit, candidate producer receipt, both AAR hashes, log digest and actual output APK hashes. This mode is implemented but has not been executed here.

The sole executor independently pins the actual producer's `native-build.json` path/hash in trusted configuration. Every producer invocation requires `--native-build-receipt <absolute-path> --native-build-sha256 <executor-pin>`; verifier expected field `nativeBuild:{path,sha256}` must come from that configuration, never from APK assets/device receipts. Preflight and export re-read the pinned compilation receipt, log and original four outputs and compare exact bytes. Replacing DEX while retaining class names/current assets, or re-signing an APK, fails that external output binding. Embedded assets and DEX inventories are additional consistency checks, not code authority.

Fresh non-R8 test APK builds embed `assets/native-test-provenance.json` through `generateNativeTestProvenance`. It hashes actual upgradeapp Java/manifest/build inputs; stale instrumentation APKs fail preflight. Existing candidate SDK build receipts remain required. Both test APKs must contain zero SDK implementation classes; verifier inventories actual DEX definitions.

`native-device.json`: schema1/kind matrix-native-device, status device-assertions-passed, `releaseAccepted:false`, exact binding, actual startedMs/endedMs, ordered seed/check-initial/cleanup stages, hashes of all other output files. Preflight/close summaries use schema1/kinds native-device-preflight/native-lease-closed with binding, times and file hashes. They are not scenario results.

Stages contain exact binding/flavor/process, assertion/cleanup results, actual schemaVersion2 token phases, client operations/header callbacks, actual challenge/revocation responses and hashes of installed app/test package code paths. Device-only `wireVerified`, `backendIdentityVerified`, `loginRevocationVerified` remain false: authority is derived by comparing server records, not by trusting these flags.

`native-commands.jsonl` records actual argv/output hash/exit/time and process-group observations. Host command leader remains unreaped while group members are drained; only owned groups are signalled, then the leader is reaped. Final package-specific force-stop plus pidof proves absence of fixture writers. `device-lease-owner.json`, `device-borrower.json`, `device-lease.jsonl` bind acquisition, exclusive borrow, row completion and release.

Both app and instrumentation installation state are inspected before claiming either package. An orphan instrumentation package rejects; an installed instrumentation APK must match one of the owned test APK inputs, in addition to the app's resolved owner receipt. Failure to inspect either leaves both unclaimed and prevents install/force-stop. Host cleanup unconditionally kills the still-pinned owned group and waits even when ps observation fails. `nativeArchive.js` uses `native_verifier_process.py` to enforce its deadline, handle cancellation/parent loss, and drain Python plus signer descendants as one group before reaping.

Darwin ps parsing follows Apple's [Mach state table](https://github.com/apple-oss-distributions/adv_cmds/blob/main/ps/tasks.c) and [state/modifier formatter](https://github.com/apple-oss-distributions/adv_cmds/blob/main/ps/print.c): `?` is unresolved Mach state, not absence/death. Only the narrow platform state/modifier grammar is accepted. `?` must progress to an observed `Z`; persistent unknown fails drain. Tests cover transient unknown, persistent unknown, malformed zombie strings and repeated observation failure. The old staged failure did not capture its exact state, so its specific value cannot be retrospectively established; the previously failing archive-path test passes with this source-grounded handling.

Private preferences/APKs/handles remain private. Failed runs emit no passing summary; partial artifacts remain diagnostic/recovery evidence only.

## Fixed verifier interface

```
const { verifyNative } = require('./testHelpers/upgradeapp/tools/nativeArchive');
const facts = verifyNative(row, expected, directory);
```

Envelope matches composite schema2/kind matrix-native-upgrade. `rawEvidenceSha256` hashes raw `<directory>/native/<executionNonce>/native-device.json` bytes; times/observations come from it.

Trusted `expected` additionally requires `nativeBuild`, `nativeSerial`, `nativeLeaseRoot`, `nativeTools`, `nativePreflight`, `nativeLeaseClose`, `jvmStartedMs` (actual JVM terminal start), plus existing owned/binding/plan/preparation/window fields. Collector must reconstruct these from trusted config and fixed owned paths. No caller-selected verification function or verified boolean enters this export. The no-native-row lease validator must also call `native_archive.verify_compilation(preflightDirectory, inspectedPreflight, expected)` so all-JVM success cannot bypass compilation authority.

Verifier independently rechecks archive hashes, paths, current/embedded source manifests, APK provenance/installed bytes/DEX/signatures, exact raw instrumentation and argv, process groups, retained pre-JVM lease intervals, app ownership, routing restoration and device writer absence. Backend verifier reads the concrete server schema (integer request IDs), compares sibling bytes with original owner files/control, checks actual identity/challenge/FDI/exact request order/login ownership/revocation/cleanup, and rejects late mutation or unresolved work. Supervisor finalization is correctly allowed after device endedMs, within parent window.

On complete valid evidence it returns derived `{binding,executionNonce,rawEvidenceSha256,deviceCleanup,workersDrained,pendingCreations,ownerReceiptsResolved,backendEventsSha256}`. Missing/invalid required input throws `NATIVE_ARCHIVE_INVALID`; there is no unconditional rejection and no waiver path.

## Qualification limits

Implementation/verification is host-only: fake-device protocol controls, real owned host subprocess tests, actual backend observer/fixture-owner modules with explicit SDK/request doubles, isolated javac against existing artifacts. New Gradle asset task, fresh APK packaging and all real device/backend/historical tuples await the sole executor and reviewed plans. Existing API34 archives cannot satisfy this contract. Outer cleanup/publication gates and preserved JVM failures remain the composite owner's responsibility.

Independent-review fix validation: 82 host tests passed in 261.558s, including the existing build producer with explicit compiler doubles, externally bound APK mutations, both-package ownership and process supervision. No fresh compilation or live qualification is implied.
