#!/bin/sh
set -eu

aar=${1:?Provide the published supertokens-android 0.5.4 AAR}
android_home=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
android_jar=${android_home:?Set ANDROID_HOME or ANDROID_SDK_ROOT}/platforms/android-32/android.jar
expected=2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9
actual=$(shasum -a 256 "$aar" | cut -d ' ' -f 1)
if [ "$actual" != "$expected" ]; then
    echo "Unexpected 0.5.4 AAR checksum" >&2
    exit 1
fi
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
unzip -p "$aar" classes.jar > "$tmp/classes.jar"
javac -source 8 -target 8 -classpath "$tmp/classes.jar:$android_jar" -d "$tmp" "$dir/OldSdkConsumer.java"
mkdir -p "$dir/../resources/compatibility"
cp "$tmp/compatibility/OldSdkConsumer.class" "$dir/../resources/compatibility/OldSdkConsumer.bin"
