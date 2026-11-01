"""Device assertion receipts, not release acceptance or server-side wire proof."""
import base64
import hashlib
import json
import re


STAGES = ['seed', 'check-initial', 'cleanup']
PHASES = ['publishedLogin', 'publishedHandoff', 'candidateRefresh']


def require(value, message):
    if not value:
        raise ValueError(message)


def hex_value(value, length):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{%d}' % length, value) is not None


def load_config(path, backend, aar_hash, run_id):
    value = json.loads(path.read_text())
    require(isinstance(value, dict), 'Native input must be an object')
    require(value.get('schema') == 1 and value.get('kind') == 'matrix-native-input', 'Wrong native input schema')
    require(value.get('mode') in ['COOKIE', 'EXPOSED_COOKIE'] and value.get('flow') in ['REFRESH', 'SIGN_OUT']
            and value.get('route') == '/login', 'Native matrix supports only four approved /login identities')
    require(value.get('deviceBackend') == backend and value.get('candidateAarSha256') == aar_hash,
            'Native backend/artifact binding mismatch')
    require(value.get('runId') == run_id and hex_value(run_id, 48), 'Explicit owned run ID required')
    for name in ['nativeNonce', 'preparationNonce', 'instance', 'challenge']:
        require(hex_value(value.get(name), 48), 'Missing native binding: ' + name)
    for name in ['qualifiedIdentitySha256', 'dependenciesSha256', 'nodeSdkSha256', 'coreSha256',
                 'coreManifestSha256', 'executionSourcesSha256', 'planSha256', 'publishedAarSha256']:
        require(hex_value(value.get(name), 64), 'Missing native digest: ' + name)
    context = value['context']
    require(isinstance(context, dict), 'Context must be an object')
    for name in ['sources', 'policySha256']:
        require(hex_value(context.get(name), 64), 'Missing context digest: ' + name)
    require(hex_value(context.get('sha'), 40) and hex_value(context.get('nonce'), 48), 'Invalid source/attempt nonce')
    require(isinstance(context.get('workflow'), str) and bool(context['workflow']) and
            type(context.get('attempt')) is int and context['attempt'] > 0, 'Missing workflow attempt')
    require(isinstance(context.get('fdi'), str) and re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', context['fdi']), 'Missing FDI')
    for name in ['backend', 'core']:
        require(isinstance(context.get(name), str) and context[name].startswith(('http://', 'https://')), 'Missing canonical endpoint')
    plan = value['plan']
    require(isinstance(plan, dict), 'Plan must be an object')
    require(isinstance(plan.get('review'), str) and bool(plan['review']), 'Reviewed plan reference required')
    for name in ['nodeVersion', 'coreVersion']:
        require(isinstance(plan.get(name), str) and re.fullmatch(r'\d+\.\d+\.\d+', plan[name]), 'Unresolved ' + name)
    require(isinstance(plan.get('phases'), dict) and set(plan['phases']) == set(PHASES) and all(
        plan['phases'][name] == 'ABSENT' if name == 'publishedHandoff' and value['flow'] == 'SIGN_OUT'
        else plan['phases'][name] in ['2', '3', '4', '5'] for name in PHASES),
            'Unresolved reviewed phases')
    return value


def encoded_config(config):
    return base64.b64encode(json.dumps(config, sort_keys=True, separators=(',', ':')).encode()).decode()


def validate_stage(value, config, stage, previous):
    require(isinstance(value, dict), 'Native stage must be an object')
    require(value.get('schema') == 1 and value.get('kind') == 'matrix-native-stage' and value.get('stage') == stage,
            'Wrong native stage identity')
    require(value.get('binding') == config and value.get('flavor') == ('sdk054' if stage == 'seed' else 'candidate'),
            'Native stage binding/flavor mismatch')
    require(value.get('assertionsPassed') is True and value.get('cleanupDone') is (stage == 'cleanup'), 'Incomplete native assertions/cleanup')
    require(all(hex_value(value.get(name), 64) for name in ['installedApplicationSha256', 'installedInstrumentationSha256']),
            'Missing actual installed APK observations')
    require(all(value.get(name) is False for name in ['wireVerified', 'backendIdentityVerified', 'loginRevocationVerified']),
            'Device assertions must not claim external authority')
    process = value.get('process')
    require(isinstance(process, str) and bool(process) and all(row['process'] != process for row in previous),
            'Missing/reused native process')
    observed = value['observed']
    expected_phases = PHASES[:2] if stage == 'seed' else PHASES
    require(set(observed) == set(['schemaVersion'] + expected_phases) and observed['schemaVersion'] == 2, 'Missing/extra observed phases')
    for phase in expected_phases:
        absent = phase == 'publishedHandoff' and config['flow'] == 'SIGN_OUT'
        require(observed[phase] == dict(status='ABSENT' if absent else 'PRESENT',
                                       tokenVersion=None if absent else config['plan']['phases'][phase]), 'Wrong observed phase: ' + phase)
        if previous and phase in previous[-1]['observed']:
            require(observed[phase] == previous[-1]['observed'][phase], 'Phase changed between processes')
    operations = value.get('clientOperations')
    prefix = ['isolated:/test/capabilities', 'isolated:/featureFlags']
    if stage == 'seed':
        expected = ['isolated:/test/native/challenge'] + prefix + ['isolated:/test/email-change-owner', 'managed:/login',
                             'sdk.refresh' if config['flow'] == 'REFRESH' else 'sdk.signOut']
        if config['flow'] == 'SIGN_OUT':
            expected += ['isolated:/test/owner-session-state']
        headers = ['REFRESH' if config['flow'] == 'REFRESH' else 'SIGN_OUT']
    elif stage == 'check-initial':
        expected = prefix + (['managed:/login'] if config['flow'] == 'SIGN_OUT' else []) + [
            'managed:/', 'sdk.refresh', 'managed:/', 'managed:/update-jwt', 'sdk.signOut', 'isolated:/test/owner-session-state']
        headers = ['REFRESH', 'SIGN_OUT']
    else:
        expected = ['isolated:/test/cleanup-owner']
        headers = []
    require(operations == expected, 'Wrong client operation sequence (not a wire trace)')
    require(value.get('customHeaderRequests') == headers, 'Unexpected SDK internal header callbacks')
    challenge = value['challengeResponse']
    require(challenge.get('schema') == 1 and challenge.get('kind') == 'native-backend-challenge' and all(
        challenge.get(name) == config[name] for name in ['runId', 'instance', 'nativeNonce', 'challenge',
                                                       'qualifiedIdentitySha256', 'dependenciesSha256', 'coreManifestSha256']),
        'Device challenge response does not match owned input')
    expected_checks = 1 if stage == 'check-initial' or (stage == 'seed' and config['flow'] == 'SIGN_OUT') else 0
    revocations = value['revocationResponses']
    require(isinstance(revocations, list) and len(revocations) == expected_checks and all(
        item.get('owned') is True and item.get('active') is False and isinstance(item.get('fixtureId'), str) and
        bool(item['fixtureId']) and isinstance(item.get('handle'), str) and bool(item['handle']) for item in revocations),
        'Missing actual login revocation response')
    return value


def source_manifest(root):
    paths = [root / 'testHelpers/device/restart-pairs.py']
    app = root / 'testHelpers/upgradeapp'
    paths += sorted((app / 'app/src').rglob('*.java'))
    paths += sorted((app / 'tools').glob('*.py'))
    paths += sorted((app / 'tools').rglob('*.js'))
    paths += [app / 'app/build.gradle', app / 'build.gradle', app / 'settings.gradle', app / 'gradle.properties']
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def compiled_sources(root):
    app = root / 'testHelpers/upgradeapp'
    paths = sorted((app / 'app/src').rglob('*.java')) + sorted((app / 'app/src').rglob('AndroidManifest.xml'))
    paths += [app / name for name in ['app/build.gradle', 'build.gradle', 'settings.gradle', 'gradle.properties']]
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
