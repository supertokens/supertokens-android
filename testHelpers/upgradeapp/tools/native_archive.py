"""Offline verification of producer bytes. Missing external authority is a hard gate failure."""
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import os
import sys
import types
import xml.etree.ElementTree as ET
import zipfile

import matrix_native
from build_provenance import validate as validate_build
from r8_smoke import dex_classes
import native_device
from native_backend import verify_backend


ROOT = Path(__file__).resolve().parents[3]
PIN = '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9'
LIMIT = 128 * 1024 * 1024
require = matrix_native.require


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(directory, name):
    require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_.-]+', name) and name not in ['.', '..'], 'Archive path escape')
    require(directory.is_absolute() and directory.resolve() == directory, 'Archive directory must be canonical')
    for parent in [directory] + list(directory.parents):
        require(not parent.is_symlink(), 'Archive directory symlink')
    file = directory / name
    require(not file.is_symlink() and file.is_file() and file.stat().st_size <= LIMIT, 'Missing/oversized/symlink archive file: ' + name)
    return file.read_bytes()


def document(directory, name):
    return json.loads(read(directory, name))


def preferences(data):
    require(b'<!DOCTYPE' not in data and b'<!ENTITY' not in data, 'XML entity declarations are forbidden')
    root = ET.fromstring(data)
    require(root.tag == 'map', 'Invalid preference snapshot')
    result = {}
    for item in root:
        name = item.attrib.get('name')
        require(name and name not in result, 'Duplicate/missing preference name')
        result[name] = item.text or '' if item.tag == 'string' else item.attrib.get('value')
    return result


