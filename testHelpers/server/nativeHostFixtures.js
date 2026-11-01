const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function toolsFixture(root) {
    const directory = path.join(root, 'host-toolchain');
    fs.mkdirSync(path.join(directory, 'lib'), { recursive: true });
    for (const name of ['adb', 'aapt', 'apksigner']) fs.writeFileSync(path.join(directory, name), '#!/bin/sh\nexit 0\n', { mode: 0o700 });
    fs.writeFileSync(path.join(directory, 'lib/apksigner.jar'), 'host signer double');
    const helpers = path.join(root, 'testHelpers/upgradeapp/tools');
    fs.mkdirSync(helpers, { recursive: true });
    fs.copyFileSync(path.join(__dirname, '../upgradeapp/tools/native_device.py'), path.join(helpers, 'native_device.py'));
    const leaseRoot = path.join(root, 'host-device-leases'); fs.mkdirSync(leaseRoot, { mode: 0o700 });
    const config = { serial: 'host-device-double', deviceLeaseRoot: leaseRoot, backend: 'http://127.0.0.1:18081',
        adb: path.join(directory, 'adb'), aapt: path.join(directory, 'aapt'), apksigner: path.join(directory, 'apksigner') };
    for (const name of ['sdkApk', 'sdkTestApk', 'candidateApk', 'candidateTestApk', 'candidateBuildProvenance']) {
        config[name] = path.join(directory, name); fs.writeFileSync(config[name], 'host artifact double');
    }
    // Opaque bytes test pin transport only; these deliberately cannot pass compilation verification.
    const buildPath = path.join(directory, 'invalid-host-build-pin.txt'), buildBytes = 'NOT COMPILATION EVIDENCE';
    fs.writeFileSync(buildPath, buildBytes);
    config.nativeBuild = { path: buildPath, sha256: crypto.createHash('sha256').update(buildBytes).digest('hex') };
    const configFile = path.join(directory, 'config.json'); fs.writeFileSync(configFile, JSON.stringify(config));
    return { config, configFile };
}
module.exports = { toolsFixture };
