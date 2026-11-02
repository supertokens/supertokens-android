const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn, spawnSync } = require("child_process");

const args = process.argv.slice(2);
assert(args.length === 0 || (args.length === 1 && args[0] === "--startup-hook-regression-child"), "Unexpected arguments");
const regressionChild = args.length === 1;

function missingPidDiagnostic(result, elapsedMs, env) {
    const secrets = Object.entries(env)
        .filter(([key, value]) => /secret|token|api[_-]?key|password|passwd|credential|private[_-]?key|authorization/i.test(key) && value)
        .map(([, value]) => String(value))
        .sort((a, b) => b.length - a.length);
    const sanitize = value => {
        let text = String(value ?? "");
        for (const secret of secrets) text = text.split(secret).join("<REDACTED>");
        return text.slice(0, 2048);
    };
    return `PID file missing: spawn error code=${sanitize(result.error?.code)} message=${sanitize(result.error?.message)} ` +
        `status=${result.status} signal=${result.signal} elapsedMs=${Math.round(elapsedMs)} stderr=${sanitize(result.stderr)}`;
}

function reportMissingPid(pidFile, result, elapsedMs, env, report = console.error) {
    if (!fs.existsSync(pidFile)) report(missingPidDiagnostic(result, elapsedMs, env));
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "android-ci-cleanup-"));
const unrelated = spawn("sleep", ["60"]);
const alive = pid => { try { process.kill(pid, 0); return true; } catch (_) { return false; } };
try {
    const missingSpawn = spawnSync(path.join(temporary, "nonexistent-command"));
    assert.strictEqual(missingSpawn.error.code, "ENOENT");
    let diagnostic;
    reportMissingPid(path.join(temporary, "missing.pids"), {
        ...missingSpawn, error: { ...missingSpawn.error, message: missingSpawn.error.message + " fixture-secret" },
        stderr: "fixture-secret" + "x".repeat(3000),
    }, 12, { API_KEY: "fixture-secret" }, message => { diagnostic = message; });
    assert.match(diagnostic, /spawn error code=ENOENT message=.*ENOENT/);
    assert.match(diagnostic, /status=null signal=null elapsedMs=12 stderr=<REDACTED>/);
    assert(!diagnostic.includes("fixture-secret"));
    assert(diagnostic.length < 2300);

    const bin = path.join(temporary, "bin");
    fs.mkdirSync(bin);
    fs.writeFileSync(path.join(bin, "curl"), `#!/bin/bash
set -eu
kill -0 "$EXPECTED_EXPRESS"
case "$*" in *"serverPid\\\":$EXPECTED_EXPRESS"*) ;; *) exit 22 ;; esac
if [ "$CLEANUP_PHASE" = qualification ] || [ "$FAIL_CORE_CLEANUP" = 1 ]; then exit 22; fi
echo endpoint >> "$CLEANUP_LOG"
kill -TERM "$EXPECTED_CORE"
`, { mode: 0o755 });
    fs.writeFileSync(path.join(bin, "node"), `#!/bin/bash
set -eu
[ "$2" = "$EXPECTED_EXPRESS" ]
echo fallback >> "$CLEANUP_LOG"
if [ "$FAIL_CORE_CLEANUP" = 1 ]; then exit 23; fi
kill -TERM "$EXPECTED_CORE"
`, { mode: 0o755 });
    for (const [phase, exitCode, cleanupFails] of [["after-startst", 37, false], ["qualification", 38, false], ["gradle-boundary", 39, false],
        ["express-exited", 40, false], ["successful", 0, false], ["cleanup-failed-zero", 1, true], ["cleanup-failed-37", 37, true],
        ["directory-missing-zero", 1, true]]) {
        const pidFile = path.join(temporary, phase + ".pids");
        const log = path.join(temporary, phase + ".log");
        const script = `set -euo pipefail
sleep 60 >/dev/null 2>&1 &
pid=$!
sleep 60 >/dev/null 2>&1 &
core_pid=$!
server_directory="$SERVER_DIRECTORY"
source "$SERVER_DIRECTORY/ciCleanup.sh"
trap cleanup_test_backend EXIT
export EXPECTED_EXPRESS="$pid" EXPECTED_CORE="$core_pid"
printf '%s %s' "$pid" "$core_pid" > "$PID_FILE"
# Stub pipeline stages: no Gradle, real server, or real Core is invoked.
if [ "$CLEANUP_PHASE" = directory-missing-zero ]; then server_directory="$PID_FILE.missing"; exit 0; fi
if [ "$CLEANUP_PHASE" = successful ] || [ "$CLEANUP_PHASE" = cleanup-failed-zero ]; then exit 0; fi
if [ "$CLEANUP_PHASE" = cleanup-failed-37 ]; then exit 37; fi
if [ "$CLEANUP_PHASE" = after-startst ]; then exit 37; fi
if [ "$CLEANUP_PHASE" = express-exited ]; then
    kill -TERM "$pid"
    wait "$pid" || true
    exit 40
fi
fake_qualification() { if [ "$CLEANUP_PHASE" = qualification ]; then return 38; fi; }
fake_gradle_boundary() { return 39; }
fake_qualification
fake_gradle_boundary
`;
        // Ambient shell startup must not inspect the fixture's fake PATH.
        const fixtureEnv = { ...process.env };
        delete fixtureEnv.BASH_ENV;
        delete fixtureEnv.ENV;
        const started = process.hrtime.bigint();
        const result = spawnSync("bash", ["-c", script], { encoding: "utf8", timeout: 20000,
            env: { ...fixtureEnv, PATH: bin + path.delimiter + process.env.PATH, SERVER_DIRECTORY: __dirname,
                PID_FILE: pidFile, CLEANUP_PHASE: phase, CLEANUP_LOG: log, FAIL_CORE_CLEANUP: cleanupFails ? "1" : "0" } });
        const elapsedMs = Number(process.hrtime.bigint() - started) / 1e6;
        reportMissingPid(pidFile, result, elapsedMs, process.env);
        const pids = fs.readFileSync(pidFile, "utf8").split(" ").map(Number);
        try {
            assert.ifError(result.error);
            assert.strictEqual(result.status, exitCode, result.stderr);
            assert.strictEqual(alive(pids[0]), false, "Owned Express survived " + phase);
            assert.strictEqual(alive(pids[1]), cleanupFails, "Core cleanup fault outcome differs for " + phase);
            assert.strictEqual(alive(unrelated.pid), true, "Unrelated process was touched");
            if (phase === "directory-missing-zero") {
                assert.strictEqual(fs.existsSync(log), false);
                assert.match(result.stderr, /Cannot enter backend cleanup directory/);
            } else {
                assert.strictEqual(fs.readFileSync(log, "utf8").trim(), cleanupFails || ["qualification", "express-exited"].includes(phase) ? "fallback" : "endpoint");
                if (cleanupFails) assert.match(result.stderr, /Owned Core fallback cleanup failed/);
            }
        } finally {
            for (const pid of pids) if (alive(pid)) process.kill(pid, "SIGKILL");
        }
    }
    console.log("CI cleanup: original failure preserved; zero becomes failure on cleanup error; Express termination always attempted; success stays zero");
    if (!regressionChild) {
        const hook = path.join(temporary, "startup-hook.sh");
        const marker = path.join(temporary, "startup-hook-ran");
        const hookPids = path.join(temporary, "startup-hook-pids");
        fs.writeFileSync(hook, `if [ "\${CI_CLEANUP_HOOK_ACTIVE:-}" != 1 ]; then
    export CI_CLEANUP_HOOK_ACTIVE=1
    printf 'hook\\n' >> "$CI_CLEANUP_HOOK_MARKER"
    node --version >/dev/null 2>&1 &
    hook_pid=$!
    printf '%s\\n' "$hook_pid" >> "$CI_CLEANUP_HOOK_PIDS"
    wait "$hook_pid" || :
fi
`);
        const bashEnv = process.env.BASH_ENV;
        const env = process.env.ENV;
        const childEnv = { ...process.env, BASH_ENV: hook, ENV: hook,
            CI_CLEANUP_HOOK_MARKER: marker, CI_CLEANUP_HOOK_PIDS: hookPids };
        delete childEnv.CI_CLEANUP_HOOK_ACTIVE;
        const result = spawnSync(process.execPath, [__filename, "--startup-hook-regression-child"],
            { encoding: "utf8", timeout: 180000, env: childEnv });
        assert.ifError(result.error);
        assert.strictEqual(result.status, 0, result.stderr);
        assert.match(result.stdout, /CI cleanup: original failure preserved/);
        if (fs.existsSync(hookPids)) {
            for (const pid of fs.readFileSync(hookPids, "utf8").trim().split("\n").map(Number)) {
                assert.strictEqual(alive(pid), false, "Startup inspection process survived");
            }
        }
        assert.strictEqual(fs.existsSync(marker), false, "Ambient shell startup ran against fixture PATH");
        assert.strictEqual(process.env.BASH_ENV, bashEnv);
        assert.strictEqual(process.env.ENV, env);
    }
} finally {
    unrelated.kill();
    fs.rmSync(temporary, { recursive: true, force: true });
}
