"""Verify backend-owned native observer records; never consume device-uploaded traces."""
import hashlib
import json
import os
from pathlib import Path
import re

from matrix_native import require


def digest(value):
    return hashlib.sha256(json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def private_read(directory, name):
    directory = Path(directory)
    require(directory.is_absolute() and directory.resolve() == directory, 'Noncanonical backend evidence directory')
    for part in [directory, directory.parent]:
        stat = part.lstat()
        require(not part.is_symlink() and part.is_dir() and stat.st_uid == os.getuid() and stat.st_mode & 0o077 == 0,
                'Backend evidence directory not private/owned')
    file = directory / name
    stat = file.lstat()
    require(file.is_file() and not file.is_symlink() and stat.st_nlink == 1 and stat.st_uid == os.getuid() and
            stat.st_mode & 0o077 == 0 and stat.st_size <= 32 * 1024 * 1024, 'Backend evidence file not private/owned/bounded')
    return file.read_bytes()


def verify_backend(owned, source, expected, stages, producer_started, producer_ended=None, archive=None):
    owned = Path(owned)
    require(owned.is_absolute() and owned.resolve() == owned, 'Owned backend root is not canonical')
    owner = json.loads((owned / 'owner.json').read_bytes())
    require(owner['root'] == str(owned) and owner['runId'] == source['runId'], 'Backend owner changed')
    directory = owned / 'native-evidence' / source['nativeNonce']
    control = json.loads(private_read(directory, 'control.json'))
    require(control['schema'] == 1 and control['kind'] == 'native-backend-control' and all(control[name] == source[name]
            for name in ['runId', 'instance', 'nativeNonce', 'challenge']), 'Backend challenge control does not match device')
    require(re.fullmatch('[a-f0-9]{48}', control['finalizeKey']) and
            len({control['nativeNonce'], control['challenge'], control['finalizeKey']}) == 3 and
            control['finalizeKey'] not in json.dumps(source), 'Challenge independence/finalize-key secrecy violated')
    data = private_read(directory, 'events.jsonl')
    complete_bytes = private_read(directory, 'complete.json')
    complete = json.loads(complete_bytes)
    if archive is not None:
        require(private_read(archive, 'events.jsonl') == data and private_read(archive, 'complete.json') == complete_bytes,
                'Sibling backend archive differs from actual owned source')
    events = [json.loads(line) for line in data.splitlines()]
    require(complete['schema'] == 1 and complete['kind'] == 'native-backend-complete' and
            complete['eventsSha256'] == hashlib.sha256(data).hexdigest() and complete['eventCount'] == len(events) and
            all(complete[name] == source[name] for name in ['runId', 'instance', 'nativeNonce']), 'Backend completion hash/binding mismatch')
    require(events and events[0]['type'] == 'row-open' and events[-1]['type'] == 'row-complete' and
            sum(item['type'] == 'row-open' for item in events) == sum(item['type'] == 'row-complete' for item in events) == 1,
            'Backend row not finalized exactly once')
    binding = expected['binding']
    identity = complete['identity']
    require(digest(identity) == binding['qualifiedIdentitySha256'] == complete['qualifiedIdentitySha256'] and
            identity['nodeSdkVersion'] == binding['nodeVersion'] and identity['coreVersion'] == binding['coreVersion'] and
            identity['sdkImplementationSha256'] == binding['nodeSdkSha256'] and identity['coreSha256'] == binding['coreSha256'] and
            identity['effectiveClasspathSha256'] == binding['coreManifestSha256'] and
            complete['dependenciesSha256'] == binding['dependenciesSha256'] and complete['coreManifestSha256'] == binding['coreManifestSha256'],
            'Actual backend Node/Core identity differs')
    require((owned / 'dependencies.sha256').read_text().strip() == binding['dependenciesSha256'], 'Owned dependency snapshot changed')
    exposure = json.loads((owned / 'exposure-config.json').read_bytes())
    require(exposure['identity'] == identity and exposure['runId'] == source['runId'] and exposure['instance'] == source['instance'],
            'Backend challenge differs from qualified exposure instance')
    identity_fields = ['identity', 'qualifiedIdentitySha256', 'dependenciesSha256', 'coreManifestSha256']
    allowed = {'row-open', 'request', 'response-finish', 'response-close', 'challenge', 'fixture-created',
               'login-session', 'session-state', 'owner-cleanup', 'row-complete'}
    last = producer_started
    requests, domain = [], []
    by_id = {}
    for index, event in enumerate(events):
        require(event['schema'] == 1 and event['sequence'] == index + 1 and event['type'] in allowed and
                all(event[name] == source[name] for name in ['runId', 'instance', 'nativeNonce']) and
                type(event['timeMs']) is int and last <= event['timeMs'] <= expected['endedMs'], 'Unordered/foreign/failed backend event')
        last = event['timeMs']
        kind = event['type']
        if kind in ['row-open', 'row-complete', 'challenge']:
            require(all(event[name] == complete[name] for name in identity_fields), 'Backend runtime drift during row')
        if kind == 'request':
            rid = event['requestId']
            require(type(rid) is int and rid == len(requests) + 1 and rid not in by_id, 'Duplicate/invalid backend request')
            by_id[rid] = dict(request=event, finish=None, close=None)
            requests.append(event)
        elif kind in ['response-finish', 'response-close']:
            require(event['requestId'] in by_id, 'Unowned backend response')
            entry = by_id[event['requestId']]
            if kind == 'response-finish':
                require(entry['finish'] is None and entry['close'] is None and event['statusCode'] == 200, 'Failed/duplicate backend finish')
                entry['finish'] = event
            else:
                require(entry['finish'] is not None and entry['close'] is None and event['afterFinish'] is True, 'Aborted/duplicate backend close')
                entry['close'] = event
        elif kind not in ['row-open', 'row-complete']:
            require(event['requestId'] in by_id and by_id[event['requestId']]['finish'] is None, 'Domain event outside actual request')
            domain.append(event)
    require(all(entry['finish'] and entry['close'] for entry in by_id.values()), 'Unfinished backend request')
    out = source['flow'] == 'SIGN_OUT'
    sequence = [('seed', 'POST', '/test/native/challenge'), ('seed', 'GET', '/test/capabilities'), ('seed', 'GET', '/featureFlags'),
                ('seed', 'POST', '/test/email-change-owner'), ('seed', 'POST', '/login'),
                ('seed', 'POST', '/auth/signout' if out else '/auth/session/refresh')]
    if out:
        sequence += [('seed', 'POST', '/test/owner-session-state')]
    sequence += [('check-initial', 'GET', '/test/capabilities'), ('check-initial', 'GET', '/featureFlags')]
    if out:
        sequence += [('check-initial', 'POST', '/login')]
    sequence += [('check-initial', method, route) for method, route in [('GET', '/'), ('POST', '/auth/session/refresh'), ('GET', '/'),
                 ('POST', '/update-jwt'), ('POST', '/auth/signout'), ('POST', '/test/owner-session-state')]]
    sequence += [('cleanup', 'POST', '/test/cleanup-owner'), ('cleanup', 'POST', '/test/native/finalize')]
    require(len(requests) == len(sequence), 'Unexpected backend wire exchanges')
    if producer_ended is not None:
        require(all(item['timeMs'] <= producer_ended for item in requests[:-1]) and requests[-1]['timeMs'] >= producer_ended,
                'Device requests/finalizer outside their actual execution intervals')
    for request, (stage, method, route) in zip(requests, sequence):
        headers = request['headers']
        required = {'x-st-test-run-id': source['runId'], 'x-st-native-nonce': source['nativeNonce'],
                    'x-st-native-stage': stage, 'fdi-version': source['context']['fdi']}
        require(request['method'] == method and request['path'] == route and all(headers.get(key) == value for key, value in required.items()) and
                set(headers) == set(required) | ({'x-st-native-fixture-id'} if route == '/login' else set()), 'Actual route/FDI/stage differs')
    groups = {name: [event for event in domain if event['type'] == name] for name in allowed}
    require(len(groups['challenge']) == 1 and len(groups['fixture-created']) == 1 and
            len(groups['login-session']) == (2 if out else 1) and len(groups['session-state']) == (2 if out else 1) and
            len(groups['owner-cleanup']) == 1, 'Missing/extra backend domain observations')
    challenge = groups['challenge'][0]
    require(by_id[challenge['requestId']]['request']['path'] == '/test/native/challenge', 'Challenge outside actual challenge request')
    fields = ['schema', 'kind', 'runId', 'instance', 'nativeNonce', 'challenge'] + identity_fields
    observed_challenge = {key: challenge[key] for key in fields}
    require(challenge['challenge'] == control['challenge'] and all(stage['challengeResponse'] == observed_challenge for stage in stages),
            'Device/backend challenge views disagree')
    fixture = groups['fixture-created'][0]
    require(fixture['creationRequestId'] == source['nativeNonce'] + '-owner' and
            by_id[fixture['requestId']]['request']['path'] == '/test/email-change-owner', 'Wrong provisioning request identity')
    logins = groups['login-session']
    handles = []
    for login in logins:
        req = by_id[login['requestId']]['request']
        require(login['fixtureId'] == fixture['fixtureId'] and login['userId'] == fixture['userId'] and req['path'] == '/login' and
                req['headers']['x-st-native-fixture-id'] == fixture['fixtureId'] and login['handle'] != fixture['provisioningHandle'] and
                login['handle'] not in handles, 'Wrong actual login owner/session handle')
        handles.append(login['handle'])
    for state, handle in zip(groups['session-state'], handles):
        require(state['fixtureId'] == fixture['fixtureId'] and state['handle'] == handle and state['active'] is False and
                by_id[state['requestId']]['request']['path'] == '/test/owner-session-state', 'Actual login not revoked before cleanup')
    client_states = [state for stage in stages for state in stage['revocationResponses']]
    require(client_states == [dict(fixtureId=event['fixtureId'], handle=event['handle'], owned=True, active=False)
                              for event in groups['session-state']], 'Device/Core revocation observations disagree')
    cleanup = groups['owner-cleanup'][0]
    require(cleanup['fixtureId'] == fixture['fixtureId'] and cleanup['cleaned'] is True and
            by_id[cleanup['requestId']]['request']['path'] == '/test/cleanup-owner', 'Scoped fixture cleanup not observed')
    # Re-read after parsing, detecting a late request that invalidates or appends to completion.
    require(private_read(directory, 'events.jsonl') == data and json.loads(private_read(directory, 'complete.json')) == complete,
            'Backend archive changed during verification')
    return dict(eventsSha256=hashlib.sha256(data).hexdigest(), pendingCreations=0, ownerReceiptsResolved=True)
