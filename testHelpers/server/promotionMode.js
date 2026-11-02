module.exports = function promotionMode(sdkVersions, coreVersions) {
    if (!Array.isArray(sdkVersions) || !Array.isArray(coreVersions)) throw new Error('Missing CDI versions');
    const versions = sdkVersions.filter(value => typeof value === 'string' && /^\d+\.\d+$/.test(value) && coreVersions.includes(value));
    versions.sort((a, b) => {
        const [am, an] = a.split('.').map(Number), [bm, bn] = b.split('.').map(Number);
        return bm - am || bn - an;
    });
    if (!versions.length) throw new Error('No compatible CDI');
    const [major, minor] = versions[0].split('.').map(Number);
    return major > 5 || (major === 5 && minor >= 6) ? 'payload' : 'child';
};