def inspect_archive(directory, summary='native-device.json'):
    """Internal diagnostic validation. Its return value is never release acceptance."""
    directory = Path(directory)
    receipt_bytes = read(directory, summary)
    receipt = json.loads(receipt_bytes)
    kinds = {'native-device.json': 'matrix-native-device', 'native-preflight.json': 'native-device-preflight',
             'native-lease-closed.json': 'native-lease-closed'}
    require(summary in kinds and receipt.get('schema') == 1 and receipt.get('kind') == kinds[summary], 'Wrong native archive kind')
    if summary == 'native-device.json':
        require(receipt.get('status') == 'device-assertions-passed' and receipt.get('releaseAccepted') is False,
                'Not a completed matrix-native device assertion archive')
    require(not (directory / 'failure.json').exists(), 'Failed archive cannot qualify')
    files = receipt['files']
    require(isinstance(files, dict) and len(files) <= 200, 'Invalid file manifest')
    require(set(files) == {file.name for file in directory.iterdir()} - {summary}, 'Unlisted/missing archive entry')
    for name, digest in files.items():
        require(matrix_native.hex_value(digest, 64) and sha(read(directory, name)) == digest, 'Archive hash mismatch: ' + name)
    run = document(directory, 'run.json')
    require(run['scenario'] == 'matrix-native' and run['cleanupOnly'] is False, 'Old scenario/recovery archive')
    binding = matrix_native.load_config(directory / 'matrix-input.json', run['backend'], run['candidateAarSha256'], run['runId'])
    require(binding == receipt['binding'] and binding['publishedAarSha256'] == PIN, 'Input/public artifact binding mismatch')
    require(type(receipt.get('startedMs')) is int and type(receipt.get('endedMs')) is int and
            receipt['endedMs'] >= receipt['startedMs'], 'Missing actual producer interval')
    require(document(directory, 'native-sources.json') == matrix_native.source_manifest(ROOT), 'Stale native execution sources')
    build = document(directory, 'candidate-build.json')
    require(build == run['candidateBuild'], 'Build receipts disagree')
    validate_build(build, binding['candidateAarSha256'])
    certificates = []
    for label in ['sdk', 'sdk-test', 'candidate', 'candidate-test']:
        data = read(directory, label + '.apk')
        require(sha(data) == run['artifacts'][label]['sha256'], 'APK digest mismatch')
        with zipfile.ZipFile(directory / (label + '.apk')) as apk:
            definitions = []
            dexes = [info for info in apk.infolist() if re.fullmatch(r'classes\d*\.dex', info.filename)]
            require(dexes and len(dexes) <= 32 and sum(info.file_size for info in dexes) <= LIMIT, 'Missing/oversized APK DEX')
            for dex in dexes:
                definitions.extend(dex_classes(apk.read(dex)))
            require(len(definitions) == len(set(definitions)), 'Duplicate APK class definitions')
            sdk = {name for name in definitions if name.startswith('Lcom/supertokens/session/')}
            if label.endswith('-test'):
                require(not sdk, 'Instrumentation APK contains SDK implementations')
                info = apk.getinfo('assets/native-test-provenance.json')
                require(info.file_size <= 1024 * 1024 and json.loads(apk.read(info)) == dict(schema=1, sources=matrix_native.compiled_sources(ROOT)),
                        'Instrumentation APK source-build manifest is stale/missing')
            else:
                require({'Lcom/supertokens/session/SuperTokens;', 'Lcom/supertokens/session/SuperTokensHttpURLConnection;',
                         'Lcom/supertokens/session/SuperTokensPersistentCookieStore;'} <= sdk, 'App APK lacks native SDK implementation')
        # The producer's command journal ties these tool outputs to the archived APK invocation.
        # Re-validating signatures/installed APKs is still an external prerequisite, not inferred here.
        signer = read(directory, label + '-signer.txt').decode()
        certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signer, re.M)
        require(len(certs) == 1, 'Ambiguous APK signer output')
        certificates.append(certs[0].lower())
        package = 'com.supertokens.upgrade' + ('.test' if label.endswith('-test') else '')
        badging = read(directory, label + '-badging.txt').decode()
        require(re.search(r"^package: name='" + re.escape(package) + r"' versionCode='1'", badging, re.M), 'Wrong APK package/version')
        manifest = read(directory, label + '-manifest.txt').decode()
        if label.endswith('-test'):
            require('androidx.test.runner.AndroidJUnitRunner' in manifest and
                    re.search(r'android:targetPackage[^\n]*="com.supertokens.upgrade"', manifest), 'Wrong instrumentation target')
        else:
            with zipfile.ZipFile(directory / (label + '.apk')) as apk:
                info = apk.getinfo('assets/sdk-provenance.json')
                require(info.file_size <= 1024 * 1024, 'Oversized embedded provenance')
                provenance = json.loads(apk.read(info))
            expected = dict(sdk='sdk054' if label == 'sdk' else 'candidate', aarSha256=PIN if label == 'sdk' else binding['candidateAarSha256'],
                            applicationId='com.supertokens.upgrade', versionCode=1)
            if label == 'candidate':
                expected['candidateBuild'] = build
            require(provenance == expected, 'Embedded SDK/build provenance mismatch')
    require(len(set(certificates)) == 1 and certificates[0] == run['certificateSha256'], 'Signer identities disagree')
    commands = [json.loads(line) for line in read(directory, 'native-commands.jsonl').splitlines()]
    require(commands and len(commands) <= 100, 'Missing/bloated command journal')
    end = receipt['startedMs']
    outputs = set()
    for command in commands:
        require(command['exit'] == 0 and type(command['startedMs']) is int and type(command['endedMs']) is int and
                end <= command['startedMs'] <= command['endedMs'] <= receipt['endedMs'], 'Failed/overlapping/unbounded child command')
        end = command['endedMs']
        require(command['output'] not in outputs and command['outputSha256'] == files.get(command['output']), 'Duplicate/unbound command output')
        outputs.add(command['output'])
        group = command['processGroup']
        require(type(group['leader']) is int and group['leader'] > 1 and group['actualExit'] == 0 and group['reason'] is None and
                group['afterKill'] and all(member['pgid'] == group['leader'] and member['state'].startswith('Z')
                                          for member in group['afterKill']) and
                any(member['pid'] == group['leader'] for member in group['afterKill']) and
                any(member['pid'] == group['leader'] and member['pgid'] == group['leader'] and member['state'].startswith('Z')
                    for member in group['beforeKill']), 'Owned command group not observed drained before leader reap')
    def package_command(name):
        matches = [c for c in commands if c['output'] == name]
        require(len(matches) == 1 and matches[0]['argv'][1:3] == ['-s', run['serial']], 'Missing/incorrect package discovery command')
        return matches[0]['argv'][3:]
    verify_packages(directory, dict(receipt=receipt, commands=commands), package_command)
    instrumentation = [command for command in commands if 'instrument' in command['argv']]
    if summary != 'native-device.json':
        require(not instrumentation and not any('install' in command['argv'] for command in commands), 'Preflight/close performed scenario operations')
        return dict(receipt=receipt, input=binding, run=run, commands=commands, rawEvidenceSha256=sha(receipt_bytes))
    shared_path = ROOT / 'testHelpers/device/restart-pairs.py'
    shared = types.ModuleType('restart_pairs_verifier')
    exec(compile(shared_path.read_text(), str(shared_path), 'exec'), shared.__dict__)
    instrumentation = [command for command in commands if 'instrument' in command['argv']]
    require(len(instrumentation) == 3, 'Wrong native instrumentation count')
    previous = []
    for index, action in enumerate(matrix_native.STAGES):
        name = 'matrix-native-' + action
        value = matrix_native.validate_stage(document(directory, name + '-evidence.json'), binding, action, previous)
        artifact = 'sdk' if index == 0 else 'candidate'
        require(value['installedApplicationSha256'] == files[artifact + '.apk'] and
                value['installedInstrumentationSha256'] == files[artifact + '-test.apk'], 'Actual installed APK observation mismatch')
        require(value == receipt['stages'][index], 'Summary/raw native stage mismatch')
        previous.append(value)
        klass = 'com.supertokens.upgrade.MatrixNative' + ('Published' if index == 0 else 'Candidate') + 'Test'
        shared.CLASS = klass
        require(shared.exactly_one_pass(read(directory, name + '-instrument.txt').decode(), 'runStage'), 'Native raw instrumentation did not pass exactly once')
        command = instrumentation[index]
        argv = command['argv']
        expected_args = ['-s', run['serial'], 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', klass + '#runStage',
                         '-e', 'stage', action, '-e', 'scenario', 'matrix-native', '-e', 'runId', binding['runId'],
                         '-e', 'backend', binding['deviceBackend'], '-e', 'expectedFlavor', value['flavor'],
                         '-e', 'matrixConfig', matrix_native.encoded_config(binding),
                         'com.supertokens.upgrade.test/androidx.test.runner.AndroidJUnitRunner']
        require(argv[1:] == expected_args and command['output'] == name + '-instrument.txt', 'Wrong actual instrumentation invocation')
        prefs = preferences(read(directory, name + '-matrix-native-receipt.xml'))
        require(prefs.get('binding') == matrix_native.encoded_config(binding) and prefs.get('runId') == binding['runId'] and
                prefs.get('process') == value['process'] and json.loads(prefs['observed']) == value['observed'] and
                prefs.get('phase') == ['seeded', 'continued', 'cleaned'][index] and
                prefs.get('cleanupDone') == ('true' if index == 2 else 'false') and 'pendingRequestId' not in prefs,
                'Persisted native receipt disagrees with stage')
        if index == 2:
            require('fixtureId' not in prefs, 'Unresolved fixture owner after cleanup')
    require(len(receipt['stages']) == 3, 'Extra native stage')
    return dict(receipt=receipt, input=binding, run=run, commands=commands, rawEvidenceSha256=sha(receipt_bytes))


