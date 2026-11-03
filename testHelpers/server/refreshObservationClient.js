const fs = require('fs');
const path = require('path');
const { identity } = require('./ownedProcess');
const { ownedBackendRequest } = require('./ownedBackendRequest');
async function main() {
    const action = process.argv[2];
    if (!['arm', 'consume'].includes(action) || process.argv.length !== 3) throw new Error('Invalid action');
    const pid = Number(process.env.ST_TEST_BACKEND_PID);
    const fingerprint = process.env.ST_TEST_BACKEND_FINGERPRINT;
    const cwd = process.env.ST_TEST_BACKEND_CWD;
    const root = process.env.INSTALL_PATH;
    const runId = process.env.ST_TEST_RUN_ID;
    const instance = process.env.ST_TEST_INSTANCE_ID;
    const port = Number(process.env.NODE_PORT);
    if (!Number.isSafeInteger(pid) || pid <= 0 || !fingerprint || !root || !cwd ||
        fs.realpathSync(cwd) !== fs.realpathSync(path.join(root, 'backend'))) throw new Error('Invalid owned backend');
    const input = action === 'consume' ? fs.readFileSync(0, 'utf8').trim() : undefined;
    const owner = { runId, child: { pid }, launch: { cwd: fs.realpathSync(cwd) }, verify() {
        if (identity(pid) !== fingerprint) throw new Error('Backend identity changed');
    } };
    const result = await ownedBackendRequest(owner, port, instance, '/test/refresh-observation/' + action, undefined,
        action === 'arm' ? { action } : { action, generation: input });
    const parsed = JSON.parse(result.body);
    if (!parsed || typeof parsed.generation !== 'string' || !/^[a-f0-9]{48}$/.test(parsed.generation)) throw new Error('Invalid observation');
    const output = { generation: parsed.generation };
    if (action === 'consume') {
        const value = parsed.observation;
        if (!value || !['refreshCookie', 'refreshHeader', 'authorization'].every(key => typeof value[key] === 'boolean') ||
            !Number.isInteger(value.status) || value.status < 100 || value.status > 599 ||
            !['absent', 'remove', 'other'].includes(value.frontToken)) throw new Error('Invalid observation');
        output.observation = { refreshCookie: value.refreshCookie, refreshHeader: value.refreshHeader,
            authorization: value.authorization, status: value.status, frontToken: value.frontToken };
    }
    process.stdout.write(JSON.stringify(output));
}
if (require.main === module) main().catch(() => { console.error('Owned observation request failed'); process.exitCode = 1; });
module.exports = { main };
