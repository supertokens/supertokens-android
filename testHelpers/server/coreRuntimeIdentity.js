const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { execFileSync } = require("child_process");
const corePort = Number(process.env.ST_TEST_CORE_PORT || 9000);
const backendPort = Number(process.env.NODE_PORT || 8080);
const coreHost = process.env.ST_TEST_RUN_ID ? '127.0.0.1' : 'localhost';
const CORE_ARGS = [...(process.env.ST_TEST_RUN_ID ? ['-Dst.fixture.run=' + process.env.ST_TEST_RUN_ID] : []),
    "-Djava.security.egd=file:/dev/urandom", "-classpath", "./core/*:./plugin-interface/*", "io.supertokens.Main", "./", "DEV",
    'host=' + coreHost, 'port=' + corePort, "test_mode"];
const serverRecordPath = path.join(__dirname, ".express-runtime.json");
const coreRecordPath = path.join(__dirname, ".core-runtime-launch.json");
const hash = value => crypto.createHash("sha256").update(value).digest("hex");

function writeRecord(file, value) {
    const temporary = file + "." + process.pid + ".tmp";
    fs.writeFileSync(temporary, JSON.stringify(value), { mode: 0o600 });
    fs.renameSync(temporary, file);
}

function sdkDigest(root) {
    if (!root) {
        root = path.dirname(require.resolve("supertokens-node"));
        while (!fs.existsSync(path.join(root, "package.json"))) root = path.dirname(root);
    }
    const entries = [];
    function visit(directory) {
        for (const entry of fs.readdirSync(directory).sort()) {
            if (entry === "node_modules" || entry === ".git") continue;
            const file = path.join(directory, entry);
            if (fs.statSync(file).isDirectory()) visit(file);
            else if (/\.(js|json|node)$/.test(entry)) entries.push([path.relative(root, file), hash(fs.readFileSync(file))]);
        }
    }
    visit(root);
    return hash(JSON.stringify(entries));
}

function effectiveArtifacts(root) {
    const entries = [];
    const times = [];
    // Core loads EE JARs dynamically; they affect qualification even outside -classpath.
    for (const directory of ["core", "plugin-interface", "ee"]) {
        const folder = path.join(root, directory);
        if (directory === 'ee' && !fs.existsSync(folder)) continue;
        times.push(fs.statSync(folder).mtimeMs);
        for (const name of fs.readdirSync(folder).filter(value => /\.jar$/i.test(value)).sort()) {
            const file = path.join(folder, name);
            entries.push([directory + "/" + name, hash(fs.readFileSync(file))]);
            times.push(fs.statSync(file).mtimeMs);
        }
    }
    if (!entries.length) throw new Error("Empty Core classpath");
    return { digest: hash(JSON.stringify(entries)), times };
}

function inspect(pid, port = corePort) {
    const options = { encoding: "utf8", env: { ...process.env, LC_ALL: "C" }, stdio: ["ignore", "pipe", "pipe"] };
    const command = execFileSync("ps", ["-ww", "-p", String(pid), "-o", "args="], options).trim();
    const started = execFileSync("ps", ["-p", String(pid), "-o", "lstart="], options).trim();
    // Linux exposes unambiguous argv. The known macOS test launch contains no
    // whitespace inside arguments; ambiguous/custom launches fail exact validation.
    const argv = process.platform === "linux" ? fs.readFileSync("/proc/" + pid + "/cmdline", "utf8").split("\0").filter(Boolean) : command.split(/\s+/);
    let cwd;
    let listening;
    if (process.platform === "linux") {
        cwd = fs.realpathSync("/proc/" + pid + "/cwd");
        const inodes = new Set();
        for (const name of ["tcp", "tcp6"]) {
            const file = "/proc/" + pid + "/net/" + name;
            if (!fs.existsSync(file)) continue;
            for (const line of fs.readFileSync(file, "utf8").trim().split("\n").slice(1)) {
                const fields = line.trim().split(/\s+/);
                if (fields[1].endsWith(":" + port.toString(16).toUpperCase().padStart(4, "0")) && fields[3] === "0A") inodes.add(fields[9]);
            }
        }
        listening = fs.readdirSync("/proc/" + pid + "/fd").some(fd => {
            try {
                const match = fs.readlinkSync("/proc/" + pid + "/fd/" + fd).match(/^socket:\[(\d+)\]$/);
                return match && inodes.has(match[1]);
            } catch (_) { return false; }
        });
    } else if (process.platform === "darwin") {
        const directories = execFileSync("lsof", ["-a", "-p", String(pid), "-d", "cwd", "-Fn"], options);
        const entry = directories.split("\n").find(line => line.startsWith("n"));
        cwd = entry && fs.realpathSync(entry.slice(1));
        try {
            listening = execFileSync("lsof", ["-nP", "-a", "-p", String(pid), "-iTCP:" + port, "-sTCP:LISTEN", "-Fn"], options).includes(":" + port);
        } catch (_) { listening = false; }
    } else {
        throw new Error("Core runtime identity requires Linux or macOS");
    }
    return { cwd, command, argv, started, listening };
}

