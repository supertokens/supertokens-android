# Synthetic native recovery controls — implemented, NOT executed

`OwnedRecoveryDeviceControlsTest` exercises the actual candidate SDK and native
SharedPreferences through `OwnedRecoveryGuard`. No backend, mocks, reflection or
production changes. It is **not** recovery authorization for retained failed apps.
Do not run `OwnedExposureRecoveryTest` as part of these controls.

Coverage:
- Captured A passes identity validation; real `installSession` installs B immediately
  before real `clearSession(A)`. Guard must refuse and B's access/refresh persist.
  Deterministic interleaving, not a probabilistic threaded stress test.
- Any raw cookie-store residue refuses before clear, preserving both preference maps.
- Real legacy partial access write produces UNAVAILABLE; ambiguous identity refuses
  without changing persistence. Wrong recorded user and handle also refuse.
- Exact owner clears successfully; separate instrumentation process verifies raw
  credential absence **before** SDK initialization and ABSENT afterward.

Six test methods; each seed needs fresh app data. Restart verification intentionally
depends on its seed receipt and caller's unique run ID. Run only the listed methods,
not whole-suite discovery. RECOVERY_REQUIRED storage-failure injection and cookie
deletion remain out of scope. Cookie fixture is opaque raw residue, not a serialized
live cookie: the guard's contract is refusal of *any* nonempty map without parsing.

## Sole executor handoff (after historical changes settle)

No Gradle, backend or device commands were run while implementing these tests.
Shared build/runner/export/continuation files were not changed. Existing build has
no isolated application-ID option. A namespace-only patch is staged at:

`/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/recovery-device-controls/isolated-namespace.patch`

Apply **only to a disposable complete repository copy**, never the working repo.
This retains the Java manifest namespace so existing BuildConfig imports compile;
only installed target/test IDs and embedded provenance change. Test setup asserts
the isolated target ID before preferences or SDK access, so accidental execution
in `com.supertokens.upgrade` fails without touching its state.

Example coordinator commands (review paths, JDK/SDK and candidate receipts first):

```bash
set -euo pipefail
ROOT="$PWD"
E=/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode
OUT=$(mktemp -d "$E/recovery-device-run.XXXXXX")
mkdir "$OUT/repo"
rsync -a --exclude=.git --exclude=.gradle --exclude=build --exclude=node_modules \
  "$ROOT/" "$OUT/repo/"
cd "$OUT/repo"
git apply --check --unidiff-zero "$E/recovery-device-controls/isolated-namespace.patch"
git apply --unidiff-zero "$E/recovery-device-controls/isolated-namespace.patch"
export JAVA_HOME="${RECOVERY_JAVA_HOME:-/Users/bogdan/.local/share/mise/installs/java/temurin-11.0.25+9}"
test -x "$JAVA_HOME/bin/java"
test -x "$JAVA_HOME/bin/javac"
"$JAVA_HOME/bin/java" -version 2>&1 | grep -Eq 'version "11\.'
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
: "${CANDIDATE_AAR:?absolute verified candidate AAR}"
: "${CANDIDATE_BUILD:?matching build provenance receipt}"
: "${ANDROID_SERIAL:?explicit isolated device serial}"
CANDIDATE_SHA=$(shasum -a 256 "$CANDIDATE_AAR" | awk '{print $1}')
./gradlew -p testHelpers/upgradeapp \
  -PcandidateAar="$CANDIDATE_AAR" -PcandidateAarSha256="$CANDIDATE_SHA" \
  -PcandidateBuildProvenance="$CANDIDATE_BUILD" \
  :app:assembleCandidateDebug :app:assembleCandidateDebugAndroidTest \
  2>&1 | tee "$OUT/build.log"
APKS="$PWD/testHelpers/upgradeapp/app/build/outputs/apk"
APP="$APKS/candidate/debug/app-candidate-debug.apk"
TEST="$APKS/androidTest/candidate/debug/app-candidate-debug-androidTest.apk"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
AAPT="${RECOVERY_AAPT:-$ANDROID_SDK_ROOT/build-tools/30.0.3/aapt}"
test -x "$AAPT"
"$AAPT" dump badging "$APP" > "$OUT/app-badging.txt"
"$AAPT" dump badging "$TEST" > "$OUT/test-badging.txt"
grep -q "package: name='com.supertokens.recoverycontrols'" "$OUT/app-badging.txt"
grep -q "package: name='com.supertokens.recoverycontrols.test'" "$OUT/test-badging.txt"
# Stop if this namespace already exists: do not overwrite unidentified prior data.
"$ADB" shell pm list packages > "$OUT/packages-before.txt"
if grep -q 'package:com.supertokens.recoverycontrols' "$OUT/packages-before.txt"; then
  echo 'Isolated namespace already exists; reconcile its ownership first' >&2; exit 1
fi
"$ADB" install "$APP"
"$ADB" install "$TEST"
RUN=$(uuidgen)
run_control() {
  "$ADB" shell am instrument -w -r \
    -e class "com.supertokens.upgrade.OwnedRecoveryDeviceControlsTest#$1" \
    -e recoveryControls synthetic-exclusive -e recoveryRun "$RUN" \
    com.supertokens.recoverycontrols.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$OUT/$1.log"
  grep -q 'OK (1 test)' "$OUT/$1.log"
  ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$OUT/$1.log"
}
for method in capturedOwnerCannotClearInstalledReplacement \
  persistedCookieResidueRefusesEvenMatchingOwner \
  partialLegacyCredentialsRefuseAmbiguousIdentity \
  mismatchedRecordedIdentityPreservesAvailableOwner exactOwnerClearPersistsAbsence; do
  "$ADB" shell am force-stop com.supertokens.recoverycontrols
  "$ADB" shell pm clear com.supertokens.recoverycontrols | grep -q Success
  run_control "$method"
done
# No pm clear/reinstall between exact clear and restart verification.
"$ADB" shell am force-stop com.supertokens.recoverycontrols
run_control clearedSessionRemainsAbsentAfterProcessRestart
shasum -a 256 "$APP" "$TEST" "$CANDIDATE_AAR" > "$OUT/artifact-hashes.txt"
# Optional cleanup only after evidence is archived: uninstall these TWO synthetic IDs.
# Never clear/uninstall com.supertokens.upgrade or any retained failed-app namespace.
```

Archive composed source hashes, candidate receipt, logs and raw isolated preferences
before cleanup. Build/install/test failure means stop and retain evidence; no broad
fallback reset. These commands produce new local recovery-control evidence, not
historical continuation or release certification. No pass claim until the sole live
executor runs them and reviews all six results and the distinct-process check.
