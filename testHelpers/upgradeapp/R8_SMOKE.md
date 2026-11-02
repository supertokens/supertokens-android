# M6 rank 12 — R8 cookie consumer smoke

## Result (2026-10-29)

**Actual red → green on API34 emulator-5580.** One unmapped seed, two minified
release migration/login/refresh stages, and scoped cleanup passed. The unmapped
seed is setup, **not** counted as an R8 pass. Both minified stages execute the SDK
from the application DEX; instrumentation only calls `R8Smoke.run`.

1. Unmapped candidate writes a real cookie-mode session and a synthetic attribute
   probe using the SDK cookie store. Captured stream contains the original
   `com.supertokens.session.SuperTokensSerializableHttpCookie` descriptor.
2. Before rules: minified A fails **Serialized cookie decoded to null** before
   SDK initialization/network. All three preference files remain byte-identical
   to the seed. Mapping renames the wrapper to `r8a.u6`; R8 usage reports removal
   of UID/readObject/writeObject.
3. After the narrow AAR consumer-rule fix: minified A reopens that **same data**,
   checks cookie digests and probe attributes, makes a protected request, refreshes,
   creates a new scoped real Google-owner session, refreshes and persists.
4. Minified B reopens A's cookies with a different mapping, checks the same
   invariants and repeats real login/refresh. No clear/uninstall between builds.
5. Minified B signs out, deletes exactly the three locally recorded fixture
   owners, removes its probe and commits `cleanupDone=true`, `owners=[]`, with no
   pending creation request. Existing backend remains live; no start/reset/stop.

## Fix and boundaries

`app/consumer-rules.pro` preserves only the serialized wrapper class name and its
private UID/read/write serialization hooks. `app/build.gradle` packages that file
as consumer rules. No SDK Java source changed; before/after debug `classes.jar`
are byte-identical. It is not a blanket SDK keep rule: **55 of 56 retained SDK
classes are renamed**. The cookie store itself changes `r8a.t6` → `r8b.t6`; both
names were verified in their respective app DEX. Test DEX contains no SDK copy.

App release builds use real R8 shrinking/optimization/obfuscation, are not
debuggable, share `com.supertokens.upgrade`, versionCode 1 and the same signer.
Debug signing is intentional for this dedicated consumer. `-Pr8Smoke` selects
the app-owned smoke source and thin instrumentation source; ordinary upgrade
debug builds retain their original tests. Mapping A/B differ by R8 repackage
prefix, not by keeping the SDK stable.

Scope: cookie-mode URLConnection login, getters, protected requests, refresh and
completed-write persistence. Prior stream comes from the fresh **unmapped
candidate**, not a newly executed published 0.5.4 seed. Published upgrade evidence
remains separate. This is not API17, arbitrary historical obfuscated-name recovery,
all R8/AGP versions, dropped-apply/crash recovery, header mode, or optional modern
OkHttp-path certification. Existing AGP 4.1.2/Gradle 6.5 and dependencies unchanged.

The initial seed fixture exposed the existing cookie-store empty-URI-path edge
case (`checkPathsMatch`, index -1). Its probe was changed to an explicit `/` URI;
SDK production remains frozen. That first attempt failed in CookieManager before
the login request was dispatched, leaving no backend owner. Only its own local
probe and pending harness receipt were reconciled, with private evidence retained.
This separate production edge case remains open; it is not an R8 defect or fix.

## Artifact evidence

Private root (contains live-token history; do not commit XML/log contents):

`historical external evidence: r8-smoke-stage`

- `before-fix/`, `after-fix/`: fresh source/build receipts, logs and AARs from
  `build_provenance.py`, including dirty-tree/untracked hashes and build interval.
- `seed-fixed/`, `red-a-fixed/`, `green-a/`, `green-b/`: exact packaged app/test
  APKs; minified builds retain mapping, configuration, seeds and usage reports.
- `device-seed-3/`, `device-red-a/`, `device-green-a/`, `device-green-b/`,
  `device-cleanup/`: bounded commands, manifests, signatures, DEX class lists,
  instrumentation results, fingerprint, XML and logcat. Every install uses `-r`.
- `artifact-audit.json`: independently checked artifact hashes, descriptor,
  byte-identical red-state preservation, Java majors, signer, DEX mappings,
  backend identity/capability and cleanup receipt.
- `api-gate/`, `sdk-release.aar`: final SDK API evidence and release artifact.

Fresh candidate AAR after rule packaging:
`4451903acd5d748dad4c9e46965518f2762d386c9bed8869441e9b3d43904abd`.

Release app A SHA-256:
`aea05310a73ac3fbcafb9ec9cca38e7017e41876a143c8a481f23bacc4c9ce91`.
Mapping A SHA-256:
`20751113d446bf2124d44f6c9de301cf7cca577ce071a08da98c45d1b15a495e`.

