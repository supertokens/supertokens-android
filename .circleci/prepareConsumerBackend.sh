#!/bin/bash
set -euo pipefail
umask 077
output=$1
repository=$(pwd)
mkdir -p "$output/public" "$output/private"
for name in COMPAT_ROOT_SHA COMPAT_CORE_SHA COMPAT_PLUGIN_SHA COMPAT_NODE_SHA; do
    [[ ${!name:-} =~ ^[a-f0-9]{40}$ ]] || { echo "$name must pin an exact reviewed revision" >&2; exit 1; }
done
test -n "${SUPERTOKENS_API_KEY:-}"
clone() {
    git init "$3"
    git -C "$3" remote add origin "https://github.com/supertokens/$1.git"
    git -C "$3" fetch --depth=1 origin "$2"
    git -C "$3" checkout --detach FETCH_HEAD
    test "$(git -C "$3" rev-parse HEAD)" = "$2"
}
clone supertokens-root "$COMPAT_ROOT_SHA" "$output/core-source"
clone supertokens-core "$COMPAT_CORE_SHA" "$output/core-source/supertokens-core"
clone supertokens-plugin-interface "$COMPAT_PLUGIN_SHA" "$output/core-source/supertokens-plugin-interface"
printf 'root %s\ncore %s\nplugin %s\nnode %s\nandroid %s\n' "$COMPAT_ROOT_SHA" "$COMPAT_CORE_SHA" "$COMPAT_PLUGIN_SHA" "$COMPAT_NODE_SHA" "$(git rev-parse HEAD)" > "$output/public/source-revisions.txt"
cd "$output/core-source"
printf 'core,pinned\nplugin-interface,pinned\n' > modules.txt
printf '%s' "$SUPERTOKENS_API_KEY" > apiPassword
trap 'rm -f "$output/core-source/apiPassword"' EXIT
# Preserve upstream build conventions; it may return zero after a nested build failure.
timeout --signal=TERM --kill-after=30s 25m ./utils/setupTestEnvLocal > "$output/private/core-build.log" 2>&1
rm -f apiPassword
unset SUPERTOKENS_API_KEY
find core -name '*.jar' -type f -exec "$JAVA_HOME/bin/jar" tf {} \; > "$output/private/core-classes.txt"
grep -qx 'io/supertokens/Main.class' "$output/private/core-classes.txt"
test -n "$(find plugin-interface -name '*.jar' -type f -print -quit)"
test -s version.yaml
find core plugin-interface ee -name '*.jar' -type f -exec sha256sum {} \; > "$output/public/core-jars.sha256"
cp version.yaml "$output/public/core-version.yaml"
cd "$repository/testHelpers/server"
# Pin the SDK before npm resolves dependencies; never fetch its moving default branch.
node -e 'const fs=require("fs"); const p=JSON.parse(fs.readFileSync("package.json")); p.dependencies["supertokens-node"]="git+https://github.com/supertokens/supertokens-node.git#"+process.env.COMPAT_NODE_SHA; fs.writeFileSync("package.json",JSON.stringify(p,null,2));'
npm install --no-audit --no-fund > "$output/private/npm-install.log" 2>&1
cp package-lock.json "$output/public/backend-package-lock.json"
# Source provenance must represent the checkout, not this disposable install edit.
git checkout -- package.json
