const { normalize } = require('../testapp/upgradeHandoff');
function select(profile, options, base) {
    if (!['upgrade', 'exposed'].includes(profile)) {
        if (options.length) throw new Error('Unexpected owned profile options');
        return { exposed: false, properties: [] };
    }
    if (profile === 'exposed') {
        if (options.length) throw new Error('Exposed profile accepts no options');
        return { exposed: true, mode: 'EXPOSED_COOKIE', properties: [] };
    }
    const [mode, fdi, login, handoff, refresh, route = '/login'] = options;
    if (![5, 6].includes(options.length) || !['HEADER', 'COOKIE', 'EXPOSED_COOKIE'].includes(mode) ||
        !['/login', '/login-2.18'].includes(route) || (route === '/login-2.18' && mode !== 'HEADER') ||
        !/^\d+\.\d+$/.test(fdi) || [login, refresh].some(value => !/^[2-5]$/.test(value))) {
        throw new Error('upgrade REPOSITORY HEADER|COOKIE|EXPOSED_COOKIE FDI LOGIN_VERSION HANDOFF_VERSION REFRESH_VERSION [/login|/login-2.18 (HEADER only)]');
    }
    const scalar = /^[2-5]$/.test(handoff);
    const handoffs = normalize(scalar ? handoff : JSON.parse(handoff), route === '/login');
    return { exposed: mode === 'EXPOSED_COOKIE', mode, route, properties: [
        '-Pupgrade.baseUrl=' + base, '-Pupgrade.fdiVersion=' + fdi,
        '-Pupgrade.expectedPublishedLoginVersion=' + login,
        ...(scalar ? ['-Pupgrade.expectedPublishedHandoffVersion=' + handoff] :
            ['-Pupgrade.expectedPublishedHandoffVersions=' + JSON.stringify(handoffs)]),
        '-Pupgrade.expectedCandidateRefreshVersion=' + refresh,
        ...(route === '/login-2.18' ? ['-Pupgrade.loginRoute=' + route] : [])
    ] };
}
module.exports = { select };
