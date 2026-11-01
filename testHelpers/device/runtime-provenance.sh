#!/usr/bin/env bash
set -euo pipefail
api=$1
arch=$2
output=$3
mkdir -p "$output"
sdk=${ANDROID_HOME:?ANDROID_HOME required}
image="$sdk/system-images/android-$api/default/$arch"
printf '%s\n' "system-images;android-$api;default;$arch" > "$output/image-id.txt"
status=0
cp "$image/source.properties" "$output/image-source.properties" || status=1
cp "$image/package.xml" "$output/image-package.xml" || status=1
"$sdk/emulator/emulator" -version > "$output/emulator-version.txt" 2>&1 || status=1
ls -l /dev/kvm > "$output/kvm-device.txt" 2>&1 || status=1
"$sdk/emulator/emulator" -accel-check > "$output/accel-check.txt" 2>&1 || status=1
exit "$status"
