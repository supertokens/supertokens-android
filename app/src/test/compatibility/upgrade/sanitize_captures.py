"""Transform private device captures; never print values or copy raw credentials to staged fixtures."""
import base64
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import uuid
import xml.etree.ElementTree as ET

def bundle_identifier(value):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,127}', value):
        raise argparse.ArgumentTypeError('Expected a bundle identifier, not a path')
    return value


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', required=True, type=Path, help='Private device evidence root')
parser.add_argument('--bundle-id', required=True, type=bundle_identifier, help='Non-sensitive evidence bundle identifier')
parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[5] / 'app/src/test/resources/upgrade')
args = parser.parse_args()
SOURCE = args.source.resolve()
DEST = args.output.resolve()
SESSION = 'supertokens-android-shared-preferences'
COOKIE = 'supertokens-cookie-shared-prefs'
FRONT = 'supertokens-android-fronttoken-key'
ACCESS = 'st-storage-item-st-access-token'
REFRESH = 'st-storage-item-st-refresh-token'
CSRF = 'supertokens-android-anticsrf-key'
MARKER = 'st-storage-item-st-last-access-token-update'
PREFIX = 'supertokens-android-session-'
rows = []
identifiers = {}
secrets = {}
raw_sensitive = set()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pseudonym(value):
    if value not in identifiers:
        try:
            uuid.UUID(value)
            identifiers[value] = str(uuid.UUID(int=len(identifiers) + 1))
        except ValueError:
            identifiers[value] = 'fixture-id-%04d' % (len(identifiers) + 1)
    raw_sensitive.add(value)
    return identifiers[value]


def secret(value):
    if value not in secrets:
        secrets[value] = 'INVALID-FIXTURE-SECRET-%04d' % (len(secrets) + 1)
    raw_sensitive.add(value)
    return secrets[value]


def decode(value, url=False):
    return json.loads((base64.urlsafe_b64decode if url else base64.b64decode)(value + '==='))


def encode(value, url=False):
    data = json.dumps(value, separators=(',', ':'), ensure_ascii=True).encode()
    result = (base64.urlsafe_b64encode if url else base64.b64encode)(data).decode()
    return result.rstrip('=') if url else result


def fingerprint(values):
    h = hashlib.sha256()
    for key in [ACCESS, REFRESH, FRONT, CSRF, MARKER]:
        value = values.get(key)
        part = 'null' if value is None or value == '' else 'java.lang.String:' + value
        data = part.encode()
        h.update(str(len(data)).encode() + b':' + data)
    return h.hexdigest()


def values(tree):
    return {e.attrib['name']: e.attrib.get('value', e.text or '') for e in tree}


for scenario in ['login-b', 'refresh', 'logout', 'cookie']:
    run = json.loads((SOURCE / ('device-' + scenario) / 'run.json').read_text())
    captured = {item['file']: item['sha256'] for item in json.loads((SOURCE / ('device-' + scenario) / 'captures.json').read_text())}
    for artifact in ['sdk', 'candidate']:
        assert sha(Path(run['artifacts'][artifact]['path']).read_bytes()) == run['artifacts'][artifact]['sha256']
    for phase in ['seed', 'check-initial', 'mutate', 'check-final', 'cleanup']:
        flavor = 'sdk' if phase in ['seed', 'mutate'] else 'candidate'
        row = dict(scenario=scenario, phase=phase, flavor=flavor, files={}, original={})
        for name in [SESSION, 'upgrade-receipt'] + ([COOKIE] if scenario == 'cookie' else []):
            source = SOURCE / ('device-' + scenario) / (scenario + '-' + phase + '-' + name + '.xml')
            assert sha(source.read_bytes()) == captured[source.name], 'Private capture changed since recorded device execution'
            tree = ET.parse(source).getroot()
            row['original'][name] = values(tree)
            row['files'][name] = dict(tree=tree, source=source, sourceSha256=sha(source.read_bytes()), apkSha256=run['artifacts'][flavor]['sha256'])
        rows.append(row)

# A common shift preserves all relative times, including front/JWT expiry equality.
earliest = min(decode(row['original'][SESSION][FRONT])['ate'] for row in rows if FRONT in row['original'][SESSION])
SHIFT_SECONDS = 4102448400 - earliest // 1000


