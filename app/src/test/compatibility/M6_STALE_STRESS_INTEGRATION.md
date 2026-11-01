# M6 legacy stale + stress integration — 2026-10-29

**Historical integration result: stress gate RED.** Subsequently resolved by the
[R15 pending-replacement fix](R15_PENDING_LEGACY_FIX.md): 20 integrated nightly seeds
green, full JVM/oracle/API/release gates passed. Original red evidence below retained.
No commits, device work or Core shutdown. Do not interpret focused green as full-suite green.

## Applied

1. `m6-legacy-stale-review/reviewed-legacy-stale.patch`: minimal hunks against the current R15 tree, two production files and nine test files. Includes all ten approved legacy-signout rebaselines and callback exception-identity regressions. Absent-signout admission captures a batch under the existing lock; rejected admission is silent only for the legacy wrapper. Callback dispatch has no stale-exception catch. Strict helper/new API/tagged behavior remains strict.
2. Only `SessionStressTest.java` and FakeBackend's synthetic signout/revocation extension from `m6-stress-faults-stage`. Existing fault tests/oracle were not duplicated or replaced. Stress exception policy is unchanged.
3. Narrow Gradle forwarding for four `session.stress.*` project properties: seed, threads, operations, watchdogSeconds. Production source/target overrides remain Java 7.

Latest crash/lost-refresh tests, consumer R8 rules, CI configuration/docs and the rest of R15 were not replaced. The older proposed/approved stale patches were not applied separately.

## Integrated evidence

Evidence directory:
`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/m6-legacy-stale-integrated/`

* `source-state.json`: source receipt, HEAD plus per-file hashes; **36 production Java files / 68 JVM-test Java files**. Dirty-tree identity is the receipt, not HEAD alone.
* `focused.log`, `focused-results/`: **123 methods, zero failures/errors/skips**, Gradle testDebugUnitTest. Includes all four new regression classes, signout refresh/redirect/cookie/body/transport, stale retry, ownership, state boundaries and LegacyEventContractTest. Public callback identity and B-retention tests pass.
* `stress-pr.log`, `stress-pr-results/`, `stress-summary.txt`: **2 methods / 1 failure**, default seed **1592598566**, 16 × 40 = **640 operations**. All workers/post-run refresh probes completed; capacity method passed. **14 healthy-fixture storage-recovery IOExceptions**, all legacy attempt-refresh/signout; **zero legacy stale exceptions**, no other ledger violations. 405 requests / 46 refreshes including post-run probes; 130 permitted tagged stale outcomes.
* `debug-bytecode.json`: **84 generated debug production classes, all major 51**. No release/AAR/R8 certification claimed by this run.

The red symptom is `Session storage recovery is required`, already reproduced against the untouched current R15 baseline in the earlier isolated review. No errors were swallowed or allowlisted. Separate R15 recovery patch must be reviewed/applied and this gate rerun before claiming green. The corrected `signoutBodyErrorsSurfaceAfterRemovalHeadersCommit` test is included and passes; the obsolete baseline removal-header failure is not an exemption.

## Commands

Use JDK 11 and Android SDK32 with the repository's Gradle wrapper:

```sh
# Default PR mix: 640 operations; prints seed and aggregate ledger to JUnit XML.
./gradlew :app:testDebugUnitTest --tests 'com.supertokens.session.SessionStressTest'

# Nightly configuration: 8,000 operations, same reproducible operation streams.
./gradlew :app:testDebugUnitTest --tests 'com.supertokens.session.SessionStressTest' \
  -Psession.stress.seed=1592598566 -Psession.stress.threads=16 \
  -Psession.stress.operations=500 -Psession.stress.watchdogSeconds=120
```

The seed fixes worker operation streams, not thread scheduling; outcomes/counts are not deterministic replay. Nightly configuration is integrated but was not rerun through Gradle during this handoff. Current-copy isolated nightly was red on the same recovery symptom. Gradle lane released after focused/default-stress execution; no full-suite green claim.
