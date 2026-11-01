# Execution and ownership — 2026-10-29

**Final-audit supersession, 2026-10-30:** [portable local execution](status-2026-10-30.md#portable-owned-entrypoints--reviewed-local-execution) completed mutation6/6 and ordinary/Retrofit121 pass/6 skip each. Earlier blocked/pending assignments and FREE receipts below are historical, not current lane ownership. Agents remain active on [remaining local/external gates](status-2026-10-30.md#active-ownership-and-outstanding-gates).

[Index](index.md) · [Baseline](baseline-2026-10-29.md) · [Decisions](decisions-2026-10-29.md)

## Active ownership

**Latest handoff, evidence dated 2026-10-30:** [Android cookie continuation](status-2026-10-30.md#api34-cookie-continuation--reviewed-execution) completed/reviewed clean, 6/6 stages; its source reports lane FREE. Earlier exclusive execution assignment below is historical; no new lane reservation supplied. Standalone ordinary/Retrofit scoped lifecycle corrections remain **agent staging/blocked, not complete**. Java11 6 pass/2 fail/8 skip is retained separately; Android success does not erase its failures.

**Superseding assignment, 2026-10-30:** **`ses_e74a1f64effefDmY09fmdN8GHe` owns sole Gradle/backend lane** for failure-safe cleanup, live continuation and full ordinary/Retrofit execution; results **PENDING**. Earlier free-lane/M7-application assignments below are history. [Current verified status](status-2026-10-30.md) records clean independent review and actual four-lane matrix completion; standalone compilation is not new suite execution.

**Current assignment, 2026-10-29:** legacy/stress and healthy-recovery fix integrated/reviewed clean; Gradle **FREE until M7 docs application, validation author next**. M7 agent applying README/CHANGELOG/SESSION_OWNERSHIP/version 0.6.0, not yet done. Final coverage audit active/read-only; backend healthy. New device-death bundle is host-tested, review pending, no device execution. Earlier transfers below are historical; [integrated evidence and remaining gates](upgrade-qualification-2026-10-29.md#current-verification-and-staged-risks). No new validation-author session ID supplied.

- **Foundation documentation agent:** exclusive write scope `docs/compatibility-wiki/**`. Deliver dated index, baseline, execution and decision records. Source plans, production, tests and CI inspected read-only. No commits.
- **Backend/CI agent**, formerly fixture agent (`ses_e78445694ffeCrN002qtkwi679`): latest 2026-10-29 assignment adds `.circleci/setupAndTestWithFreeCore.sh` wiring qualification and its gate into `test:core`/matrix execution. Pair-qualification source fix passed review; medium CI consumption gap remains. Server/readiness work stays separate from JVM fixtures. See [live evidence](baseline-2026-10-29.md#activated-backend-cleanup-and-coverage).
- **Import/rollback agent**, formerly oracle-only (`ses_e78448ed1ffesylJ70UuHlkgEn`): following characterization and user approval on 2026-10-29, now owns `SessionIdentity.java`, `SessionStorage.java`, `SharedPreferencesSessionStorage.java`, storage methods in `SessionState.java` under `app/src/main/java/com/supertokens/session/`, and import/rollback tests. Harness ownership retained. Coordinate `SessionState` method boundaries with the R5 owner; this does not grant unrelated transport/configuration edits. See [characterization evidence](baseline-2026-10-29.md#oracle-characterization-and-current-slice-reports).
  Latest handoff, 2026-10-29: M4 request parity merged/reviewed clean, 285 JVM/47 oracle/7 live/API green, 79 major-51 classes. Lane released; see [latest handoff](m4-m5-upgrade-2026-10-29.md#active-m4m5-work).
- **Fixture/foundation reviewer** (`ses_e783d6941ffek9kaF7BpQxnoi0`): read-only P2 finding reported resolved on 2026-10-29 by fix session `ses_e783a9783ffeL8VHNCdMInMyoJ`; see [resolution and fixture limits](baseline-2026-10-29.md#fixture-p2-resolution). No ongoing fixture implementation assignment is recorded here.
- **R5 init/configure agent** (`ses_e7840d132fferIuAlb3qsvqZ79`): R5 reported complete with clean read-only review by `ses_e783384f7ffeZvbdwXYSD1A6Oh` and 30 targeted tests passing on 2026-10-29. Broader concurrent results are not authoritative. Init/configure scope remains separate from the import agent's storage-method scope.
- **M4 event agent**, formerly R17 (`ses_e783a4b80ffeSjxbolwPrlx3z5`): corrected R10 complete, clean review by `ses_e77d80f20ffeGpuPVcnkZPVp9i`; 278 JVM/44 oracle/API green, public follower coverage. Lane released to request parity. [Exception provenance](m4-m5-upgrade-2026-10-29.md#r10-corrected-integration) supersedes erroneous coordinator no-wrapping guidance.
- **M5/legacy agents:** latest legacy/stress + healthy-recovery integration **437 JVM/71 oracle/API/Java7**, 640 + 20 × 8,000 stress operations green; prior blockers closed, clean review `ses_e77b97b01ffeYLuHmzLT6BWBs1`. Earlier API34 3/3, 8/8, 1/1 qualify the earlier artifact only. Old StrictMode 2/2 stays invalid history; [evidence](upgrade-qualification-2026-10-29.md#current-verification-and-staged-risks).
- **M3 scope/configuration agent** (`ses_e7816e0ebffeJVAbgd0O8MO2iD`): integration applied/reviewed, including strict patch and blocked-transfer-hook flight regression; 131 targeted, 261 JVM/39 oracle and 7/7 restored live green. Lane released to M4; [source evidence](m2-m3-handoff-2026-10-29.md#m3-integration-and-m4-staging).
- **Device/upgrade agent** (`ses_e7840d132fferIuAlb3qsvqZ79`): API34 upgrade/rollback 20/20; independent audit `ses_e77d48047ffeSXs6rZ0L0FQejI` supports recorded completed-write scenarios/current pair. No active device ownership. Separate restart 9/9 remains valid; [provenance limits](upgrade-qualification-2026-10-29.md).
- **API gate agent** (`ses_e7840d132fferIuAlb3qsvqZ79`): gate, regeneration/freshness and smoke verified; clean review/minor README correction reported. Gradle lane released to runtime; see [API gate status](api-runtime-gate-2026-10-29.md).
- **Runtime compatibility agent** (`ses_e7816e0ebffeJVAbgd0O8MO2iD`): applied patch complete; 233 JVM/36 oracle green, API gates and seven fresh-artifact probes passed. Lane released to M2. Owned-transport constructor requires OkHttp 3.14+ without raising the legacy floor. Review by `ses_e780086eeffeABhsUZF5dYVJKg` and API17/historical-consumer/R8 verification remain pending.
- **R1 reviewer** (`ses_e783b68e9ffe3HDSb9Kwmz02Zj`): read-only re-review reported no issues after the cookie fix on 2026-10-29. Narrow request/login slice is complete and reviewed; see [evidence](baseline-2026-10-29.md#cookie-recovery-fix-and-re-review). Overall import work remains partial and device persistence unverified.
- **Coordinator:** resolve any shared helper/build-file ownership before edits, collect implementation agents' actual file scopes and test evidence, and assign the remaining work. This page does not expand their assignments.

Backend reviewer `ses_e783420dbffeof0xUkWXCzrkNM` raised the earlier nonblocking cleanup concern; cleanup routes have since been activated and HTTP-verified. [Coverage/version limitations](baseline-2026-10-29.md#activated-backend-cleanup-and-coverage) remain explicit.

Latest **2026-10-29** upgrade qualification activated Express76371/Core76687 with route smoke/cleanup/evidence gates; this supersedes the earlier restart-pending state. SDK16.7/Core8 still reports `LINK_REVOKED_SESSION` unsupported, not R7 success. Linux historical CI pending; [API34 qualification](upgrade-qualification-2026-10-29.md) is artifact-specific.

Assignments and explicitly labeled completion reports above were relayed on 2026-10-29; completion does not imply review approval. Wiki ownership transfers back to the coordinator on documentation handoff; concurrent agents should send evidence to the owner rather than edit these pages during this task.

## Gradle execution ownership

**Latest 2026-10-30 handoff:** cookie-continuation report releases its lane after execution; standalone lifecycle correction is staging, no completion or new execution reservation reported. [Current boundaries](status-2026-10-30.md#active-ownership-and-outstanding-gates) supersede earlier assignments below.

**2026-10-30:** exclusive owner `ses_e74a1f64effefDmY09fmdN8GHe`; [current work/results boundary](status-2026-10-30.md#active-ownership-and-outstanding-gates) supersedes the dated transfers below.

Latest coordinator instruction, **2026-10-29**: **Gradle FREE until M7 documentation/version application; validation author next**. Debugger/R15 integration is complete, stress blockers closed. Backend healthy; no new device run reported. Latest-artifact upgrade/R8/full testapp/API17/Linux and check administration remain pending. [Approved legacy outcomes](decisions-2026-10-29.md#user-approved-legacy-stale-outcomes) and strict guards are unchanged.

## Recommended execution slices

Latest follow-up **2026-10-29**: prior 496/497 red stage superseded by integrated green legacy/recovery gates. M7 0.6.0 application underway, not complete; preserve unsupported old-generation overload documentation. Device-death bundle: 12 host passes/Java7, review pending, unexecuted on device. Read-only final coverage audit active; [scope and remaining gaps](upgrade-qualification-2026-10-29.md#partial-writes-and-lost-refresh-response).

Follow [implementation batches](../../COMPATIBILITY_IMPLEMENTATION.md#suggested-commit-boundaries-and-dependencies); each slice ends with a checkable handoff:

1. **0A baseline:** choose the installed JDK 11, set SDK location, run JVM baseline, then restored testapp cases with a ready backend. Done when commands, dirty-tree state, named failures and environmental blockers are recorded. Coordinate assertion restoration separately from fixture/oracle writes.
2. **0B fixtures and 0C oracle, parallel:** agents retain their assigned files. Done when helpers support pre-init/restart/failure scenarios and oracle smoke proves classloader isolation, published checksum, login/refresh and reopen. FakeBackend remains an emulation until relevant behaviour is checked against live Core.
   Update 2026-10-29: 0A/0B/0C are reported complete and the fixture P2 finding resolved, with scoped results in the baseline. Broader oracle characterization and backend readiness remain ongoing; this does not mark every M0 exit criterion complete.
3. **Import/rollback safeguards reviewed; JVM integration green (batches 1–2):** R2/R4 controls and R17/resource bounds pass after reviewed rebaselines. Preserve [restart/raw-write constraints](migration-findings-2026-10-29.md); device persistence and broader release criteria remain unverified.
4. **Response correction and scope/configuration integrated (3–4):** strict patch and requested transfer-hook regression applied/reviewed with green JVM/oracle/API and restored live tests; see [M3 evidence](m2-m3-handoff-2026-10-29.md#m3-integration-and-m4-staging). Results remain scoped to the recorded working tree/backend pair.
5. **Events/request parity merged (5–6):** clean review and reported integrated JVM/oracle/live/API gates; retain caller-specific exception/post-state and cleanup contracts.
6. **Read-only/pre-init, startup monitor and R15 locks integrated (7A/7B):** latest 412 JVM/67 oracle/API and API34 3/3 + 8/8 + 1/1 provide scoped evidence. Original StrictMode 2/2 remains invalid. Changed artifact still needs final R8/upgrade reruns; no general durability certification.
7. **Coverage/gates and release (8–9):** infrastructure scaffolding can begin after foundation; final stress, device rollback and FDI runs use the completed implementation. Done only with demonstrated gates, required-check administration and reconciled release documentation.

## Evidence handoff

For every fixed regression, retain the named pre-fix failure and post-fix result, affected D/F identifiers, command and toolchain, commit plus uncommitted changes, and report path. Classify dependency/toolchain/backend failures separately from semantic failures. An unexecuted test is pending, not green. Use the [decision register](decisions-2026-10-29.md) before accepting behaviour that differs from the source plan.

## Proposed milestone-end wiki lint checklist

Manual checklist proposed 2026-10-29; no automation or scheduled job is implied.

- Every page appears in the categorized index with a one-line summary; local links and anchors resolve.
- Claims carry dates and source sessions or repository/commit evidence; uncommitted results are identified explicitly.
- Results include exact command, environment, counts and artifact paths when supplied; missing evidence stays visible.
- Superseded observations point to their replacement without expanding its verification scope.
- Ownership and unresolved decisions match the latest handoff; proposals remain distinct from approvals.
- Source documents remain linked, and duplicated or stale conclusions are reconciled.
