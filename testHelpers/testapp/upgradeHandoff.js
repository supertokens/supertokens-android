const flows = ['LOGIN', 'REFRESH', 'SIGN_OUT', 'PAYLOAD_UPDATE'];
function normalize(value, allowScalar = false) {
    if (allowScalar && typeof value === 'string' && /^[2-5]$/.test(value)) {
        value = Object.fromEntries(flows.map(flow => [flow, flow === 'SIGN_OUT' ? 'ABSENT' : value]));
    }
    if (!value || typeof value !== 'object' || Array.isArray(value) ||
        JSON.stringify(Object.keys(value).sort()) !== JSON.stringify([...flows].sort()) ||
        flows.some(flow => typeof value[flow] !== 'string' ||
            (flow === 'SIGN_OUT' ? value[flow] !== 'ABSENT' : !/^[2-5]$/.test(value[flow])))) {
        throw new Error('publishedHandoff requires exact LOGIN/REFRESH/SIGN_OUT/PAYLOAD_UPDATE keys; SIGN_OUT must be ABSENT');
    }
    return Object.fromEntries(flows.map(flow => [flow, value[flow]]));
}
module.exports = { normalize, flows };
