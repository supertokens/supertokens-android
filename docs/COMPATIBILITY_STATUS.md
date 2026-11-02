# Compatibility qualification status

The 0.6.0 candidate is not release-qualified. Earlier local JVM, device and backend runs
applied to historical artifacts or dirty snapshots, not the final candidate. Historical
hosted failures remain reviewable at run links; missing private/local receipts are not
acceptance evidence.

Final-SHA unit, published-0.5.4 oracle, API/runtime, hosted matrix, integration and
device gates remain required by [the regression plan](../REGRESSION_FIX_PLAN.md).
The four published-cookie JVM upgrade failures remain failures; Android acceptance
requires correctly bound evidence and is not blanket qualification. No per-FDI result
or successful publication is implied by this summary.
The R21 intermittent failure remains unresolved; original failure evidence has not been
found. A later pass alone does not establish the cause or erase those observations.

## Recovery checkpoint — 2026-11-02

API 21 test-only fix commit `d09d1c7` has not yet been validated on hosted CI.
All results below are **diagnostic at recovered hosted baseline E4**
(`e4ff5edd8539bacf7eae70df5fe237aace4c2b03`), attempt 1, not fix-commit qualification:
- [Unit](https://github.com/supertokens/supertokens-android/actions/runs/41434675367) passed; [nightly](https://github.com/supertokens/supertokens-android/actions/runs/41434675339) passed all OkHttp lanes and Stress 20.
- [Device](https://github.com/supertokens/supertokens-android/actions/runs/41434675325): API 17/19 runtime and API 34 restart/StrictMode passed; API 21 restart passed, but StrictMode hit a disk-read child-death timeout.
- [Consumer](https://github.com/supertokens/supertokens-android/actions/runs/41434675389): all four COMPAT SHA variables and the setup key were empty; failed before upgrade/R8. Consumer remains blocked; the owner deferred configuration and authorized no changes at this checkpoint.
- Circle pipeline 611: all nine failed in the same host-cleanup fixture child Bash 20-second ETIMEDOUT before live qualification ([job 687](https://circleci.com/gh/supertokens/supertokens-android/687)). Cause remains unknown; the proposed child-environment isolation addresses a proven hermeticity defect, not an established retrospective cause.

Required exposed profile remains fail-closed under `ownedExposedCleanup`, not advertised;
all nine upgrade plans remain null and native acceptance is off. The archived seed/R21
cause remains unknown despite the deterministic stress-oracle fix and green CI.
