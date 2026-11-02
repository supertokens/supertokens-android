#!/bin/bash
set -euo pipefail
repository="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$repository/owned-integration-evidence"
export GRADLE_USER_HOME="$(mktemp -d "$repository/owned-integration-evidence/core-build.XXXXXX")"

coreInfo=`curl -s -X GET \
"https://api.supertokens.io/0/core/latest?password=$SUPERTOKENS_API_KEY&planType=FREE&mode=DEV&version=$1" \
-H 'api-version: 0'`
if [[ `echo $coreInfo | jq .tag` == "null" ]]
then
    echo "fetching latest X.Y.Z version for core, X.Y version: $1, planType: FREE gave response: $coreInfo"
    exit 1
fi
coreTag=$(echo $coreInfo | jq .tag | tr -d '"')
coreVersion=$(echo $coreInfo | jq .version | tr -d '"')

pluginInterfaceVersionXY=`curl -s -X GET \
"https://api.supertokens.io/0/core/dependency/plugin-interface/latest?password=$SUPERTOKENS_API_KEY&planType=FREE&mode=DEV&version=$1" \
-H 'api-version: 0'`
if [[ `echo $pluginInterfaceVersionXY | jq .pluginInterface` == "null" ]]
then
    echo "fetching latest X.Y version for plugin-interface, given core X.Y version: $1, planType: FREE gave response: $pluginInterfaceVersionXY"
    exit 1
fi
pluginInterfaceVersionXY=$(echo $pluginInterfaceVersionXY | jq .pluginInterface | tr -d '"')

pluginInterfaceInfo=`curl -s -X GET \
"https://api.supertokens.io/0/plugin-interface/latest?password=$SUPERTOKENS_API_KEY&planType=FREE&mode=DEV&version=$pluginInterfaceVersionXY" \
-H 'api-version: 0'`
if [[ `echo $pluginInterfaceInfo | jq .tag` == "null" ]]
then
    echo "fetching latest X.Y.Z version for plugin-interface, X.Y version: $pluginInterfaceVersionXY, planType: FREE gave response: $pluginInterfaceInfo"
    exit 1
fi
pluginInterfaceTag=$(echo $pluginInterfaceInfo | jq .tag | tr -d '"')
pluginInterfaceVersion=$(echo $pluginInterfaceInfo | jq .version | tr -d '"')

echo "Testing with node driver: $2, FREE core: $coreVersion, plugin-interface: $pluginInterfaceVersion"

cd ../../
git clone git@github.com:supertokens/supertokens-root.git
cd supertokens-root
echo -e "core,$1\nplugin-interface,$pluginInterfaceVersionXY" > modules.txt
./loadModules --ssh
cd supertokens-core
git checkout $coreTag
cd ../supertokens-plugin-interface
git checkout $pluginInterfaceTag
cd ../
echo $SUPERTOKENS_API_KEY > apiPassword
./utils/setupTestEnvLocal
if ! find core -name '*.jar' -type f -exec "$JAVA_HOME/bin/jar" tf {} \; | grep '^io/supertokens/Main.class$' >/dev/null; then
    echo "Core setup failed: no built JAR contains io.supertokens.Main" >&2
    exit 1
fi
if ! find plugin-interface -name '*.jar' -type f -print -quit | grep -q .; then
    echo "Core setup failed: plugin-interface JAR is missing" >&2
    exit 1
fi
cd ../project/testHelpers/server/
nvm use 16
npm i -d --force
npm i "git+https://github.com/supertokens/supertokens-node.git#$3"
core_source="$(cd ../../../supertokens-root && pwd)"
node "$repository/.circleci/sourceRevisionManifest.js" \
    "$repository/owned-integration-evidence/source-revisions-$5.json" "$repository" \
    "$core_source" "$PWD/package-lock.json" "$5" "$6" "$2" "$4" "$3" \
    "$coreTag" "$coreVersion" "$pluginInterfaceTag" "$pluginInterfaceVersion"
npm test
npm run test:entrypoints
cd "$repository"
CORE_PROTOCOL_TESTS=1 ST_TEST_JAVA=/usr/java/jdk-21/bin/java \
JAVA_HOME=/usr/java/jdk-11 PATH="/usr/java/jdk-11/bin:$PATH" \
node testHelpers/server/runOrdinary.js "$core_source" "$repository/owned-integration-evidence" 18081 19001 \
    node "$repository/testHelpers/server/runOwnedTests.js" matrix "$repository" "$repository/owned-integration-evidence/release-context.json"
fdi=$(node -p 'JSON.parse(require("fs").readFileSync("owned-integration-evidence/release-context.json")).fdi')
# This is reached only after runOrdinary proves successful command drain and cleanup.
node .circleci/integrationReceipt.js finalize /tmp/integration-receipts "$fdi"
CORE_PROTOCOL_TESTS=1 ST_TEST_JAVA=/usr/java/jdk-21/bin/java \
JAVA_HOME=/usr/java/jdk-11 PATH="/usr/java/jdk-11/bin:$PATH" \
node .circleci/publishedUpgradeAdapter.js "$core_source"
