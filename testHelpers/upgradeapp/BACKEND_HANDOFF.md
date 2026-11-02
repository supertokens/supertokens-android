# Upgrade review: backend-owner handoff

**Reviewed patch applied and activated on 2026-10-29 after M4 released live use.**
Express76371/Core76687 passed the new-route HTTP smoke and exact-identity gate.
See [activation and 20-stage device evidence](RESULTS_2026-10-29.md). No commits.

Temporary patch and isolated sources/tests:

`historical external evidence: upgrade-backend-review-65z72ua8`

- `backend-review.patch`: changes to `fixtureOwners.js`, its tests, `coreProtocol.js`,
  and new `ownerSessionState.js` / `ownerSessionState.test.js`.
- `base-sha256.json`: exact active-source baselines used to generate this patch.
- `before/` and `after/`: isolated copies. Existing backend files still matched
  their baseline hashes when the patch was generated.

The temporary patch remains historical evidence; do not apply it again. Shared
changes are limited to the five backend files listed above.

## Changes and verification

Known completed owner cleanups leave process-local tombstones. Retrying the same
ID acknowledges success after a lost HTTP response without deleting again.
Unknown IDs still fail. A mapping deletion whose response was lost can be retried
when the mapping is already absent, but cleanup still verifies both mapping and
user absence before acknowledging success.

`POST /test/owner-session-state` accepts `{fixtureId, handle}`. It first checks the
original process's exact owner/handle registry, then independently calls real
`Session.getSessionInformation(handle)`. Response: `{owned:true, active:boolean}`.
Unknown/mismatched handles get 404, disabled fixtures 501; SDK exceptions propagate
and never become a revoked result. This is read-only; it does not revoke the
session to make the logout assertion pass. Android invokes it after old-SDK
logout and after candidate restart, before scoped cleanup.

Offline tests are rerun from shared `testHelpers/server`:

```sh
node fixtureOwners.test.js
node ownerSessionState.test.js
```

Tests cover cleanup response loss, mapping-delete response loss, retry, unknown
IDs, unrelated-owner survival, active/revoked lookup, unknown/mismatched handles,
disabled fixtures and lookup errors. No Core or HTTP server was contacted.
The installed Node SDK implementation confirms `getSessionInformation` uses a
Core lookup and returns undefined for an absent session. Subsequent live HTTP and
device execution verified this behavior. Do not restart/reset shared services
without their current owner's coordination.

## Recovery contract

For a lost cleanup response, keep the original backend process and device receipt;
rerun `upgrade.sh --cleanup-only` with original run/scenario/artifact arguments
and a fresh output directory. Tombstones last only for that backend process and
are retained for its test lifetime. Unknown owners are never treated as cleaned.

This patch implements the review's minimum known-owner idempotence. It does not
implement idempotent server creation or durable server registries. Instrumentation
now commits `pendingRequestId` and `pendingRequestEmail` **before** creation and
removes them only atomically with storing the returned fixture ID. The request ID
is sent as diagnostic metadata, not an idempotency key; the current server ignores
that field. The host preserves the planned IDs/emails in `recovery.json` before
device operations and emits recovery instructions on failure. Cleanup fails
before side effects if a pending creation remains, rather than reporting success
while an unrecorded owner may survive.

If creation's response was lost, the device may not know its fixture ID. Preserve
the original process/device, run ID, scenario, request email and logs. For private
inspection, capture the receipt without printing its contents:

```sh
"$ADB" -s "$SERIAL" exec-out run-as com.supertokens.upgrade \
  cat shared_prefs/upgrade-receipt.xml > "$PRIVATE_EVIDENCE/pending-receipt.xml"
```

Use a bounded host invocation if the device is unresponsive. Ask the backend owner to
identify that specific request's registered owner before invoking scoped cleanup.
If ownership cannot be established, recovery is blocked; no broad delete/reset.
Backend restart loses the registry/tombstones, so it is not a recovery procedure.
Resolving a pending creation and reconciling its receipt is manual, not a supported
automatic retry. Success-case qualification may proceed after activation, but this
harness does not certify crash-safe creation/recovery.
