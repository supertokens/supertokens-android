#!/bin/bash
set -euo pipefail

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
./gradlew --stop
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
npm i git+https://github.com:supertokens/supertokens-node.git#$2
npm test
TEST_MODE=testing INSTALL_PATH=../../../supertokens-root node . &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
server_ready=false
for attempt in {1..60}; do
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "Test server exited before becoming ready" >&2
        wait "$pid" || true
        exit 1
    fi
    if [ "$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' --max-time 1 http://127.0.0.1:8080/)" = "404" ]; then
        server_ready=true
        break
    fi
    sleep 1
done
if [ "$server_ready" != true ]; then
    echo "Test server did not become ready within 60 seconds" >&2
    exit 1
fi
cd ../../
JAVA_HOME=/usr/java/jdk-15.0.1 PATH="/usr/java/jdk-15.0.1/bin:$PATH" ./gradlew clean build
if ! kill -0 "$pid" 2>/dev/null; then
    echo "Test server exited during the Android build" >&2
    wait "$pid" || true
    exit 1
fi
cd testHelpers/testapp
if ! JAVA_HOME=/usr/java/jdk-15.0.1 PATH="/usr/java/jdk-15.0.1/bin:$PATH" ./gradlew test; then
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "Test server exited during integration tests" >&2
        wait "$pid" || true
    fi
    exit 1
fi
