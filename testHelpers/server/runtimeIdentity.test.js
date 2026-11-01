const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const runtime = require("./coreRuntimeIdentity");

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "android-runtime-test-"));
try {
    for (const folder of ["core", "plugin-interface", "sdk"]) fs.mkdirSync(path.join(temporary, folder));
    fs.writeFileSync(path.join(temporary, "core", "main.jar"), "main");
    fs.writeFileSync(path.join(temporary, "core", "dependency.jar"), "dependency");
    fs.writeFileSync(path.join(temporary, "plugin-interface", "plugin.jar"), "plugin");
    const original = runtime.effectiveArtifacts(temporary).digest;
    fs.writeFileSync(path.join(temporary, "core", "dependency.jar"), "same-name-replaced");
    assert.notStrictEqual(runtime.effectiveArtifacts(temporary).digest, original);
    fs.writeFileSync(path.join(temporary, "core", "dependency.jar"), "dependency");
    fs.writeFileSync(path.join(temporary, "plugin-interface", "competing.JAR"), "extra");
    assert.notStrictEqual(runtime.effectiveArtifacts(temporary).digest, original);
    fs.unlinkSync(path.join(temporary, "plugin-interface", "competing.JAR"));
    assert.strictEqual(runtime.effectiveArtifacts(temporary).digest, original);
    fs.mkdirSync(path.join(temporary, 'ee'));
    fs.writeFileSync(path.join(temporary, 'ee', 'ee.jar'), 'feature-module');
    const withFeatures = runtime.effectiveArtifacts(temporary).digest;
    assert.notStrictEqual(withFeatures, original, 'Dynamically loaded EE module must be part of runtime identity');
    fs.writeFileSync(path.join(temporary, 'ee', 'ee.jar'), 'changed-feature-module');
    assert.notStrictEqual(runtime.effectiveArtifacts(temporary).digest, withFeatures);

    const sdk = path.join(temporary, "sdk");
    fs.writeFileSync(path.join(sdk, "package.json"), '{"version":"16.7.0"}');
    fs.writeFileSync(path.join(sdk, "index.js"), "exports.linkAccounts = () => 1;");
    const sdkBefore = runtime.sdkDigest(sdk);
    fs.writeFileSync(path.join(sdk, "index.js"), "exports.linkAccounts = () => 2;");
    assert.notStrictEqual(runtime.sdkDigest(sdk), sdkBefore, "Same version and exports must not hide altered SDK code");

    const started = "Thu Oct 29 15:09:40 2026";
    const metadata = { cwd: temporary, started, listening: true, argv: ["java", ...runtime.CORE_ARGS] };
    runtime.validate(temporary, metadata, [Date.parse(started) - 1000]);
    for (const classpath of ["other.jar:./core/*:./plugin-interface/*", "./core/*:./plugin-interface/*:other.jar", "./core/*:./plugin-interface/*-suffix"]) {
        const argv = [...metadata.argv];
        argv[3] = classpath;
        assert.throws(() => runtime.validate(temporary, { ...metadata, argv }, []));
    }
    assert.throws(() => runtime.validate(temporary, { ...metadata, argv: [...metadata.argv, "-classpath", "other.jar"] }, []));
    const server = { pid: 1, installation: temporary, started, cwd: fs.realpathSync(__dirname), argv: [process.execPath, "."], sdkDigest: sdkBefore };
    const running = { ...server, listening: true };
    runtime.verifyServer(server, running, sdkBefore, temporary);
    assert.throws(() => runtime.verifyServer(server, running, runtime.sdkDigest(sdk), temporary), /loaded SDK/);
    assert.throws(() => runtime.verifyServer(server, { ...running, started: "different" }, sdkBefore, temporary), /startup identity/);
    const owned = { root: temporary, started, argv: metadata.argv };
    assert.strictEqual(runtime.sameOwnedCore(owned, metadata), true);
    assert.strictEqual(runtime.sameOwnedCore(owned, { ...metadata, started: "reused PID" }), false);
    assert.strictEqual(runtime.sameOwnedCore(owned, { ...metadata, cwd: "/unrelated" }), false);
    console.log("Exact classpath, full artifact set, SDK implementation and startup/ownership identity tests passed");
} finally { fs.rmSync(temporary, { recursive: true, force: true }); }