Release app B SHA-256:
`e18ba12a17b0181492300d841185984684e3199059fbacaa6885b533da53de51`.
Mapping B SHA-256:
`4f2cd707462f3fd002201d189b6dac8694737eefff48a5788b507432eba10def`.

SDK release: **79 classes, all Java major 51**. App manifests: **minSdk 17**.
`japicmp`, `verifyOldSdkConsumer`, `apiCompatibilityGateTest`, `assembleRelease`
passed after consumer-rule packaging. Existing host runner tests: **24/24**.
No SDK Java test-suite rerun is claimed for this rule-only change; prior green M6
JVM/oracle integration is separate evidence. No commits.

## Reproduce with the exclusive build/device lane

Use JDK11 and existing Android SDK; export `JAVA_HOME`, `ANDROID_HOME`.
Choose a new private evidence root and unique run ID. Backend must expose compatible
scoped owner fixtures; check `/test/capabilities`. Do not globally reset services.

```sh
python3 testHelpers/upgradeapp/tools/build_provenance.py --output "$EVIDENCE/candidate"
SHA=$(shasum -a 256 "$EVIDENCE/candidate/candidate.aar" | awk '{print $1}')
./gradlew -p testHelpers/upgradeapp \
  -PcandidateAar="$EVIDENCE/candidate/candidate.aar" -PcandidateAarSha256="$SHA" \
  -PcandidateBuildProvenance="$EVIDENCE/candidate/candidate-build.json" \
  -Pr8Smoke -Pr8TestBuildType=debug \
  :app:assembleCandidateDebug :app:assembleCandidateDebugAndroidTest
```

Archive debug APKs before building release. Run `r8_smoke.py --stage seed` using
the archived debug app/test pair. Then build and archive release A and B,
including `app/build/outputs/mapping/candidateRelease/`, separately:

```sh
./gradlew -p testHelpers/upgradeapp \
  -PcandidateAar="$EVIDENCE/candidate/candidate.aar" -PcandidateAarSha256="$SHA" \
  -PcandidateBuildProvenance="$EVIDENCE/candidate/candidate-build.json" \
  -Pr8Smoke -Pr8Mapping=a \
  :app:assembleCandidateRelease :app:assembleCandidateReleaseAndroidTest
# Archive APKs/mapping, then repeat with -Pr8Mapping=b and archive separately.
```

Per-stage invocation (seed omits `--mapping`; use the corresponding artifacts):

```sh
python3 testHelpers/upgradeapp/tools/r8_smoke.py \
  --serial emulator-5580 --backend http://10.0.2.2:8080 --run-id "$RUN_ID" \
  --stage mapping-a --app "$APP_APK" --test "$TEST_APK" --mapping "$MAPPING" \
  --receipt "$EVIDENCE/candidate/candidate-build.json" --output "$NEW_STAGE_OUTPUT"
```

Order: seed → mapping-a → mapping-b → cleanup (B artifacts). For **mapping-b**,
add `--mapping-a-evidence "$MAPPING_A_STAGE_OUTPUT"`, pointing to the completed
mapping-a runner output, not merely its build directory. A's `PASS`, run ID,
device, backend, candidate receipt and signer must match; archived app/mapping
hashes are checked. The runner compares the actual mapped cookie-store names
against both APK DEX inventories and requires the name to change. Different
mapping-file bytes alone do not qualify. The B receipt links A's evidence hashes
and records both store names. Existing archived stage receipts remain usable.

Output directories must be new. Runner bounds commands/install/instrumentation
at 30/120/120 seconds, validates package/version/signer/provenance/mapping-to-DEX
and exact test result. Mapping-stage apps must be nondebuggable according to
`aapt dump xmltree` on the actual APK; absent or explicit false is accepted, true
or unrecognized encodings are rejected. B also checks archived A's APK manifest.
These checks finish before device commands. Seed remains an unmapped candidate;
no published-seed requirement has been added.
The dedicated emulator must provide `su 0` to capture nondebuggable release XML;
only this fixture's private files are read. Override `ANDROID_BUILD_TOOLS` if
30.0.3 is unavailable. Do not use this runner on unrelated app data.

Failure preserves data. Inspect `r8-receipt.xml` before recovery. Unknown creation
outcomes require reconciliation with the backend owner, not blind retries or
global deletion. The cleanup stage expects unchanged saved cookie digests; after
a partially completed mutation, manual scoped reconciliation may be required.

## Runner repeatability review follow-up

Host-only verification: `python3 testHelpers/upgradeapp/tools/test_r8_smoke.py`
passes **8 tests**, including same-A-for-B rejection, different mapping bytes with
the same store name, debuggable A/B, unknown manifest boolean, tampered/incomplete
or wrong-run evidence, and valid archived A→B. All external tools are fake; failure
tests assert rejection before any fake device call. The original recorded A/B
APKs, mappings and receipts were also replayed successfully against archived tool
outputs with every command faked. This verifies repeatability checks, not a new
Android run. No Gradle, adb, production rules or app build changes in this follow-up.
