const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

async function main(args = process.argv.slice(2), executeUpgrade) {
    const [mode, repository, ...options] = args;
    if (!['ordinary', 'retrofit', 'mutation', 'matrix', 'qualify', 'upgrade', 'exposed'].includes(mode) || !repository ||
        !process.env.ST_TEST_RUN_ID || !process.env.INSTALL_PATH) {
        throw new Error('Run through runOrdinary.js: node runOwnedTests.js ordinary|retrofit|mutation|matrix|qualify REPOSITORY');
    }
    const root = fs.realpathSync(repository);
    const backend = path.join(process.env.INSTALL_PATH, 'backend');
    const runtime = require(path.join(backend, 'coreRuntimeIdentity'));
    const qualification = require(path.join(backend, 'accountLinkingQualification'));
    const fetch = require(path.join(backend, 'node_modules/node-fetch'));
    const base = process.env.ST_TEST_BACKEND_URL;
    const env = process.env;
    let capabilities;
    const contextFile = mode === 'matrix' ? options[0] : undefined;
    if (mode === 'matrix' && options.length > 1) throw new Error('Matrix accepts at most one release-context path');
    const selected = require('./ownedExposure').select(mode, mode === 'matrix' ? [] : options, base);
    function run(command, args, cwd = root) {
        const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
        if (result.error) throw result.error;
        if (result.status !== 0) throw new Error(command + ' failed: ' + (result.signal || result.status));
    }
    async function request(route, body) {
        runtime.serverIdentity(process.env.INSTALL_PATH);
        const response = await fetch(base + route, { method: body === undefined ? 'GET' : 'POST',
            headers: { 'content-type': 'application/json', 'x-st-test-run-id': env.ST_TEST_RUN_ID },
            body: body === undefined ? undefined : JSON.stringify(body), timeout: 30000 });
        if (!response.ok) throw new Error(route + ': HTTP ' + response.status);
        return response;
    }
    if (['upgrade', 'exposed'].includes(mode)) {
        if (env.CORE_PROTOCOL_TESTS !== '1') throw new Error('Explicit CORE_PROTOCOL_TESTS=1 required');
        let exposedNodeVersion;
        if (selected.exposed) {
            const version = require(path.join(backend, 'node_modules/supertokens-node/lib/build/version')).version;
            const match = typeof version === 'string' && /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.exec(version);
            if (!match || !Number.isSafeInteger(Number(match[1])) || Number(match[1]) < 14) {
                throw new Error('Exposed cookie requires a valid Node SDK version with major >= 14');
            }
            const before = await (await request('/test/capabilities')).json();
            if (before.nodeVersion !== version) throw new Error('Exposed cookie Node SDK version changed or unavailable');
            if (before.ownedExposedCleanup !== true) {
                throw new Error('Exposed cookie cleanup/fixture lease contract not implemented; required lane blocked');
            }
            exposedNodeVersion = version;
        }
        await request('/beforeeach', {});
        await request('/startst', { accessTokenValidity: 3600, enableAntiCsrf: false, enableJWT: selected.exposed });
        const identity = qualification.identity();
        if (!identity) throw new Error('Exact instance unavailable');
        const observed = await (await request('/test/capabilities')).json();
        const flags = await (await request('/featureFlags')).json();
        if (observed.qualificationProxy !== false) throw new Error('Real nonproxy backend required');
        if (selected.route === '/login-2.18' && (!observed.coreReachable ||
            !Array.isArray(observed.coreCdiVersions) || !observed.coreCdiVersions.includes('2.18'))) {
            throw new Error('Historical route requires reachable Core advertising CDI 2.18');
        }
        if (flags.sessionJwt !== selected.exposed || !observed.compatible || (mode === 'upgrade' && !observed.mappedGoogleSession) ||
            (selected.exposed && observed.nodeVersion !== exposedNodeVersion)) {
            throw new Error('Selected exposure configuration unavailable; not a passing/skipped row');
        }
        if (JSON.stringify(identity) !== JSON.stringify(qualification.identity())) throw new Error('Instance changed during exposure qualification');
        fs.writeFileSync(path.join(env.INSTALL_PATH, 'exposure-config.json'), JSON.stringify({
            runId: env.ST_TEST_RUN_ID, instance: env.ST_TEST_INSTANCE_ID, identity, profile: mode, selected, capabilities: observed, flags
        }, null, 2));
        env.ST_UPGRADE_MODE = selected.mode;
        env.ST_UPGRADE_ROUTE = selected.route || '/login';
        runtime.serverIdentity(process.env.INSTALL_PATH);
        if (JSON.stringify(identity) !== JSON.stringify(qualification.identity())) throw new Error('Instance changed before exposure dispatch');
        if (executeUpgrade) {
            if (mode !== 'upgrade') throw new Error('Composite callback is upgrade-only');
            await executeUpgrade({ root, selected, identity, observed, flags, runtime, qualification });
        } else run(process.execPath, [path.join(root, 'testHelpers/testapp/runTests.js'), mode, ...selected.properties]);
        return;
    }
    if (['matrix', 'mutation', 'qualify'].includes(mode)) {
        if (env.CORE_PROTOCOL_TESTS !== '1') throw new Error('Explicit CORE_PROTOCOL_TESTS=1 required');
        await request('/beforeeach', {});
        await request('/startst', { accessTokenValidity: 3600, enableAntiCsrf: false });
        run(process.execPath, ['accountLinking.live.test.js'], backend);
        run(process.execPath, ['accountLinking.gate.js', ...(mode === 'qualify' ? ['--capability'] : [])], backend);
        const identity = qualification.identity();
        if (!identity) throw new Error('Exact instance unavailable');
        capabilities = await (await request('/test/capabilities')).json();
        const features = await (await request('/featureFlags')).json();
        if (capabilities.qualificationProxy !== false) throw new Error('Real nonproxy backend required');
        if (JSON.stringify(identity) !== JSON.stringify(qualification.identity())) throw new Error('Instance changed during qualification');
        fs.writeFileSync(path.join(env.INSTALL_PATH, 'qualified-instance.json'), JSON.stringify({ identity, capabilities, features }, null, 2));
        if (mode === 'mutation' && (!capabilities.mappedGoogleSession || !capabilities.nonPublicTenantSession)) {
            throw new Error('Six-method mutation lane requires mapped-owner and non-public-tenant capabilities; unavailable is not a pass');
        }
    }
    if (mode === 'qualify') return;
    if (mode === 'matrix') {
        await require('./ownedMatrix')({ root, backend, env, capabilities, contextFile });
        return;
    }
    for (const profile of [mode]) {
        run(process.execPath, [path.join(root, 'testHelpers/testapp/runTests.js'), profile]);
    }
}
module.exports = { main };
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