def verify_packages(directory, inspected, command_for):
    present = {}
    checked = []
    for package, prefix in [('com.supertokens.upgrade', 'native-preflight-'),
                            ('com.supertokens.upgrade.test', 'native-preflight-test-')]:
        listing = prefix + 'packages.txt'
        require(command_for(listing) == ['shell', 'pm', 'list', 'packages', package], 'Missing exact package listing')
        present[package] = package in native_device.packages(read(directory, listing).decode())
        checked.append(listing)
    app, test = present['com.supertokens.upgrade'], present['com.supertokens.upgrade.test']
    require(not test or app, 'Orphan instrumentation package')
    for package, prefix in [('com.supertokens.upgrade', 'native-preflight-'),
                            ('com.supertokens.upgrade.test', 'native-preflight-test-')]:
        name = prefix + 'package.txt'
        path_commands = [c for c in inspected['commands'] if c['argv'][3:] == ['shell', 'pm', 'path', package]]
        if not present[package]:
            require(name not in inspected['receipt']['files'] and not path_commands, 'Absent package has fabricated path evidence')
            continue
        require(len(path_commands) == 1 and command_for(name) == ['shell', 'pm', 'path', package], 'Missing listed package path')
        paths = native_device.package_paths(read(directory, name).decode(), instrumentation=package.endswith('.test'))
        checked.append(name)
        if package.endswith('.test'):
            name = 'native-preflight-test-sha256.txt'
            require(command_for(name) == ['shell', 'sha256sum', paths[0]], 'Installed instrumentation bytes not queried')
            actual = read(directory, name).decode().strip().split()
            require(len(actual) == 2 and actual[1] == paths[0] and actual[0] in
                    [inspected['receipt']['files'][label + '.apk'] for label in ['sdk-test', 'candidate-test']],
                    'Installed instrumentation differs from owned inputs')
            checked.append(name)
    if not test:
        require('native-preflight-test-sha256.txt' not in inspected['receipt']['files'], 'Absent instrumentation has hash evidence')
    if app:
        listing = 'native-preflight-prefs.txt'
        require(command_for(listing) == ['exec-out', 'run-as', 'com.supertokens.upgrade', 'ls', 'shared_prefs'], 'Prior ownership listing not queried')
        checked.append(listing)
        names = read(directory, listing).decode().splitlines()
        markers = [name for name in ['matrix-native-receipt.xml', 'upgrade-receipt.xml'] if name in names]
        require(markers, 'Unowned existing fixture app')
        for name in markers:
            filename = 'native-preflight-' + name
            require(command_for(filename) == ['exec-out', 'run-as', 'com.supertokens.upgrade', 'cat', 'shared_prefs/' + name], 'Prior owner receipt not queried')
            prior = preferences(read(directory, filename))
            require(prior.get('cleanupDone') == 'true' and 'pendingRequestId' not in prior and 'fixtureId' not in prior and
                    json.loads(prior.get('owners', '[]')) == [], 'Unresolved prior fixture owner')
            checked.append(filename)
    else:
        require(not any(name in inspected['receipt']['files'] for name in ['native-preflight-prefs.txt',
            'native-preflight-matrix-native-receipt.xml', 'native-preflight-upgrade-receipt.xml']), 'Absent app has owner evidence')
    positions = {c['output']: i for i, c in enumerate(inspected['commands'])}
    list_end = max(positions[name] for name in ['native-preflight-packages.txt', 'native-preflight-test-packages.txt'])
    require(all(positions[name] > list_end for name in checked[2:]), 'Package ownership queried before both listings')
    end = max(positions[name] for name in checked)
    require(all(i > end for i, c in enumerate(inspected['commands']) if 'install' in c['argv'] or 'force-stop' in c['argv']),
            'Package mutated before both ownership checks')


