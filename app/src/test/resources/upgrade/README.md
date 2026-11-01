# Sanitized API34 preference replay captures

These fixtures derive from the 2026-10-29 real published 0.5.4 → M5 candidate →
published mutation → candidate device run. **Sanitized, not raw/bit-identical.**
`capture-provenance.json` records every source filename/hash, scenario/stage,
installed app APK hash, sanitized XML hash, XML key/type map and transformation
script digest. Raw private evidence remains outside the repository:

`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/upgrade-activation-5oxbst6c`

## Layout and actual observed coverage

- `0.5.4/`: published **seed and mutate** captures for login-B, refresh, logout
  and cookie. These are published-written snapshots, not pristine 0.5.4 stores:
  rollback and later seeds deliberately retain earlier candidate metadata.
- `candidate/`: candidate check-initial, check-final and cleanup captures,
  separated from published provenance.
- All **45** captures: 20 SDK preferences, 20 typed harness receipts, 5 cookie
  stores. There are 20 independent replay states; 8 are published-written.
- SDK/cookie values in these captures are XML **strings**, including numeric
  revisions/expiry/markers. Harness receipts also contain XML **booleans**.
  No actual int/long/float/set/XML-null value was observed. Those parser behaviors
  have separate explicitly synthetic loader tests, not device-coverage claims.
- JWT version **5** only. Front `up` retains JSON null CSRF/parent-refresh-hash
  fields, numeric timestamps, and identity structure. Captured CSRF is null/absent;
  this does not establish non-null CSRF replay coverage.

## Sanitization contract

The reproducible sanitizer is included in the integration patch at
`app/src/test/compatibility/upgrade/sanitize_captures.py` (digest in provenance).
From the repository root, regenerate with:

```sh
python3 app/src/test/compatibility/upgrade/sanitize_captures.py --source "$PRIVATE_CAPTURE_ROOT"
```

Optional `--output` overrides the repository resource destination. It reads private originals
and writes only transformed fixtures; no raw credential mapping is persisted.

1. Pseudonymize user, recipe-user, session handle, fixture, adoption, process and
   run/request IDs consistently across all captures. UUID-shaped input remains
   UUID-shaped: adoption validation depends on this structural property.
2. Replace every refresh credential and JWT signature with unmistakably invalid
   fixture values. Replace key IDs and refresh-hash material; preserve repeated
   value equality, JSON field structure/types, token version/algorithm, and nulls.
   These are parser fixtures, not cryptographically valid tokens. Refresh-hash
   values no longer claim a cryptographic relationship to actual refresh secrets.
3. Shift `iat`/`exp`, front `ate`, persisted expiry and nonzero update/legacy-marker
   timestamps by one common offset into 2100. Preserve milliseconds/seconds
   relationships, equality, ordering, deltas and zero sentinels. Session, receipt
   and cookie JWT identity/expiry agree. Keep tenant and SDK configuration digest;
   change token issuer/receipt backend to `http://fixture.invalid`.
4. Patch **only serialized cookie credential strings and their Java stream length
   fields**. Preserve class descriptor, serial UID, domain/path, maxAge, version,
   booleans and null attributes. Hex remains an XML string. JVM tests decode it
   using the candidate's real `SuperTokensSerializableHttpCookie` via cookie store.
   Stream metadata is retained, not replaced with a handwritten JSON cookie.
5. Build a cross-stage original-credential-digest → sanitized-credential-digest
   mapping from all session/cookie captures, then transform each original receipt
   digest through that mapping. Preserve all **six nonempty historical cleanup
   digests** even though cleanup's current credentials are absent. Originally empty
   digests remain empty; unresolved historical digests fail regeneration. Preserve
   boolean types, empty strings and absent keys.
6. Rebind tuple fingerprints for the sanitized tuple while preserving each raw
   fingerprint's **matching vs stale vs missing** decision. Never turn stale
   rollback metadata into coherent candidate metadata. Metadata revisions/schema,
   validation/status and key presence/type remain unchanged. XML element order is
   retained by the transform but is irrelevant to the loader and replay tests.
7. Scan transformed XML and decoded JWT/cookie representations for original
   sensitive values. Source hashes are provenance, not usable credentials.

## Replay assertions

`DeviceXmlReplayTest` runs four tests per capture state:

- Load actual typed XML; candidate init yields readable AVAILABLE/ABSENT, never
  unexpected RECOVERY_REQUIRED. Preserve complete header tuple or cookie-mode
  header absence, JWT/front identity/version/expiry and usable synthetic cookies.
- Check import/write policy: eight missing/stale published states import once;
  twelve coherent candidate states require no initial write. Two durable XML
  serialize/parse/process-reset cycles retain adoption/configuration/revisions,
  tuple and state with no extra writes or lifecycle events. Both `session.commits`
  and `session.applies` are checked before/after each restart; initialization must
  also issue zero apply calls.
- Invalid **strict install** cannot replace the imported tuple or emit events.
  Importing legacy material does not relax the explicit strict-install boundary.
- Corrupting versioned revision metadata while retaining coherent tuple evidence
  must require recovery, expose no tokens and fabricate no startup events.
- The fourth parameterized test parses all files (including receipts/cookies),
  verifies actual type declarations and round-trips maps without coercion.

`DeviceXmlTransitionTest` adds five cross-stage assertions: login B replaces A
while stale user metadata survives; refresh changes the refresh credential but
keeps user/handle; old logout removes credentials despite stale AVAILABLE metadata;
cookie login B changes user without introducing header credentials; six historical
cleanup digests still match their preceding check-final receipt, while logout's
empty digests remain empty.

`CaptureIntegrityTest` verifies all **45 unique fixture SHA-256 values** against
the manifest and asserts **12 coherent / 7 stale / 1 missing** classifications.
It also proves rejection of modified bytes and modified expected hashes for every
capture (90 rejection probes). Source-private hashes are retained as provenance;
this test does not load raw captures or credentials.

`TypedPreferencesXmlTest` has three synthetic parser tests covering Integer vs
Long, Float, Boolean, String, string set, XML null vs empty vs absent, whitespace,
escaping/order, and rejection of invalid/duplicate/unsupported values. XML external
entity/DOCTYPE processing is disabled.

Total: **90 tests** (80 state tests + 5 transition tests + 3 loader tests + 2 integrity tests).
This is JVM persistence replay, not JWT signature validation, network/backend
re-execution, Android XML parser equivalence, API17/R8 coverage or crash testing.
