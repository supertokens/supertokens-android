const methods = ['payloadUpdateRetainsOwnerAndEmitsOnePayloadEvent', 'handlePayloadUpdateBecomesVisibleAfterRefresh',
    'logoutThenRejectedAccessRefreshClearsOnce', 'revokeAllThenRejectedAccessRefreshClearsOnce',
    'claims403NeitherRefreshesNorClears', 'nonPublicTenantLoginAndRefreshRetainTenantAndOwner'];
function accept(report) {
    if (report.tests !== 6 || report.passed !== 6 || report.skipped !== 0 || report.failed !== 0 ||
        report.cases.length !== 6 || report.cases.some(test => test.className !== 'com.example.example.BackendMutationCoverageTest' || test.result !== 'SUCCESS') ||
        JSON.stringify(report.cases.map(test => test.name).sort()) !== JSON.stringify(methods.slice().sort())) {
        throw new Error('Mutation acceptance requires the exact six methods, 6 passed, 0 skipped, 0 failed');
    }
}
module.exports = { accept, methods };
