const path = require('path');
const { spawnSync } = require('child_process');

// Fixed implementation, not a verifier selected by receipt contents or a production callback.
function verifyNative(row, expected, directory) {
    // The bridge enforces the deadline and drains the verifier and signer together.
    const result = spawnSync('python3', [path.join(__dirname, 'native_verifier_process.py'), path.resolve(directory)], {
        input: JSON.stringify({ row, expected }), encoding: 'utf8',
        maxBuffer: 1024 * 1024, env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
    });
    if (result.error) throw result.error;
    let response;
    try { response = JSON.parse(result.stdout); }
    catch (_) { throw new Error('Native archive verifier failed: ' + (result.stderr || result.status)); }
    if (result.status !== 0 || response.error) {
        const error = new Error(response.error || 'Native archive verification failed');
        error.code = response.code || 'NATIVE_ARCHIVE_INVALID';
        throw error;
    }
    return response;
}

module.exports = { verifyNative };
