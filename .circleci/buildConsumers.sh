#!/bin/bash
set -euo pipefail
output=$1
test -z "$(git status --porcelain --untracked-files=all)"
python3 testHelpers/upgradeapp/tools/build_provenance.py --output "$output/candidate"
node - "$output/candidate/candidate-build.json" <<'NODE'
const fs = require('fs');
const { execFileSync } = require('child_process');
const receipt = JSON.parse(fs.readFileSync(process.argv[2]));
if (receipt.source.dirty || receipt.source.gitRevision !== execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()) {
    throw new Error('Candidate source is not the exact clean checkout');
}
NODE
sha=$(sha256sum "$output/candidate/candidate.aar" | cut -d' ' -f1)
args=(-p testHelpers/upgradeapp --no-daemon --console=plain --rerun-tasks
    "-PcandidateAar=$output/candidate/candidate.aar" "-PcandidateAarSha256=$sha"
    "-PcandidateBuildProvenance=$output/candidate/candidate-build.json")
./gradlew "${args[@]}" :app:assembleSdk054Debug :app:assembleSdk054DebugAndroidTest :app:assembleCandidateDebug :app:assembleCandidateDebugAndroidTest
mkdir -p "$output/upgrade"
cp -R testHelpers/upgradeapp/app/build/outputs/apk "$output/upgrade/apk"
./gradlew "${args[@]}" -Pr8Smoke -Pr8TestBuildType=debug :app:assembleCandidateDebug :app:assembleCandidateDebugAndroidTest
mkdir -p "$output/seed"
cp -R testHelpers/upgradeapp/app/build/outputs/apk "$output/seed/apk"
for mapping in a b; do
    ./gradlew "${args[@]}" -Pr8Smoke "-Pr8Mapping=$mapping" :app:assembleCandidateRelease :app:assembleCandidateReleaseAndroidTest
    mkdir -p "$output/$mapping"
    cp -R testHelpers/upgradeapp/app/build/outputs/apk "$output/$mapping/apk"
    cp -R testHelpers/upgradeapp/app/build/outputs/mapping/candidateRelease "$output/$mapping/mapping"
done
find "$output" -type f \( -name '*.apk' -o -name '*.aar' -o -name 'mapping.txt' \) -exec sha256sum {} \; > "$output/artifacts.sha256"
