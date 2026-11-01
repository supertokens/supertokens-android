#!/bin/sh
set -eu

aar=${1:?Provide the published supertokens-android 0.5.4 AAR}
dependencies=${2:?Provide the OkHttp compile dependency classpath (or use :app:regenerateOldSdkConsumer)}
android_home=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
android_jar=${android_home:?Set ANDROID_HOME or ANDROID_SDK_ROOT}/platforms/android-32/android.jar
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
expected=$(cat "$dir/legacy054.sha256")
actual=$(shasum -a 256 "$aar" | cut -d ' ' -f 1)
if [ "$actual" != "$expected" ]; then
    echo "Unexpected 0.5.4 AAR checksum" >&2
    exit 1
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
unzip -p "$aar" classes.jar > "$tmp/classes.jar"
javac -g -proc:none -source 8 -target 8 -classpath "$tmp/classes.jar:$android_jar:$dependencies" -d "$tmp" "$dir/OldSdkConsumer.java"
mkdir -p "$dir/../resources/compatibility"
cp "$tmp/compatibility/OldSdkConsumer.class" "$dir/../resources/compatibility/OldSdkConsumer.bin"
