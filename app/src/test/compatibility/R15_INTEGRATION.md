# R15 + reviewed XML replay integration — 2026-10-29

Gradle and device lanes: **FREE**. No commits. Debugger-owned legacy-stale patch
was not applied. M4 exception mapping, 7A startup/getter policy, corrected
three-test StrictMode class, and R8 consumer rules are retained.

## Current-tree results

- **412 JVM tests**, zero failures/errors/skips. Includes 20 R15 protocol tests,
  nine raw-review tests, real admitted-flight capacity cleanup, and all **90** XML
  replay/transition/typed-loader/integrity tests against current production.
- **67 published-0.5.4 oracle tests**, zero failures/errors/skips.
- `japicmp`, `verifyOldSdkConsumer`, `apiCompatibilityGateTest`, `assembleRelease` passed.
- Release AAR: **84 classes, all major 51** (Java7 source/target).
- Fresh API34 APK on `emulator-5580`: corrected StrictMode **3/3**;
  four restart pairs **8/8**, plus warmed-cache/concurrent snapshots **1/1**.
- Device commands used the bounded restart runner and its bounded-command helper.
  Installation used `install -r`; no `pm clear` or uninstall. Upgrade harness
  package/cookie state was not reset. This is not a fresh published upgrade run.

## Integration and I/O audit

Three-way reconciliation used staged immutable base, reviewed candidate, and current
working files. The only textual conflict was 7A's already-landed empty-init no-write
change inside `configure`; that policy remains. M4 callback failure handling and
operation-owned event batches remain in the merged coordinator/façade.

- All session adapter calls are confined to `readStorage`, `writeStorage`, and
  `editLegacy`. Each requires W and exactly one S hold, releases S for adapter I/O,
  reacquires S, and verifies the reservation/configuration. W remains held through
  primary persistence, recovery, and publication.
- Configure/reconfigure, install/clear, import, ordinary/sign-out responses,
  refresh completion, raw edits, recovery, and test reset/configuration/storage
  replacement enter W before validation or inherit it. Reads/admission do not take W.
- Every SDK session-key setter in Utils, FrontToken and AntiCSRF routes through
  `editLegacy`; known-key metadata invalidation occurs in the same acknowledged edit.
  Sequential header-helper callback/anti-CSRF ordering is preserved.
- Pre-init raw reads happen outside S and recheck reservations afterward. The newly
  merged 7A front-token fallback also uses this projection, preserving raw decoding
  and exception behavior when no state/reservation exists. Unrelated preference
  reads remain outside S. Cookie-store edits use their separate existing store.
- Literal committed marker projection is separate from numeric generation high-water.
  Promotions expose committed snapshots; replacement/clear/raw persistence exposes
  tokenless transient snapshots. Terminal partial raw state reaches observers without
  an added record write or clearing the partial durable tuple.
- One monotonic budget crosses pending waits/rechecks, pre-start and follower waits.
  Leader transport timeout/cancellation behavior is unchanged. The added regression
  admits nine actual flights through `acquireRefresh`, times each out while an unrelated
  raw write is blocked, then completes a real refresh. Leaking any of those slots
  fails the eight-slot capacity boundary; no unregistered-flight inference is used.
- Observer offers are deferred until outer writer release; callback dispatch remains
  outside mutation locks. Refresh flight completion still precedes legacy callbacks.

## Failures observed and resolved

1. New capacity test initially called a checked-exception token factory inside a
   transport lambda. Prebuilding its response fixed test compilation.
2. XML tests compiled against desktop JSON in isolation but not Android's compile
   API: `JSONObject.keySet()` was unavailable and `JSONException` was checked.
   Tests now use `keys()` and declare the exception. Fixture bytes, hashes, sanitizer,
   provenance, equality assertions and write-count assertions were not changed.
3. Optional warmed-cache device test required AVAILABLE during all replacement I/O.
   R15 intentionally makes that interval readable as tokenless UNAVAILABLE. The test
   now explicitly checks null tokens/front/payload for that state, retaining complete
   AVAILABLE-state checks and every completed-install assertion. Fresh APK rerun passed.

No final failures were waived or allowlisted. The separate legacy-stale correction
still needs its own reviewed rebase and integrated verification. Historical staged
negative evidence remains `review-before.log` (7/9 raw regressions failing), not a
claim about the final integrated tree.

## Release provenance / later upgrade rerun

Evidence root:
`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/m5-r15-staged/integration`

- `app-release.aar` SHA256:
  `c6f709ec800ba004fc804c6f35499d4d696c539f950a512ee65c2bf8c4685e99`
- `classes.jar` SHA256:
  `e636632430bb7f63d5b5ba85d7ad863280f00fac4c3334cf1419df3dcde0ac9f`
- `app-debug-androidTest-final.apk` SHA256:
  `20954155dc0309f3ed7cdfc0aa71bef8b7ee8627d8470cfe65de93c1fd8ad918`
- `provenance.json`, `final-sources.sha256`, `final-source/`, `release-bytecode.json`,
  `final-test-results/`, `final-xml-fixed-gates.log`, `device-contract-build.log`,
  `final-strictmode.txt`, and `restart-final/` preserve source/build/test receipts.
  Initial failed logs and prior APK are retained separately.
- Dirty tree based on `eda605ba8453bc716b82b83c04628a4c0ac4ba3e`; use the manifest
  and archived sources rather than treating HEAD as artifact identity.
- Temurin 11.0.25+9, Gradle 6.5, AGP 4.1.2, compile SDK32. Actual API17 execution
  and the later published upgrade/R8 rerun are not certified by this result.

Final JVM command (Temurin11 / Android SDK environment):

```sh
./gradlew --no-daemon --continue :app:testDebugUnitTest :app:legacy054Test \
  :app:japicmp :app:verifyOldSdkConsumer :app:apiCompatibilityGateTest \
  :app:assembleRelease :app:assembleDebugAndroidTest
```

After the instrumentation-only expectation correction, `assembleDebugAndroidTest`
was rerun, followed by bounded StrictMode and all restart pairs/warmed-cache tests.
