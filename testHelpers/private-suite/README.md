# Private backend-suite environment

The legacy JVM tests hardcode ports 8080 and 9000 and call server helpers that stop Core processes listed under `INSTALL_PATH`. Run the backend and tests in a dedicated container and network, with a **copy** of the Core installation and no host mounts, published ports, host PID namespace, or shared network. Do not point `INSTALL_PATH` at a host or shared Core installation. This is separate from `run-cookie-instrumented`, which starts a host Node process and publishes an owned Core on loopback for the emulator.

The tested image is built for `linux/amd64` from `Dockerfile` here. Gradle 6.5 runs on Java 11; Core 8.0.0 requires Java 21. The test run used `supertokens-node` 16.7.0, copied from `testHelpers/server/node_modules`, and the candidate debug AAR. Record the hashes and versions of these inputs for each run.

From the repository root, after building the candidate AAR and installing the backend dependencies, the setup used was:

```sh
docker build --platform linux/amd64 -t base-android-regression-runtime:local testHelpers/private-suite
docker network create base-android-regression-suite-net
docker run -d --platform linux/amd64 --name base-android-regression-suite --network base-android-regression-suite-net base-android-regression-runtime:local sleep 86400
docker exec base-android-regression-suite mkdir -p /workspace/testHelpers/testapp /workspace/testHelpers/server /workspace/app/build/outputs/aar /core-install
tar -C testHelpers/testapp --exclude='./app/build' --exclude='./build' --exclude='./.gradle' --exclude='./app/src/androidTest' -cf - . | docker exec -i base-android-regression-suite tar -xf - -C /workspace/testHelpers/testapp
docker cp testHelpers/server/. base-android-regression-suite:/workspace/testHelpers/server/
docker cp app/build/outputs/aar/app-debug.aar base-android-regression-suite:/workspace/app/build/outputs/aar/app-debug.aar
```

Copy only `core`, `plugin-interface`, `plugin`, `temp`, and `version.yaml` from a built, compatible Core distribution into `/core-install`; do not copy `.started`, `config.yaml`, or a host database. Copy a Linux amd64 Java 21 runtime into `/opt/jre21` in the container. For example, use a temporary container made from `eclipse-temurin:21-jre-jammy` and `docker cp` its `/opt/java/openjdk` through an owned temporary directory. Then:

```sh
docker exec -d -w /workspace/testHelpers/server -e INSTALL_PATH=/core-install -e NODE_PORT=8080 -e TEST_MODE=testing -e PATH=/opt/jre21/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin base-android-regression-suite node index.js
docker exec base-android-regression-suite curl -fsS http://127.0.0.1:8080/featureFlags
docker exec -w /workspace/testHelpers/testapp base-android-regression-suite timeout 20m ./gradlew testDebugUnitTest --no-daemon --rerun-tasks
```

Run under an external time limit, save only redacted results, and inspect the test XML for counts and skips. On completion stop and remove only `base-android-regression-suite` and remove its dedicated network. The suite container can reach dependency repositories, but does not share host or other test processes. Its lifecycle is independent of the emulator.
