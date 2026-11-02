# Compatibility qualification status

The 0.6.0 candidate is not release-qualified. Earlier local JVM, device and backend
runs applied to specific historical artifacts or dirty source snapshots, not the final
candidate. Historical failures and private evidence remain preserved outside this tree.

Final-SHA unit, published-0.5.4 oracle, API/runtime, hosted matrix, integration and
device gates remain required by [the regression plan](../REGRESSION_FIX_PLAN.md).
The four published-cookie JVM upgrade failures remain failures; Android acceptance
requires correctly bound evidence and is not blanket qualification. No per-FDI result
or successful publication is implied by this summary.
The reported R21 intermittent failure remains unresolved; original failure evidence
has not been found. Failed validation runs remain preserved. A later pass alone does
not establish the cause or erase those observations.