def payload(value):
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if item is None:
                result[key] = None
            elif key in ['uid', 'sub', 'rsub', 'sessionHandle', 'userId']:
                result[key] = pseudonym(item)
            elif key in ['refreshTokenHash1', 'parentRefreshTokenHash1', 'antiCsrfToken']:
                result[key] = sha(secret(item).encode()) if 'Hash' in key else secret(item)
            elif key in ['exp', 'iat']:
                result[key] = item + SHIFT_SECONDS
            elif key in ['ate', 'expiryTime', 'timeCreated']:
                result[key] = item + SHIFT_SECONDS * 1000
            elif key == 'iss':
                result[key] = 'http://fixture.invalid'
            else:
                result[key] = payload(item)
        return result
    if isinstance(value, list):
        return [payload(item) for item in value]
    return value


def access(value):
    raw_sensitive.add(value)
    header, body, signature = value.split('.')
    h = decode(header, True)
    if 'kid' in h:
        h['kid'] = pseudonym(h['kid'])
    return encode(h, True) + '.' + encode(payload(decode(body, True)), True) + '.' + base64.urlsafe_b64encode(secret(signature).encode()).decode().rstrip('=')


def cookie(value):
    data = bytes.fromhex(value)
    # The SDK writes name then value as TC_STRING objects after its class descriptor.
    for name in ['sAccessToken', 'sRefreshToken']:
        encoded = name.encode()
        needle = b'\x74' + struct.pack('>H', len(encoded)) + encoded
        index = data.find(needle)
        if index < 0:
            continue
        start = index + len(needle)
        assert data[start] == 0x74, 'Expected serialized string cookie value'
        size = struct.unpack('>H', data[start + 1:start + 3])[0]
        original = data[start + 3:start + 3 + size].decode('ascii')
        replacement = (access(original) if name == 'sAccessToken' else secret(original)).encode('ascii')
        transformed = data[:start + 1] + struct.pack('>H', len(replacement)) + replacement + data[start + 3 + size:]
        return transformed.hex()
    raise ValueError('Unexpected cookie name; explicit sanitizer required')


for row in rows:
    for name, info in row['files'].items():
        for element in info['tree']:
            key = element.attrib['name']
            if element.tag != 'string':
                continue
            value = element.text or ''
            if name == COOKIE:
                element.text = cookie(value)
            elif key == ACCESS:
                element.text = access(value)
            elif key == REFRESH or key == CSRF:
                element.text = secret(value)
            elif key == FRONT:
                raw_sensitive.add(value)
                element.text = encode(payload(decode(value)))
            elif key in [MARKER, PREFIX + 'legacy-marker', PREFIX + 'expiry']:
                element.text = str(int(value) + SHIFT_SECONDS * 1000) if int(value) else value
            elif key in [PREFIX + 'user', PREFIX + 'handle', PREFIX + 'adoption', 'userId', 'handle', 'fixtureId', 'process', 'runId', 'lastRequestId']:
                element.text = pseudonym(value)
            elif key == 'owners':
                element.text = json.dumps([pseudonym(x) for x in json.loads(value)], separators=(',', ':'))
            elif key == 'backend':
                element.text = 'http://fixture.invalid'

def credentials(session, cookies):
    token_values = dict(accessDigest=session.get(ACCESS), refreshDigest=session.get(REFRESH))
    if cookies is not None:
        for value in cookies.values():
            data = bytes.fromhex(value)
            for name, key in [('sAccessToken', 'accessDigest'), ('sRefreshToken', 'refreshDigest')]:
                needle = b'\x74' + struct.pack('>H', len(name)) + name.encode()
                index = data.find(needle)
                if index >= 0:
                    start = index + len(needle)
                    size = struct.unpack('>H', data[start + 1:start + 3])[0]
                    token_values[key] = data[start + 3:start + 3 + size].decode('ascii')
    return token_values


# A cleanup receipt intentionally retains the previous stage's credential digests.
# Resolve every receipt against all observed credentials, never the current stage alone.
digest_map = {}
for row in rows:
    original_tokens = credentials(row['original'][SESSION], row['original'].get(COOKIE))
    sanitized_tokens = credentials(values(row['files'][SESSION]['tree']),
                                   values(row['files'][COOKIE]['tree']) if COOKIE in row['files'] else None)
    for key, original in original_tokens.items():
        if original:
            old, new = sha(original.encode()), sha(sanitized_tokens[key].encode())
            assert old not in digest_map or digest_map[old] == new, 'Inconsistent credential transform'
            digest_map[old] = new

