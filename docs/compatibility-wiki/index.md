# Compatibility wiki

Established **2026-10-29**; latest completed evidence **2026-10-30**: [integrated signout fix](status-2026-10-30.md#latest-integrated-signout-fix--fresh-artifact-qualification), independently reviewed, **2,920 passing JVM executions** and **38 passing API34 stages** (only **two actual minified R8 scenarios**, plus setup/cleanup). Device/R8 evidence uses debug AAR `aa1ca…`; release gates separately produced `89cc…`. The complete current-pair owned matrix and 160,000-operation stress run precede this production fix. [Recovery device controls](status-2026-10-30.md#recovery-device-controls--scoped-gap-closed) now **6/6 passed, independently audited clean: scoped gap closed, recovery lane free**; no threaded stress, storage-failure, cookie-deletion or whole-tree certification.

[Release-gate scaffolding](status-2026-10-30.md#integrated-release-gates--not-hosted-certification) is integrated and independently reviewed, not hosted certification. **Four published-0.5.4 JVM failures remain recorded**; [faithful Android acceptance is approved policy](status-2026-10-30.md#approved-policy--faithful-android-acceptance), native/gate implementation **staged/in progress, not live-qualified**. All **nine** release phase plans remain null. [Historical per-flow correction and current-pair diagnostic](status-2026-10-30.md#historical-route--current-pair-diagnostic-closed): **second review clean; 107 host tests plus compilation; actual 4/4 zero-skip diagnostic independently audited clean, lane released**. Scope: installed Node16.7/Core8/plugin5/EE, FDI1.18, `/login-2.18`/CDI2.18 only; not the entire historical matrix or final native/composite tree. R7 diagnostic integrated/reviewed with host tests, still **`fixture_unimplemented` / no supported live pair**. API17/Linux/broader historical pairs/non-EE and external release enforcement remain unverified. [Current coordination](status-2026-10-30.md#active-ownership-and-outstanding-gates) supersedes old pending assignments. Existing gates remain blocked; no M6 completion or release certification claim.

## Navigation

- [Wiki index](index.md) — Complete page directory and authority/evidence conventions.

## Execution

- [2026-10-30 current status](status-2026-10-30.md) — Latest signout fix, exact debug/release artifacts, JVM/API34 results, integrated gates, historical scopes and remaining blockers.
- [2026-10-30 recovery history](recovery-2026-10-30.md) — Recovered tenant/proxy receipts and chronological matrix failures, compile fixes and approved-contract execution.

- [Execution and ownership](execution-2026-10-29.md) — Read before picking up work for ownership boundaries, recommended slices, handoff criteria and a proposed manual milestone-end wiki checklist.
- [M2/M3 handoff and readiness](m2-m3-handoff-2026-10-29.md) — Latest response/scope results, staged correction, device-runner readiness and backend CI residuals with source pointers.
- [M4/M5 and upgrade handoff](m4-m5-upgrade-2026-10-29.md) — Corrected event parity/provenance, current staging, upgrade activation limits and hosted PR-gate gaps.

## Baseline evidence

- [Environment and evidence](baseline-2026-10-29.md) — Read before baseline checks for historical inspection, agent-reported harness/JVM results and remaining readiness gaps.
- [API gate and runtime floor](api-runtime-gate-2026-10-29.md) — Read before release claims for applied integration/fresh-artifact evidence, pending review and runtime certification gaps.
- [API34 upgrade qualification](upgrade-qualification-2026-10-29.md) — Integrated legacy/recovery fix and green stress, historical upgrade/R8 evidence and remaining final-artifact/device qualification gaps.

## Decisions

- [Decision register](decisions-2026-10-29.md) — Read before changing acceptance behaviour for authoritative source decisions and unresolved questions.

## Design findings

- [Migration constraints](migration-findings-2026-10-29.md) — Read before import/classification work for restart/raw-write constraints, oracle evidence limits and the now-approved recovery policy.

## Authority and evidence

[Compatibility plan](../../COMPATIBILITY_PLAN.md) owns D1–D6, R1–R17, F1–F5 and milestone acceptance. [Implementation companion](../../COMPATIBILITY_IMPLEMENTATION.md) owns batch sequencing and unresolved acceptance questions. These are linked sources, preserved in place; this wiki records coordination and evidence rather than replacing them.

Historical observations in these initial pages are dated **2026-10-29**. Code evidence is pinned to repository **supertokens/supertokens-android**, candidate commit [`eda605ba8453bc716b82b83c04628a4c0ac4ba3e`](https://github.com/supertokens/supertokens-android/commit/eda605ba8453bc716b82b83c04628a4c0ac4ba3e). Local `master` resolved to [`3c2ced040ff4764f83f84f5f15e9bc1d0b2e6e0c`](https://github.com/supertokens/supertokens-android/commit/3c2ced040ff4764f83f84f5f15e9bc1d0b2e6e0c); that is a restoration reference, not proof of published 0.5.4 behaviour.

The later implementation, characterization and review results describe **uncommitted working trees**, attributed to their reporting sessions; the inspection commit above is not their implementation commit. Later user-approved recovery choices are recorded in the decision register with their conversation source; original plans remain unedited.

For new evidence, record date, repository and full commit, working-tree changes, exact command/environment, result and artifact location. Distinguish observed results, source assertions and recommendations. Published behaviour requires the checksum-verified AAR oracle; test names or planning text alone establish no pass/fail result.
