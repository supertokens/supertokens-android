# API gate and runtime-floor blocker — 2026-10-29

**Final-audit supersession, 2026-10-30:** [portable local execution](status-2026-10-30.md#portable-owned-entrypoints--reviewed-local-execution) now records mutation6/6 and ordinary/Retrofit121 pass/6 skip each. Staged/blocked/compile-only statements below are historical. [Active work and gates](status-2026-10-30.md#active-ownership-and-outstanding-gates) remain open; no release certification.

[Index](index.md) · [Decisions](decisions-2026-10-29.md) · [Execution](execution-2026-10-29.md)

Source session `ses_e7840d132fferIuAlb3qsvqZ79`; authoritative handoff: [API_GATE.md](../../app/src/test/compatibility/API_GATE.md), read on **2026-10-29**. Implementation claims concern the uncommitted working tree of `supertokens/supertokens-android`, not the wiki's pinned inspection commit. Commands and policy details remain in the source rather than duplicated here.

## Gate status

**Latest runtime evidence, 2026-10-30:** [reviewed native API34 cookie continuation](status-2026-10-30.md#api34-cookie-continuation--reviewed-execution) passed 6/6 stages using the recorded **debug AAR**, distinct from earlier release-AAR qualification. FDI advertisement is not a negotiated version; no API17/historical FDI/exposed-cookie/live failure-recovery claim. Java11 6 pass/2 fail/8 skip remains unchanged; standalone ordinary/Retrofit lifecycle corrections are staged, not completed execution.

**Current correction, 2026-10-30:** [verified four-lane matrix](status-2026-10-30.md#actual-matrix-gates-and-new-stress-evidence) is **578/578 each**, zero skips/failures/errors; **131 oracle**, lint zero/API green, 84 Java7 classes, 20 NEW nightly seeds/160,000 clean operations. Historical 3.12 owned branches prove **constructor rejection only**. Standalone ordinary/Retrofit compilation is not new suite execution. Earlier exact-artifact upgrade/R8/testapp successes are [scoped separately](status-2026-10-30.md#recovered-live-and-exact-artifact-results). API17/Linux/historical FDI/external release enforcement remain unverified; older pending-matrix/rerun statements below are superseded only within these scopes.

**Current supersession, 2026-10-29:** [legacy/recovery integration](../../app/src/test/compatibility/R15_PENDING_LEGACY_FIX.md) passed **437 JVM/71 oracle/API and release assembly, 84 major-51 classes**, default 640 and 20 × 8,000 stress operations with zero violations. Prior 496/497 blocker is closed, not waived. [Latest AAR](upgrade-qualification-2026-10-29.md#latest-integrated-legacy-and-healthy-recovery-fix) is not certified by older device/R8/upgrade runs. M7 docs/version application is underway, not complete; latest-artifact upgrade/R8/full testapp/API17/Linux/check administration remain pending. Device-death bundle's 12 host passes/Java7 are review-pending, not device execution. Earlier red-gate statements below are history.

**Latest gate qualification, 2026-10-29:** callback P1 fixed/reviewed clean, but [combined staged suite remains 496/497](upgrade-qualification-2026-10-29.md#current-verification-and-staged-risks) due to current-baseline recovery stress. Debugger owns sole Gradle integration lane; no integrated green result yet. [Seven focused partial-write/lost-response passes](upgrade-qualification-2026-10-29.md#partial-writes-and-lost-refresh-response) and Android test compilation add scoped evidence, not device execution. Actual death mid-login/refresh, post-R15 R8/upgrade reruns, Linux API17 and cross-CI gates remain pending; R7 unsupported.

**Current supersession, 2026-10-29:** [integrated R15/XML](../../app/src/test/compatibility/R15_INTEGRATION.md) passed 412 JVM/67 oracle, API gates/release assembly; **84 major-51 classes**. Fresh API34 StrictMode 3/3, restart 8/8 and warm 1/1 passed. Earlier [R8 qualification](../../testHelpers/upgradeapp/R8_SMOKE.md) is actual API34 evidence with narrow consumer rules; its repeatability follow-up is host-only. **Post-R15 R8/published-upgrade reruns, Linux API17 execution and cross-CI `markAsSuccess` remain pending.** Four workflow files are reported integrated; M7 0.6.0 patch remains TEMP. [Artifact/scope details](upgrade-qualification-2026-10-29.md#integrated-artifact-and-required-reruns) supersede earlier staged-R15/no-R8-result status below.

Latest **2026-10-29**: integrated fault suite **293 JVM/67 oracle/API passed**, no production change. R8 now owns Gradle/device execution; no R8 result yet. API17 image availability is confirmed, but harness/workflow remains staged for review and **no API17 execution is claimed**. [Stage/provenance details](upgrade-qualification-2026-10-29.md#current-verification-and-staged-risks).

Superseding **2026-10-29** evidence: independent [upgrade audit](upgrade-qualification-2026-10-29.md) supports recorded completed-write scenarios/current pair; source hashes do not reconstruct a source snapshot. [Corrected StrictMode 3/3](m4-m5-upgrade-2026-10-29.md#valid-replacement-strictmode-proof) is valid scoped API34 evidence after red/green disk-control proof. Earlier pending-audit/rerun statements below are historical; old 2/2 remains invalid. API17/R8/crash-safe/future-R15 exclusions still apply.

Later **2026-10-29** [published upgrade qualification](upgrade-qualification-2026-10-29.md) supplies actual API34 upgrade/rollback evidence for a fresh, provenance-matched M5 AAR, with pending audit. This supersedes earlier published-upgrade-pending statements only for that artifact/scenario set. API17, R8, XML replay and future R15 certification remain open; StrictMode 2/2 remains invalid performance evidence.

Latest **2026-10-29** merged M4/M5 reports: API green and **79 major-51 classes**, with M5 **290 JVM/66 oracle** passing. These supersede earlier artifact counts for their tested builds. **StrictMode API34 2/2 is invalid performance evidence**, not runtime certification; see [P1 correction](m4-m5-upgrade-2026-10-29.md#strictmode-performance-evidence-invalidated). Separate restart 9/9 remains valid. Corrected monitoring/negative-control rerun is pending; lock protocol remains unapplied.

Verified **2026-10-29**, source `ses_e7840d132fferIuAlb3qsvqZ79` and updated [API_GATE.md](../../app/src/test/compatibility/API_GATE.md), read for this update: **17 policy cases passed; 35 public classes compared with zero disallowed changes; expanded consumer regenerated and freshness verified; 1 candidate smoke test passed**. These scoped results concern the integrated uncommitted working tree before the latest runtime application, not the pinned inspection commit or a full-suite pass. Source commands, environment and report paths remain in API_GATE.md.

The earlier deliberately stale `.bin`/pending-execution status is superseded. Coordinator reports clean review with a minor README correction. No separate API reviewer session was supplied in that handoff. The later runtime integration below reran these gates; [sole Gradle ownership](execution-2026-10-29.md#gradle-execution-ownership) has now transferred to M2.

## Published artifact audit

Latest **2026-10-29** [R10 integration](m4-m5-upgrade-2026-10-29.md#r10-corrected-integration) reports API gates green and **78 major-51 release classes**, superseding earlier artifact class counts for that uncommitted build. This does not recertify device or historical consumer/R8 coverage.

The source reports an audit of the checksum-verified published 0.5.4 AAR, SHA-256 `2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9`:

- All **31 classes** use major version **51, Java 7 bytecode**.
- **42 distinct direct OkHttp method references** link against 3.3.0; required methods are absent from 3.2.0.
- Published metadata declares no OkHttp minimum. The observed static linkage floor is **not runtime certification**, transitive-dependency proof or an Android API-floor determination.

This contradicts the plan's suggested premise that Java 8/OkHttp 3.12 were already required. **D5 release blocker:** preserve compatibility or resolve the conflict explicitly before release. No raised floor is approved; passing a signature comparison alone cannot close this blocker.

## Applied runtime integration

**2026-10-29**, source `ses_e7816e0ebffeJVAbgd0O8MO2iD` and [RUNTIME_COMPATIBILITY.md](../../app/src/test/compatibility/RUNTIME_COMPATIBILITY.md), read for this update: staged compatibility work is **applied** to the uncommitted `supertokens/supertokens-android` working tree. The source owns exact commands, artifact hashes and detailed evidence; the pinned HEAD is its base, not an implementation commit.

- **233 JVM + 36 oracle tests passed**, zero failures/errors/skips; API policy, japicmp and consumer verification passed again. This supersedes prior integrated assertion conflicts after reviewed rebaselines, including storage controls and R17/resource bounds.
- Fresh release AAR: **77 classes, all major 51 (Java 7)**. Production compilation uses source/target 7; test compilation remains Java 8. Metadata adds no OkHttp minimum or JVM-version requirement.
- **Seven fresh-artifact OkHttp lanes, 3.3.0 through 4.1.0**, passed probes including login, refresh, retry, rotation and logout. These are JVM/header-mode HTTP probes, not complete Android/cookie/TLS certification. The new opt-in owned-transport constructor still requires OkHttp **3.14+**, without raising the legacy path's floor.
- `lintRelease` completed with an empty issue report but emitted a lint/JNA native-library warning. It is not clean device/API-17 certification.

Runtime review by `ses_e780086eeffeABhsUZF5dYVJKg` is **pending**. API 17 execution, historical consumer-build pipelines, R8 optional-class isolation and device upgrade/rollback remain pending. The original raised-floor conflict has an applied preservation implementation with scoped green evidence; release-wide runtime certification remains open. No raised legacy floor was adopted.

Later **2026-10-29** handoff: M2 initially reported API success and 77 major-51 release classes, then required a reviewed staged strict-validation correction. [M3 integration/testing](m2-m3-handoff-2026-10-29.md) is pending. API34 emulator boot without a fresh installed APK adds readiness only, not device or API17 certification.

Superseding update **2026-10-29**: [M3 completion and API34 execution](m2-m3-handoff-2026-10-29.md) now establish applied strict integration with API/release gates green, 77 major-51 classes and 9/9 actual candidate restart invocations on the recorded fresh APK. Earlier staging/boot-only statements are historical. Published upgrade, API17, historical consumer/R8 and fresh seven-lane recertification for M3 remain outside that evidence.
