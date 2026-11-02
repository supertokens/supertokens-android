# R15 pending-replacement legacy boundary fix — 2026-10-29

**Integrated. Gradle lane FREE. No commits or device operations.**
Supersedes the recovery-error stress blocker in
[M6 stale/stress integration](M6_STALE_STRESS_INTEGRATION.md).

## Root cause and exact interleaving

1. A writer validates installation of B, invalidates A, and exposes a fresh current
   owner with a tokenless `UNAVAILABLE` snapshot.
2. R15 reserves persistence and releases S while retaining W. Healthy storage is
   deliberately blocked before acknowledgement in the deterministic regression.
3. Public `attemptRefreshingSession` or `signOut` captures that temporary owner.
   It is current, so this is **not** the legacy-stale bug.
4. `resolutionTerminalLocked` treats every non-AVAILABLE/non-ABSENT current state
   as `RECOVERY_REQUIRED`, attaching `IOException("Session storage recovery is required")`.
   The two legacy entry points throw it before the healthy write can finish.
5. Releasing storage succeeds and publishes B. No persistence failure occurred.

The copied reviewed candidate reproduced six such errors in 640 operations with
zero stale leaks. Before the fix, two deterministic healthy-blocked-write tests
fail with that exact IOException; genuine recovery controls already pass.

## Narrow fix / policy

State tracks the owner of its transient replacement view separately from terminal
partial-raw `UNAVAILABLE`. Terminal partial publication clears that marker. Under S,
resolution classifies a pending replacement only when all three hold:

- current status is UNAVAILABLE;
- a mutation reservation exists;
- current owner is the marked transient owner.

The immutable internal `SessionResolution.pendingReplacement` flag carries that
classification to the caller without a racy second state read. Public attempt-refresh
returns false and legacy signout returns without wire traffic, mutation or events.
This is a credentialless legacy projection under R1/D5, not recovery success, a
transport retry under B, or an exception catch/allowlist. A failure that occurs after
that observation is still reported to its producing writer and subsequent callers.

Explicit resolution retains its RECOVERY_REQUIRED result/error. Tagged handling is
unchanged. A genuine RECOVERY_REQUIRED state never gets the flag, even during a
blocked recovery tombstone commit. Terminal partial raw state does not get it even
when an unrelated write is pending. Own-operation commit failures propagate unchanged.

Only three production files changed: SessionState, SessionResolution, SuperTokens.
No public enum/signature changes. RefreshCoordinator, LegacyEventBatch and
SessionTransport are byte-identical to the reviewed dependency. Absent-signout
callback admission/provenance logic is untouched; all ten legacy rebaselines remain.

## Deterministic controls

`LegacyPendingMutationTest` has five methods covering:

- Attempt-refresh false / signout no-op during blocked install-B, clear,
  reconfigure and raw edit. Calls finish before writer release; zero wire requests
  and callbacks; old owner retired; getters export no credentials or payload.
  After install completes B is intact; the calls did not borrow or sign out B.
- Explicit resolve and tagged response application remain fail-closed while pending.
- Terminal partial raw state remains an error after completion and during an
  unrelated blocked write.
- Failed primary install remains genuine recovery during and after blocked tombstone
  persistence; original install error is retained.
- Producing legacy refresh/signout still throws the **exact injected IOException
  instance** when its own persistence fails (including failed recovery persistence).

The existing callback-created stale-exception identity/B-retention tests pass.
No stress exception policy, fixtures, or existing assertions were weakened.

## Actual integrated gates

- Focused blocked-writer, callback, R15 and default **640-operation** stress passed.
- **20 nightly seeds × 8,000 operations = 160,000 operations**, seeds
  `1592598566` through `1592598585`, 16 workers, 500 operations/worker.
  Every run: zero ledger violations, two passing methods including capacity cleanup,
  zero failures/errors/skips. Seed and operation counts verified from each XML.
- Final full Gradle JVM suite: **437 tests**, zero failures/errors/skips.
- Published oracle: **71 tests**, zero failures/errors/skips.
- `japicmp`, `verifyOldSdkConsumer`, `apiCompatibilityGateTest`, `assembleRelease` pass.
- Release: **84 classes, all major 51**, Java7 source/target.
- Isolated staging evidence was separately **502 tests**, plus 8,000 and 80,000
  stress operations. Those numbers do not replace the integrated counts above.

No failures waived. Thread schedules are not deterministic even with fixed operation
seeds; these are bounded stress results, not proof of every interleaving.

## Evidence and artifact

Root:
`historical external evidence: r15-pending-legacy-followup`

- `base/`: reviewed legacy-stale candidate dependency; `candidate/`: staged fix.
  The three production base files matched the newly integrated shared tree exactly.
- `pending-replacement.patch`: four-file dependent patch (three production files,
  one test), SHA256 `9fc9e688cfbe4daa1fdaa32446af70f368ece1d88553d9e335ff541b31bda367`.
  It applies **after** `m6-legacy-stale-review/reviewed-legacy-stale.patch`, not instead
  of it. `patch-sources.json` pins inputs/outputs. Applied as minimal hunks.
- `initial-stress.log`, `pending-red-compiled.log`, `pending-green.log`,
  `full-green.log`, `stress-8000.log`, `stress-80000.log`: isolated red/green evidence.
  `pending-red.log` separately records the initial test-lambda compilation correction.
- `integrated/focused-640.log`, `focused-640-results/`, twenty `nightly-<seed>/`
  directories, `nightly-summary.json`, `run-nightly.py`, `full-gates.log`,
  `final-results/`: actual Gradle commands, logs, XML and bounded runner.
- `integrated/provenance.json`, `source-hashes.json`, `source/`, `bytecode.json`,
  `app-release.aar`: fresh dirty-tree source/build/artifact receipts.

Fresh release AAR SHA256:
`09d77aaef295b73707ab47764e269ffe86dd412628f75d702dc3bf144a3e05c0`

Classes JAR SHA256:
`9d5eedff63295afb188248b9555996e58a175898dbb4baf2c10faede0c34be08`

Toolchain: Temurin 11.0.25+9, SDK32, Gradle 6.5 / AGP 4.1.2. Dirty HEAD
`eda605ba8453bc716b82b83c04628a4c0ac4ba3e` is not the artifact identity; use the receipts.
Earlier device/R8/upgrade results do not certify this new AAR.
