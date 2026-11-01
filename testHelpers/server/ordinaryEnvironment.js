const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function digestTree(root) {
    const hash = crypto.createHash('sha256');
    function visit(directory) {
        for (const name of fs.readdirSync(directory).sort()) {
            const file = path.join(directory, name);
            if (fs.statSync(file).isDirectory()) visit(file);
            else { hash.update(path.relative(root, file) + '\0'); hash.update(fs.readFileSync(file)); }
        }
    }
    visit(root);
    return hash.digest('hex');
}

function snapshotDependencies(source, target) {
    const before = digestTree(source);
    fs.cpSync(source, target, { recursive: true, dereference: true });
    const copied = digestTree(target);
    if (copied !== before || digestTree(source) !== before) throw new Error('Dependencies changed during snapshot');
    return copied;
}

function environment(source, root, backendPort, corePort, runId, instance) {
    const env = {};
    if (source.CORE_PROTOCOL_TESTS !== undefined && !['0', '1'].includes(source.CORE_PROTOCOL_TESTS)) {
        throw new Error('CORE_PROTOCOL_TESTS must be explicitly 0 or 1');
    }
    for (const key of ['PATH', 'HOME', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'LANG', 'LC_ALL']) {
        if (source[key] !== undefined) env[key] = source[key];
    }
    return { ...env, TMPDIR: root, GRADLE_USER_HOME: path.join(root, 'gradle-home'),
        TEST_MODE: 'testing', CORE_PROTOCOL_TESTS: source.CORE_PROTOCOL_TESTS === '1' ? '1' : '0',
        CORE_PROTOCOL_URL: 'http://127.0.0.1:' + backendPort,
        INSTALL_PATH: root, NODE_PORT: String(backendPort),
        ST_TEST_CORE_PORT: String(corePort), ST_TEST_RUN_ID: runId, ST_TEST_INSTANCE_ID: instance,
        ST_TEST_JAVA: source.ST_TEST_JAVA || 'java', ST_TEST_BACKEND_URL: 'http://127.0.0.1:' + backendPort,
        CORE_CONNECTION_URI: 'http://127.0.0.1:' + corePort,
        ACCOUNT_LINKING_QUALIFICATION_FILE: path.join(root, 'account-linking-qualification.json') };
}

module.exports = { environment, snapshotDependencies, digestTree };