def verify_device_owner(directory, inspected, expected):
    source, receipt, run = inspected['input'], inspected['receipt'], inspected['run']
    commands = inspected['commands']
    pre_dir, close_dir = Path(expected['nativePreflight']), Path(expected['nativeLeaseClose'])
    pre = inspect_archive(pre_dir, 'native-preflight.json')
    close = inspect_archive(close_dir, 'native-lease-closed.json')
    verify_compilation(pre_dir, pre, expected)
    require(pre['input'] == close['input'] and source.get('leaseNonce') == pre['input']['nativeNonce'] and
            all(pre['input'][name] == source[name] for name in ['context', 'runId', 'instance', 'preparationNonce', 'mode', 'route',
                'candidateAarSha256', 'publishedAarSha256', 'deviceBackend', 'toolchain', 'executionSourcesSha256', 'qualifiedIdentitySha256',
                'dependenciesSha256', 'nodeSdkSha256', 'coreSha256', 'coreManifestSha256', 'planSha256']), 'Pre-JVM retained lease binding mismatch')
    require(type(expected['jvmStartedMs']) is int and expected['startedMs'] <= pre['receipt']['startedMs'] <=
            pre['receipt']['endedMs'] <= expected['jvmStartedMs'] <= receipt['startedMs'] <= receipt['endedMs'] <=
            close['receipt']['startedMs'] <= close['receipt']['endedMs'] <= expected['endedMs'], 'Device lease preflight/close did not enclose JVM/native execution')
    for label in ['sdk', 'sdk-test', 'candidate', 'candidate-test']:
        require(pre['receipt']['files'][label + '.apk'] == receipt['files'][label + '.apk'] == close['receipt']['files'][label + '.apk'],
                'Artifact changed after pre-JVM preflight')
    require(run['serial'] == expected['nativeSerial'], 'Device serial differs from trusted executor selection')
    lease_bytes = read(directory, 'device-lease-owner.json')
    require(lease_bytes == read(pre_dir, 'device-lease-owner.json') == read(close_dir, 'device-lease-owner.json'), 'Selector lease ownership changed between stages')
    owner = json.loads(lease_bytes)
    require(owner['schema'] == 1 and owner['serial'] == run['serial'] and type(owner['hostPid']) is int and owner['hostPid'] > 1 and
            owner['nativeNonce'] == source['leaseNonce'] and all(owner[name] == source[name] for name in ['runId', 'instance', 'preparationNonce']), 'Wrong device lease owner')
    opened = [json.loads(line) for line in read(pre_dir, 'device-lease.jsonl').splitlines()]
    closed = [json.loads(line) for line in read(close_dir, 'device-lease.jsonl').splitlines()]
    borrowed = [json.loads(line) for line in read(directory, 'device-lease.jsonl').splitlines()]
    require(len(opened) == 1 and len(closed) == len(borrowed) == 2 and opened[0]['action'] == 'acquired' and
            closed[0]['action'] == borrowed[0]['action'] == 'borrowed' and closed[1]['action'] == 'released' and
            borrowed[1]['action'] == 'row-completed', 'Device selector lease not acquired/borrowed/closed exactly')
    for item in closed[:1] + borrowed[:1]:
        require(item['preflight'] == str(pre_dir) and item['preflightSha256'] == pre['rawEvidenceSha256'], 'Borrowed an unrelated device preflight')
    for folder, records, input_value in [(directory, borrowed, source), (close_dir, closed, close['input'])]:
        borrower_bytes = read(folder, 'device-borrower.json')
        borrower = json.loads(borrower_bytes)
        require(borrower['schema'] == 1 and type(borrower['hostPid']) is int and borrower['hostPid'] > 1 and
                borrower['nativeNonce'] == input_value['nativeNonce'] and borrower['preparationNonce'] == source['preparationNonce'] and
                borrower['ownerSha256'] == sha(lease_bytes) and all(item['borrowerSha256'] == sha(borrower_bytes) for item in records),
                'Borrower identity/ownership journal mismatch')
    lease = [opened[0], closed[1]]
    require(all(item['inode'] == opened[0]['inode'] and item['ownerSha256'] == sha(lease_bytes) for item in opened + closed + borrowed) and
            lease[0]['inode'] == lease[1]['inode'] and all(item['ownerSha256'] == sha(lease_bytes) for item in lease), 'Device lease retained/replaced')
    root = Path(expected['nativeLeaseRoot'])
    require(str(root) == lease[0]['root'] and root.resolve() == root and
            lease[0]['path'] == str(root / sha(run['serial'].encode())) and not Path(lease[0]['path']).exists(), 'Device lease root/collision mismatch')
    require(pre['receipt']['startedMs'] <= lease[0]['timeMs'] <= lease[1]['timeMs'] <= close['receipt']['endedMs'], 'Device lease interval mismatch')
    def command_for(name, archive=inspected):
        matches = [item for item in archive['commands'] if item['output'] == name]
        require(len(matches) == 1, 'Missing/duplicate device command: ' + name)
        command = matches[0]
        require(command['argv'][1:3] == ['-s', run['serial']] and
                lease[0]['timeMs'] <= command['startedMs'] <= command['endedMs'] <= lease[1]['timeMs'], 'ADB ran without selected device lease')
        return command['argv'][3:]
    for suffix, package in [('app', 'com.supertokens.upgrade'), ('test', 'com.supertokens.upgrade.test')]:
        require(command_for('native-final-stop-' + suffix + '.txt') == ['shell', 'am', 'force-stop', package], 'Wrong final force-stop target')
        require(command_for('native-final-process-' + suffix + '.txt') == ['shell', 'sh', '-c',
                "'pidof " + package + "; code=$?; echo NATIVE_PIDOF_EXIT:$code'"] and
                read(directory, 'native-final-process-' + suffix + '.txt').decode().strip() == 'NATIVE_PIDOF_EXIT:1', 'Native device writers survived')
    from urllib.parse import urlsplit
    device, host = urlsplit(source['deviceBackend']), urlsplit(source['context']['backend'])
    require(device.scheme == host.scheme == 'http' and device.hostname == host.hostname == '127.0.0.1' and device.port and host.port,
            'Unowned device routing mode')
    local, remote = 'tcp:' + str(device.port), 'tcp:' + str(host.port)
    before = native_device.routes(read(pre_dir, 'native-routing-before.txt').decode())
    active = native_device.routes(read(directory, 'native-routing-active.txt').decode())
    final = native_device.routes(read(directory, 'native-routing-final.txt').decode())
    after = native_device.routes(read(close_dir, 'native-routing-after.txt').decode())
    require(not any(item[1] == local for item in before) and len([item for item in active if item[1:] == [local, remote]]) == 1 and
            [item for item in active if item[1] != local] == before and final == active and after == before,
            'Routing replaced another owner or failed restoration')
    require(command_for('native-routing-create.txt', pre) == ['reverse', '--no-rebind', local, remote] and
            command_for('native-routing-remove.txt', close) == ['reverse', '--remove', local], 'Unowned routing mutation')
    for data, folder, routing in [(pre, pre_dir, ['before', 'active']), (inspected, directory, ['active', 'final']),
                                  (close, close_dir, ['active', 'final', 'after'])]:
        require(data['run']['serial'] == run['serial'], 'Device changed across selector lease')
        verify_packages(folder, data, lambda name: command_for(name, data))
        require(command_for('native-process-probe.txt', data) == ['shell', 'sh', '-c',
                "'command -v pidof >/dev/null; echo NATIVE_PIDOF_AVAILABLE:$?'"] and
                read(folder, 'native-process-probe.txt').decode().strip() == 'NATIVE_PIDOF_AVAILABLE:0', 'Device process-absence probe unavailable')
        for name in routing:
            require(command_for('native-routing-' + name + '.txt', data) == ['reverse', '--list'], 'Routing output was not an actual query')
            if name in ['active', 'final']:
                require(native_device.routes(read(folder, 'native-routing-' + name + '.txt').decode()) == active, 'Shared device route changed')
        for command in data['commands']:
            argv = command['argv']
            if len(argv) > 2 and argv[1] == '-s':
                command_for(command['output'], data)
                require(not any(value in argv for value in ['uninstall', 'clear', 'kill', 'killall', 'pkill']), 'Destructive native device command')
                if 'force-stop' in argv:
                    require(data is not pre and argv[-1] in ['com.supertokens.upgrade', 'com.supertokens.upgrade.test'], 'Unowned/preflight app force-stop')
    for suffix, package in [('app', 'com.supertokens.upgrade'), ('test', 'com.supertokens.upgrade.test')]:
        require(command_for('native-final-stop-' + suffix + '.txt', close) == ['shell', 'am', 'force-stop', package] and
                command_for('native-final-process-' + suffix + '.txt', close) == ['shell', 'sh', '-c',
                    "'pidof " + package + "; code=$?; echo NATIVE_PIDOF_EXIT:$code'"] and
                read(close_dir, 'native-final-process-' + suffix + '.txt').decode().strip() == 'NATIVE_PIDOF_EXIT:1', 'Device writers survived selector close')
    return [(pre_dir, 'native-preflight.json', pre['rawEvidenceSha256'], pre['receipt']['files']),
            (close_dir, 'native-lease-closed.json', close['rawEvidenceSha256'], close['receipt']['files'])]