function validate(root, metadata, artifactTimes) {
    const argv = metadata.argv || metadata.command.split(/\s+/);
    if (metadata.cwd !== root || !metadata.listening || path.basename(argv[0]) !== "java" ||
        JSON.stringify(argv.slice(1)) !== JSON.stringify(CORE_ARGS)) {
        throw new Error("Core listener does not match the test installation launch");
    }
    const startedAt = Date.parse(metadata.started);
    if (!Number.isFinite(startedAt) || artifactTimes.some(time => time > startedAt + 1000)) {
        throw new Error("Core artifacts changed after launch or process start is unknown; restart the test Core before qualification");
    }
}

function runtimeIdentity(root) {
    root = fs.realpathSync(root);
    const pidText = fs.readFileSync(path.join(root, ".started", coreHost + '-' + corePort), "utf8").trim();
    if (!/^\d+$/.test(pidText)) throw new Error("Invalid test Core PID marker");
    const metadata = inspect(Number(pidText));
    const artifacts = effectiveArtifacts(root);
    validate(root, metadata, artifacts.times);
    if (fs.existsSync(coreRecordPath)) {
        const recorded = JSON.parse(fs.readFileSync(coreRecordPath, "utf8"));
        if (recorded.pid === Number(pidText) && recorded.started === metadata.started && recorded.artifacts !== artifacts.digest) {
            throw new Error("Core classpath differs from captured launch artifacts");
        }
    }
    return hash(JSON.stringify({ pid: pidText, root, artifacts: artifacts.digest, ...metadata }));
}

function recordServerStart() {
    const metadata = inspect(process.pid, backendPort);
    writeRecord(serverRecordPath, { pid: process.pid, started: metadata.started, cwd: metadata.cwd,
        argv: metadata.argv, sdkDigest: sdkDigest(), installation: process.env.INSTALL_PATH && fs.realpathSync(process.env.INSTALL_PATH) });
}

function verifyServer(record, metadata, digest, root) {
    if (record.installation !== root || record.started !== metadata.started || record.cwd !== metadata.cwd ||
        record.cwd !== fs.realpathSync(__dirname) || !metadata.listening ||
        JSON.stringify(record.argv) !== JSON.stringify(metadata.argv) || record.sdkDigest !== digest) {
        throw new Error("Express startup identity or loaded SDK implementation differs from current installation");
    }
}

function serverIdentity(root) {
    const record = JSON.parse(fs.readFileSync(serverRecordPath, "utf8"));
    const metadata = inspect(record.pid, backendPort);
    const digest = sdkDigest();
    verifyServer(record, metadata, digest, fs.realpathSync(root));
    return { sdkImplementationSha256: digest, expressRuntimeId: hash(JSON.stringify(record)) };
}

function recordCoreLaunch(pid, root, artifacts) {
    const metadata = inspect(pid);
    validate(fs.realpathSync(root), { ...metadata, listening: true }, []);
    writeRecord(coreRecordPath, { pid, root: fs.realpathSync(root), serverPid: process.pid,
        serverStarted: inspect(process.pid, backendPort).started, started: metadata.started, argv: metadata.argv, artifacts });
}

function sameOwnedCore(record, metadata) {
    return record.started === metadata.started && record.root === metadata.cwd &&
        JSON.stringify(record.argv) === JSON.stringify(metadata.argv);
}

async function shutdownOwnedCore(serverPid) {
    if (!fs.existsSync(coreRecordPath)) return { stopped: false, reason: "NO_OWNED_CORE" };
    const record = JSON.parse(fs.readFileSync(coreRecordPath, "utf8"));
    const server = JSON.parse(fs.readFileSync(serverRecordPath, "utf8"));
    if (record.serverPid !== Number(serverPid) || server.pid !== Number(serverPid) || record.serverStarted !== server.started ||
        !process.env.INSTALL_PATH || record.root !== fs.realpathSync(process.env.INSTALL_PATH)) {
        return { stopped: false, reason: "NOT_OWNED" };
    }
    const stillOwned = () => {
        let metadata;
        try { metadata = inspect(record.pid); } catch (error) {
            try { process.kill(record.pid, 0); } catch (probeError) {
                if (probeError.code === "ESRCH") return false;
                throw probeError;
            }
            throw error;
        }
        if (!sameOwnedCore(record, metadata)) throw new Error("Core PID reused or ownership changed; refusing shutdown");
        return true;
    };
    for (const [signal, duration] of [["SIGTERM", 5000], ["SIGKILL", 2000]]) {
        if (!stillOwned()) return { stopped: true };
        process.kill(record.pid, signal);
        const deadline = Date.now() + duration;
        while (Date.now() < deadline) {
            if (!stillOwned()) return { stopped: true };
            await new Promise(resolve => setTimeout(resolve, 100));
        }
    }
    throw new Error("Owned Core did not exit within bounded shutdown");
}

module.exports = { inspect, runtimeIdentity, validate, effectiveArtifacts, sdkDigest, serverIdentity,
    recordServerStart, verifyServer, recordCoreLaunch, shutdownOwnedCore, sameOwnedCore, CORE_ARGS };