historical_digests = 0
for row in rows:
    original_tokens = credentials(row['original'][SESSION], row['original'].get(COOKIE))
    for element in row['files']['upgrade-receipt']['tree']:
        key = element.attrib['name']
        if key in original_tokens:
            original_digest = row['original']['upgrade-receipt'][key]
            if original_digest:
                assert original_digest in digest_map, 'Unresolved historical receipt digest'
                element.text = digest_map[original_digest]
                token = original_tokens[key]
                if not token or sha(token.encode()) != original_digest:
                    historical_digests += 1
            else:
                element.text = ''
assert historical_digests == 6, 'Expected six historical nonempty cleanup digests'

# Preserve whether tuple evidence matched. Never "repair" stale metadata into coherence.
fingerprints = {}
for row in rows:
    original = row['original'][SESSION]
    fingerprints[fingerprint(original)] = fingerprint(values(row['files'][SESSION]['tree']))
for row in rows:
    original = row['original'][SESSION]
    for element in row['files'][SESSION]['tree']:
        if element.attrib['name'] == PREFIX + 'fingerprint':
            element.text = fingerprints.get(element.text, sha(('stale-sanitized-evidence:' + element.text).encode()))
    updated = values(row['files'][SESSION]['tree'])
    if PREFIX + 'fingerprint' in original:
        assert (original[PREFIX + 'fingerprint'] == fingerprint(original)) == (updated[PREFIX + 'fingerprint'] == fingerprint(updated))

manifest = dict(schema=1, sourceEvidenceRoot=args.bundle_id, transform='app/src/test/compatibility/upgrade/sanitize_captures.py',
                transformSha256=sha(Path(__file__).read_bytes()), shiftSeconds=SHIFT_SECONDS,
                candidateAarSha256='9d4aaaaf43e6a72d4ab3f5d2621c157301b3da600a9128341cc6503747921ac1',
                publishedAarSha256='2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9',
                sanitizedNotBitIdentical=True, historicalReceiptDigests=historical_digests, cases=[])
for row in rows:
    folder = ('0.5.4' if row['flavor'] == 'sdk' else 'candidate') + '/' + row['scenario'] + '/' + row['phase']
    case = dict(scenario=row['scenario'], phase=row['phase'], folder=folder, files={})
    current = values(row['files'][SESSION]['tree'])
    original = row['original'][SESSION]
    coherent = PREFIX + 'fingerprint' in original and original[PREFIX + 'fingerprint'] == fingerprint(original)
    available = FRONT in current
    case.update(available=available, originalEvidence='coherent' if coherent else 'missing' if PREFIX + 'fingerprint' not in original else 'stale',
                expectedValidation=current.get(PREFIX + 'validation') if coherent else 'legacy' if available else 'strict',
                expectedInitialWrites=0 if coherent else 1)
    if available:
        front = decode(current[FRONT])
        case.update(userId=front['uid'], expiry=front['ate'], tokenVersion='5', handle=front['up'].get('sessionHandle'))
    for name, info in row['files'].items():
        target = DEST / folder / (name + '.xml')
        target.parent.mkdir(parents=True, exist_ok=True)
        ET.indent(info['tree'])
        ET.ElementTree(info['tree']).write(target, encoding='utf-8', xml_declaration=True)
        with target.open('ab') as output:
            output.write(b'\n')
        assert [(e.tag, e.attrib['name']) for e in info['tree']] == [(e.tag, e.attrib['name']) for e in ET.parse(info['source']).getroot()]
        case['files'][name] = dict(source=str(info['source'].relative_to(SOURCE)), sourceSha256=info['sourceSha256'],
                                 sanitizedSha256=sha(target.read_bytes()), apkSha256=info['apkSha256'],
                                 types={e.attrib['name']:e.tag for e in info['tree']})
    manifest['cases'].append(case)
(DEST / 'capture-provenance.json').write_text(json.dumps(manifest, indent=2) + '\n')

# Scan plain XML and decoded token/cookie representations, not just visible encodings.
outputs=[]
for row in rows:
    for name, info in row['files'].items():
        for e in info['tree']:
            value=e.text or ''
            outputs.append(value)
            if e.attrib['name'] == ACCESS:
                outputs += [base64.urlsafe_b64decode(part+'===').decode() for part in value.split('.')]
            elif e.attrib['name'] == FRONT:
                outputs.append(base64.b64decode(value).decode())
            elif name == COOKIE:
                raw=bytes.fromhex(value)
                outputs.append(raw.decode('latin1'))
for secret_value in raw_sensitive:
    if len(secret_value) >= 8:
        assert not any(secret_value in output for output in outputs), 'Raw sensitive value survived sanitization'
print('Sanitized 45 typed XML captures; 20 replay cases; six historical cleanup digests preserved; raw-value scan passed')