def verify_signatures(directory, inspected, expected):
    pin = expected['nativeTools']['apksigner']
    require(inspected['input']['toolchain']['apksigner'] == pin, 'Device tool pin differs from trusted owner discovery')
    native_device.verify_signer(pin)
    env = {name: value for name, value in os.environ.items() if name not in
           ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'CLASSPATH']}
    for label in ['sdk', 'sdk-test', 'candidate', 'candidate-test']:
        result = subprocess.run([pin['path'], 'verify', '--print-certs', str(directory / (label + '.apk'))],
                                capture_output=True, text=True, timeout=15, env=env)
        require(result.returncode == 0, 'Independent archived APK signature verification failed')
        certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', result.stdout, re.M)
        require(len(certs) == 1 and certs[0].lower() == inspected['run']['certificateSha256'], 'Independent APK signer differs')
    native_device.verify_signer(pin)


def verify_compilation(directory, inspected, expected):
    import native_build
    return native_build.verify(expected['nativeBuild'], ROOT, inspected['input'],
        {label: Path(directory) / (label + '.apk') for label in native_build.LABELS},
        inspected['run']['candidateBuild'], inspected['receipt']['startedMs'])


def verify_native(row, expected, directory):
    require(matrix_native.hex_value(row.get('executionNonce'), 48), 'Invalid native execution nonce')
    archive = Path(directory) / 'native' / row['executionNonce']
    inspected = inspect_archive(archive)
    receipt, source = inspected['receipt'], inspected['input']
    verify_compilation(archive, inspected, expected)
    require(row['schema'] == 2 and row['kind'] == 'matrix-native-upgrade' and row['binding'] == expected['binding'], 'Wrong composite envelope')
    binding = expected['binding']
    for name, value in binding.items():
        actual = source['plan'][name] if name in ['nodeVersion', 'coreVersion'] else source['mode'] == 'EXPOSED_COOKIE' if name == 'sessionJwt' else source.get(name)
        require(actual == value, 'Composite/native binding differs: ' + name)
    require(source['nativeNonce'] == row['executionNonce'] and source['preparationNonce'] == row['parentPreparationNonce'] == expected['preparationNonce'] and
            source['flow'] == row['flow'] and source['mode'] == row['mode'] and row['route'] == source['route'] == '/login' and
            row['rowId'] == source['mode'] + ':' + source['flow'] + ':' + source['nativeNonce'], 'Wrong native row identity')
    require(row['rawEvidenceSha256'] == inspected['rawEvidenceSha256'] and row['observed'] == receipt['stages'][-1]['observed'] and
            row['startedMs'] == receipt['startedMs'] and row['endedMs'] == receipt['endedMs'] and
            expected['startedMs'] <= receipt['startedMs'] <= receipt['endedMs'] <= expected['endedMs'], 'Envelope differs from actual native archive')
    versions = expected['plan']['phaseVersions'][source['mode'] + ':/login']
    phases = dict(publishedLogin=versions['publishedLogin'], publishedHandoff=versions['publishedHandoff'][source['flow']],
                  candidateRefresh=versions['candidateRefresh'])
    require(source['plan']['phases'] == phases and source['plan']['review'] == expected['plan']['review']['reference'], 'Wrong reviewed row plan')
    require(row['sessionJwt'] == (source['mode'] == 'EXPOSED_COOKIE') and all(row.get(name) is True for name in
            ['publishedFirst', 'candidateContinued', 'scopedCleanup']), 'Wrong native envelope assertions')
    verify_signatures(archive, inspected, expected)
    lease_archives = verify_device_owner(archive, inspected, expected)
    backend = verify_backend(expected['owned'], source, expected, receipt['stages'], receipt['startedMs'], receipt['endedMs'],
                             Path(directory) / 'native-backend' / row['executionNonce'])
    # Recheck immutable input bytes after independent tools and backend log validation.
    require(sha(read(archive, 'native-device.json')) == inspected['rawEvidenceSha256'] and
            all(sha(read(archive, name)) == value for name, value in receipt['files'].items()), 'Native archive changed during verification')
    for folder, summary, digest, files in lease_archives:
        require(sha(read(folder, summary)) == digest and all(sha(read(folder, name)) == value for name, value in files.items()),
                'Device preflight/close changed during verification')
    verify_compilation(archive, inspected, expected)
    return dict(binding=binding, executionNonce=source['nativeNonce'], rawEvidenceSha256=inspected['rawEvidenceSha256'],
                deviceCleanup=True, workersDrained=True, pendingCreations=backend['pendingCreations'],
                ownerReceiptsResolved=backend['ownerReceiptsResolved'], backendEventsSha256=backend['eventsSha256'])


def main():
    try:
        request = json.load(sys.stdin)
        result = verify_native(request['row'], request['expected'], Path(sys.argv[1]))
        print(json.dumps(result))
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, IndexError, struct.error, subprocess.SubprocessError, zipfile.BadZipFile, ET.ParseError) as error:
        print(json.dumps(dict(error=str(error), code='NATIVE_ARCHIVE_INVALID')))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
