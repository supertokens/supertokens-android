function accept(profile, mode, report, route = '/login') {
    const upgrade = profile === 'upgrade';
    if (!['upgrade', 'exposed'].includes(profile) || (!upgrade && mode !== 'EXPOSED_COOKIE')) {
        throw new Error('Invalid exposure profile/configuration');
    }
    if (!['HEADER', 'COOKIE', 'EXPOSED_COOKIE'].includes(mode)) throw new Error('Explicit owned mode required');
    if (!['/login', '/login-2.18'].includes(route) || (route !== '/login' && (!upgrade || mode !== 'HEADER'))) {
        throw new Error('Invalid owned upgrade route/mode');
    }
    const names = upgrade ? ['LOGIN', 'REFRESH', 'SIGN_OUT', 'PAYLOAD_UPDATE'].map(flow =>
        'publishedSessionContinuesAgainstConfiguredFdiMember[' + mode + '-' + flow + '-' + route + ']') :
        ['OKHTTP', 'RETROFIT', 'URLCONNECTION'].map(transport => 'exposedCookieLifecycle[' + transport + ']');
    const klass = 'com.example.example.' + (upgrade ? 'PublishedUpgradeLiveTest' : 'ExposedCookieLiveTest');
    if (report.tests !== names.length || report.passed !== names.length || report.failed !== 0 || report.skipped !== 0 ||
        report.cases.length !== names.length || report.cases.some(row => row.className !== klass || row.result !== 'SUCCESS') ||
        JSON.stringify(report.cases.map(row => row.name).sort()) !== JSON.stringify(names.sort())) {
        throw new Error('Exposure acceptance requires exact selected rows; failures/skips are not capability exclusions');
    }
}
module.exports = { accept };
