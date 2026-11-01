#!/bin/bash
set -euo pipefail
umask 077
repository=$1
public=$2
private=$3
cd "$repository"
test -n "${ST_TEST_RUN_ID:-}"
test -n "${INSTALL_PATH:-}"
export ANDROID_BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/30.0.3"
export AAPT="$ANDROID_BUILD_TOOLS/aapt" APKSIGNER="$ANDROID_BUILD_TOOLS/apksigner"
export ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
status=failed
finish() {
    printf '%s\n' "$status" > "$public/consumer-status.txt"
    # No raw XML, backend responses, logcat or instrumentation logs leave this runner.
    find "$private" -type f -not -path '*/node_modules/*' -not -path '*/gradle-home/*' -exec sha256sum {} \; > "$public/private-evidence.sha256"
}
trap finish EXIT
node testHelpers/server/runOwnedTests.js qualify "$repository" > "$private/qualification.log" 2>&1
cp "$INSTALL_PATH/dependencies.sha256" "$public/backend-dependencies.sha256"
sha=$(sha256sum "$public/candidate/candidate.aar" | cut -d' ' -f1)
apks="$public/upgrade/apk"
sh testHelpers/device/upgrade.sh --serial emulator-5554 --backend http://10.0.2.2:18081 \
    --sdk-apk "$apks/sdk054/debug/app-sdk054-debug.apk" \
    --sdk-test-apk "$apks/androidTest/sdk054/debug/app-sdk054-debug-androidTest.apk" \
    --candidate-apk "$apks/candidate/debug/app-candidate-debug.apk" \
    --candidate-test-apk "$apks/androidTest/candidate/debug/app-candidate-debug-androidTest.apk" \
    --candidate-aar-sha256 "$sha" --candidate-build-provenance "$public/candidate/candidate-build.json" \
    --output "$private/upgrade" > "$private/upgrade.log" 2>&1
printf 'PASS: published upgrade/rollback, all scenarios and cleanup\n' > "$public/stages.txt"
run_stage() {
    local stage=$1 artifact=$2 variant=$3
    shift 3
    python3 testHelpers/upgradeapp/tools/r8_smoke.py --serial emulator-5554 --backend http://10.0.2.2:18081 \
        --run-id "$ST_TEST_RUN_ID" --stage "$stage" \
        --app "$public/$artifact/apk/candidate/$variant/app-candidate-$variant.apk" \
        --test "$public/$artifact/apk/androidTest/candidate/$variant/app-candidate-$variant-androidTest.apk" \
        --receipt "$public/candidate/candidate-build.json" --output "$private/r8-$stage" "$@" > "$private/r8-$stage.log" 2>&1
    printf 'PASS: R8 %s\n' "$stage" >> "$public/stages.txt"
}
run_stage seed seed debug
run_stage mapping-a a release --mapping "$public/a/mapping/mapping.txt"
run_stage mapping-b b release --mapping "$public/b/mapping/mapping.txt" --mapping-a-evidence "$private/r8-mapping-a"
run_stage cleanup b release --mapping "$public/b/mapping/mapping.txt"
status=passed
