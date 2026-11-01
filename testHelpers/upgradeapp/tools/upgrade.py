#!/usr/bin/env python3
"""Installed published -> candidate -> published mutation -> candidate device coverage."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import signal
import sys
import types
import time
from urllib.parse import urlsplit
import uuid
import xml.etree.ElementTree as ET
import zipfile
from build_provenance import validate as validate_build_receipt
import matrix_native
import native_device


HELPERS = Path(__file__).resolve().parents[2]
shared_path = HELPERS / 'device' / 'restart-pairs.py'
shared = types.ModuleType('restart_pairs')
exec(compile(shared_path.read_text(), str(shared_path), 'exec'), shared.__dict__)
PACKAGE = 'com.supertokens.upgrade'
TEST_PACKAGE = PACKAGE + '.test'
CLASS = PACKAGE + '.UpgradeTest'
shared.CLASS = CLASS
SCENARIOS = ['login-b', 'refresh', 'logout', 'cookie']
CONTINUATIONS = ['cookie-refresh', 'cookie-signout', 'exposed-cookie-refresh', 'exposed-cookie-signout']


def sha256(path):
    with path.open('rb') as source:
        digest = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--backend', required=True, help='Device-reachable fixture origin, e.g. http://10.0.2.2:8080')
    parser.add_argument('--output', required=True, type=Path)
    for flavor in ['sdk', 'candidate']:
        parser.add_argument('--' + flavor + '-apk', required=True, type=Path)
        parser.add_argument('--' + flavor + '-test-apk', required=True, type=Path)
    parser.add_argument('--candidate-aar-sha256', required=True)
    parser.add_argument('--candidate-build-provenance', required=True, type=Path)
    parser.add_argument('--native-build-receipt', type=Path)
    parser.add_argument('--native-build-sha256')
    parser.add_argument('--allow-test-only', action='store_true', help='Allow -t only for APKs whose manifest declares testOnly=true')
    parser.add_argument('--scenario', choices=['all', 'matrix-native'] + SCENARIOS + CONTINUATIONS, default='all')
    parser.add_argument('--matrix-config', type=Path, help='Bound matrix-native-input JSON; gate authority remains external')
    parser.add_argument('--device-lease-root', type=Path, help='Pre-created private global lease directory shared by device executors')
    parser.add_argument('--preflight-only', action='store_true', help='Matrix: validate artifacts/device/routing and retain lease before JVM execution')
    parser.add_argument('--device-lease-session', type=Path, help='Matrix: borrow a completed preflight directory under its retained selector lease')
    parser.add_argument('--release-lease-only', action='store_true', help='Matrix: drain device and release shared selector lease; no acceptance scenario')
    parser.add_argument('--run-id', default=None)
    parser.add_argument('--cleanup-only', action='store_true')
    parser.add_argument('--aapt', default=os.environ.get('AAPT', 'aapt'))
    parser.add_argument('--apksigner', default=os.environ.get('APKSIGNER', 'apksigner'))
    parser.add_argument('--command-timeout', type=shared.seconds, default=30)
    parser.add_argument('--install-timeout', type=shared.seconds, default=120)
    parser.add_argument('--test-timeout', type=shared.seconds, default=120)
    args = parser.parse_args()
    origin = urlsplit(args.backend)
    if (not re.fullmatch(r'https?://(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\])(?::[0-9]+)?/?', args.backend)
            or origin.scheme not in ['http', 'https'] or not origin.hostname or origin.username or origin.password
            or origin.path not in ['', '/'] or origin.query or origin.fragment):
        parser.error('--backend must be a device-reachable HTTP(S) origin')
    args.backend = args.backend.rstrip('/')
    matrix = None
    if args.scenario == 'matrix-native':
        if not args.matrix_config or not args.device_lease_root or args.cleanup_only:
            parser.error('matrix-native requires --matrix-config; cleanup-only cannot certify a matrix run')
        try:
            matrix = matrix_native.load_config(args.matrix_config, args.backend, args.candidate_aar_sha256, args.run_id)
            import native_build
            if not args.native_build_receipt or not args.native_build_sha256:
                raise ValueError('Matrix execution requires executor-pinned native compilation receipt')
            native_build.verify(dict(path=str(args.native_build_receipt), sha256=args.native_build_sha256), HELPERS.parent, matrix,
                {label: getattr(args, label.replace('-', '_') + '_apk') for label in native_build.LABELS},
                json.loads(args.candidate_build_provenance.read_bytes()), time.time_ns() // 1000000)
            discovered_signer = native_device.signer_identity(args.apksigner)
            if discovered_signer != matrix['toolchain']['apksigner']:
                raise ValueError('Discovered apksigner differs from owner-pinned toolchain')
            args.apksigner = discovered_signer['path']
        except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
            parser.error(str(error))
    elif args.matrix_config:
        parser.error('--matrix-config requires --scenario matrix-native')
    if args.preflight_only or args.device_lease_session or args.release_lease_only:
        if not matrix or (args.preflight_only and (args.device_lease_session or args.release_lease_only)) or (args.release_lease_only and not args.device_lease_session):
            parser.error('Invalid matrix shared lease lifecycle options')
    if not args.serial or not re.fullmatch('[0-9a-f]{64}', args.candidate_aar_sha256):
        parser.error('Explicit serial and lowercase candidate AAR SHA-256 required')
    if args.cleanup_only and (not args.run_id or args.scenario == 'all'):
        parser.error('--cleanup-only requires the original --run-id and one --scenario')
    run_id = args.run_id or uuid.uuid4().hex
    if not re.fullmatch('[A-Za-z0-9-]{1,80}', run_id):
        parser.error('Invalid run ID')
    if args.output.exists():
        parser.error('Output directory must be new')
    os.umask(0o077)
    args.output.mkdir(parents=True)
    cancellation = shared.Cancellation()
    for sig in [signal.SIGINT, signal.SIGTERM]:
        signal.signal(sig, cancellation.receive)
    stage = 'artifact-preflight'
    active_scenario = None
    status = 1
    captures = []
    native_stages = []
    native_sources = matrix_native.source_manifest(HELPERS.parent) if matrix else None
    native_started = time.time_ns() // 1000000
    lease = None

    def command(argv, filename, timeout=None):
        log = args.output / filename
        started = time.time_ns() // 1000000
        group = None
        if matrix:
            code, group = native_device.run_command(argv, timeout or args.command_timeout, log, cancellation)
        else:
            code = shared.bounded_command(argv, timeout or args.command_timeout, log, cancellation)
        if matrix:
            with (args.output / 'native-commands.jsonl').open('a') as journal:
                journal.write(json.dumps(dict(argv=argv, output=filename, startedMs=started,
                                              endedMs=time.time_ns() // 1000000, exit=code,
                                              outputSha256=sha256(log), processGroup=group)) + '\n')
        if code != 0:
            raise RuntimeError('Command failed (%s); retained log: %s' % (code, log))
        return log.read_text(errors='replace')

    def adb(argv, filename, timeout=None):
        if matrix:
            lease.check()
        return command([os.environ.get('ADB', 'adb'), '-s', args.serial] + argv, filename, timeout)

    try:
        pin = (HELPERS.parent / 'app/src/test/compatibility/legacy054.sha256').read_text().strip()
        if matrix and matrix['publishedAarSha256'] != pin:
            raise RuntimeError('Matrix input differs from published AAR pin')
        if args.candidate_aar_sha256 == pin:
            raise RuntimeError('Candidate must differ from published 0.5.4')
        build_receipt = validate_build_receipt(json.loads(args.candidate_build_provenance.read_text()), args.candidate_aar_sha256)
        (args.output / 'candidate-build.json').write_text(json.dumps(build_receipt, indent=2) + '\n')
        artifacts = {}
        certificates = []
        versions = []
        for label, source, expected_package, flavor, sdk_hash in [
            ('sdk', args.sdk_apk, PACKAGE, 'sdk054', pin),
            ('sdk-test', args.sdk_test_apk, TEST_PACKAGE, None, None),
            ('candidate', args.candidate_apk, PACKAGE, 'candidate', args.candidate_aar_sha256),
            ('candidate-test', args.candidate_test_apk, TEST_PACKAGE, None, None),
        ]:
            if not source.is_file():
                raise RuntimeError('APK does not exist: ' + str(source))
            target = args.output / (label + '.apk')
            shutil.copyfile(source, target)
            digest = sha256(target)
            if digest != sha256(source):
                raise RuntimeError('APK changed while copying: ' + str(source))
            badging = command([args.aapt, 'dump', 'badging', str(target)], label + '-badging.txt')
            metadata = re.search(r"^package: name='([^']+)' versionCode='([^']*)'", badging, re.M)
            if not metadata or metadata.group(1) != expected_package:
                raise RuntimeError('Wrong APK package: ' + label)
            manifest = command([args.aapt, 'dump', 'xmltree', str(target), 'AndroidManifest.xml'], label + '-manifest.txt')
            test_only_values = re.findall(r'android:testOnly[^\n]*', manifest)
            test_only = False
            if test_only_values:
                if len(test_only_values) != 1 or not re.search(r'=(?:\(type 0x12\))?(?:0xffffffff|0x0|"true"|"false")\s*$', test_only_values[0]):
                    raise RuntimeError('Unrecognized testOnly manifest value: ' + label)
                test_only = bool(re.search(r'=(?:\(type 0x12\))?(?:0xffffffff|"true")\s*$', test_only_values[0]))
            if test_only and not args.allow_test_only:
                raise RuntimeError('APK declares testOnly; inspect manifest and pass --allow-test-only: ' + label)
            if flavor:
                versions.append(metadata.group(2))
                with zipfile.ZipFile(target) as apk:
                    provenance = json.loads(apk.read('assets/sdk-provenance.json'))
                expected = dict(sdk=flavor, aarSha256=sdk_hash, applicationId=PACKAGE, versionCode=1)
                if flavor == 'candidate':
                    expected['candidateBuild'] = build_receipt
                if provenance != expected:
                    raise RuntimeError('Wrong SDK artifact provenance: ' + label)
            else:
                if matrix:
                    with zipfile.ZipFile(target) as apk:
                        provenance = json.loads(apk.read('assets/native-test-provenance.json'))
                    if provenance != dict(schema=1, sources=matrix_native.compiled_sources(HELPERS.parent)):
                        raise RuntimeError('Instrumentation APK was not built from current native sources')
                if not re.search(r'android:targetPackage[^\n]*="' + re.escape(PACKAGE) + '"', manifest):
                    raise RuntimeError('Wrong instrumentation target: ' + label)
                if 'androidx.test.runner.AndroidJUnitRunner' not in manifest:
                    raise RuntimeError('Wrong instrumentation runner: ' + label)
            signed = command([args.apksigner, 'verify', '--print-certs', str(target)], label + '-signer.txt')
            certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signed, re.M)
            if len(certs) != 1:
                raise RuntimeError('Expected exactly one verified APK signer: ' + label)
            certificates.append(certs[0].lower())
            artifacts[label] = dict(path=str(target), source=str(source.resolve()), sha256=digest, testOnly=test_only)
        if versions != ['1', '1'] or len(set(certificates)) != 1:
            raise RuntimeError('SDK flavors/test APKs must share signing identity; app versionCodes must both be 1')
        (args.output / 'run.json').write_text(json.dumps(dict(
            runId=run_id, serial=args.serial, backend=args.backend, scenario=args.scenario,
            cleanupOnly=args.cleanup_only, candidateAarSha256=args.candidate_aar_sha256,
            candidateBuild=build_receipt, allowTestOnly=args.allow_test_only,
            artifacts=artifacts, certificateSha256=certificates[0],
            commandTimeout=args.command_timeout, installTimeout=args.install_timeout,
             testTimeout=args.test_timeout), indent=2) + '\n')
        if matrix:
            (args.output / 'matrix-input.json').write_text(json.dumps(matrix, indent=2) + '\n')
            (args.output / 'native-sources.json').write_text(json.dumps(native_sources, sort_keys=True, indent=2) + '\n')
        stage = 'device-preflight'
        if matrix:
            lease = native_device.DeviceLease(args.device_lease_root, args.serial, matrix, args.output)
            if args.device_lease_session:
                lease.borrow(args.device_lease_session)
                prepared_run = json.loads((args.device_lease_session / 'run.json').read_text())
                if any(artifacts[label]['sha256'] != prepared_run['artifacts'][label]['sha256'] for label in artifacts):
                    raise RuntimeError('Native artifacts differ from pre-JVM device preflight')
            else:
                lease.acquire()
        scenarios = SCENARIOS if args.scenario == 'all' else [args.scenario]
        requests = [dict(scenario=scenario, label=label, requestId=run_id + '-' + scenario + '-' + label,
                         email=run_id + '-' + scenario + '-' + label + '@upgrade.example')
                    for scenario in scenarios for label in (['A', 'B'] if scenario in ['login-b', 'cookie', 'cookie-signout', 'exposed-cookie-signout'] else ['A'])]
        if matrix:
            request_id = matrix['nativeNonce'] + '-owner'
            requests = [dict(scenario='matrix-native', label='A', requestId=request_id, email=request_id + '@upgrade.example')]
        (args.output / 'recovery.json').write_text(json.dumps(dict(runId=run_id, requests=requests,
            instructions='Known-owner cleanup is independent of SDK sign-out/absence and pending creation. If creation outcome is unknown, '
                         'preserve the original backend process and ask its owner to resolve that exact request. '
                         'Do not retry seed/mutation, guess fixture IDs, clear data or reset globally. '
                         'Known-owner cleanup may be retried with --cleanup-only and original run/scenario.'), indent=2) + '\n')
        if adb(['get-state'], 'device-state.txt').strip() != 'device':
            raise RuntimeError('Device not online')
        adb(['shell', 'getprop', 'ro.build.version.sdk'], 'api.txt')
        adb(['shell', 'getprop', 'ro.build.fingerprint'], 'fingerprint.txt')
        if matrix:
            probe = adb(['shell', 'sh', '-c', "'command -v pidof >/dev/null; echo NATIVE_PIDOF_AVAILABLE:$?'"], 'native-process-probe.txt')
            if probe.strip() != 'NATIVE_PIDOF_AVAILABLE:0':
                raise RuntimeError('Device cannot prove fixture process absence with pidof')
            if lease.shared:
                lease.check_shared_route(adb)
            else:
                lease.configure_route(adb)
            lease.claim_fixture_app(adb)
            if args.preflight_only or args.release_lease_only:
                if args.release_lease_only:
                    lease.finish(adb)
                if matrix_native.source_manifest(HELPERS.parent) != native_sources:
                    raise RuntimeError('Native execution sources changed during device preflight/close')
                kind = 'native-lease-closed' if args.release_lease_only else 'native-device-preflight'
                result = dict(schema=1, kind=kind, binding=matrix, startedMs=native_started, endedMs=time.time_ns() // 1000000,
                              files={path.name: sha256(path) for path in args.output.iterdir() if path.is_file()})
                filename = 'native-lease-closed.json' if args.release_lease_only else 'native-preflight.json'
                (args.output / filename).write_text(json.dumps(result, indent=2) + '\n')
                status = 0
                return status

        def install(flavor, prefix):
            adb(['shell', 'am', 'force-stop', PACKAGE], prefix + '-stop-before-install.txt')
            for label in [flavor, flavor + '-test']:
                flags = ['-r'] + (['-t'] if artifacts[label]['testOnly'] else [])
                text = adb(['install'] + flags + [artifacts[label]['path']], prefix + '-' + label + '-install.txt', args.install_timeout)
                if 'Success' not in text.splitlines():
                    raise RuntimeError('Install did not report success: ' + label)

        def invoke(scenario, action, flavor):
            nonlocal stage
            stage = scenario + '-' + action
            adb(['shell', 'am', 'force-stop', PACKAGE], stage + '-stop.txt')
            klass = CLASS if not matrix else PACKAGE + ('.MatrixNativePublishedTest' if flavor == 'sdk054' else '.MatrixNativeCandidateTest')
            shared.CLASS = klass
            text = adb(['shell', 'am', 'instrument', '-w', '-r',
                        '-e', 'class', klass + '#runStage', '-e', 'stage', action,
                        '-e', 'scenario', scenario, '-e', 'runId', run_id,
                        '-e', 'backend', args.backend, '-e', 'expectedFlavor', flavor] +
                       (['-e', 'matrixConfig', matrix_native.encoded_config(matrix)] if matrix else []) + [
                        TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'],
                       stage + '-instrument.txt', args.test_timeout)
            if not shared.exactly_one_pass(text, 'runStage'):
                raise RuntimeError('Expected exactly one passing stage; see ' + stage + '-instrument.txt')
            if matrix:
                evidence_file = stage + '-evidence.json'
                raw = adb(['exec-out', 'run-as', PACKAGE, 'cat', 'files/matrix-native-' + matrix['nativeNonce'] + '-' + action + '.json'], evidence_file)
                value = matrix_native.validate_stage(json.loads(raw), matrix, action, native_stages)
                artifact = 'sdk' if flavor == 'sdk054' else 'candidate'
                if (value['installedApplicationSha256'] != artifacts[artifact]['sha256'] or
                        value['installedInstrumentationSha256'] != artifacts[artifact + '-test']['sha256']):
                    raise RuntimeError('Installed APK bytes differ from archived artifacts')
                native_stages.append(value)
            names = ['supertokens-android-shared-preferences', 'matrix-native-receipt' if matrix else 'upgrade-receipt']
            if matrix or scenario == 'cookie' or scenario in CONTINUATIONS:
                names.append('supertokens-cookie-shared-prefs')
            for name in names:
                filename = stage + '-' + name + '.xml'
                adb(['exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/' + name + '.xml'], filename)
                tree = ET.parse(args.output / filename).getroot()
                if tree.tag != 'map':
                    raise RuntimeError('Invalid preferences XML: ' + filename)
                captures.append(dict(file=filename, sha256=sha256(args.output / filename),
                                     types=[element.tag for element in tree]))
                (args.output / 'captures.json').write_text(json.dumps(captures, indent=2) + '\n')
            print(stage + ' PASS', flush=True)
            with (args.output / 'summary.txt').open('a') as summary:
                summary.write(stage + ' PASS\n')

        for scenario in SCENARIOS if args.scenario == 'all' else [args.scenario]:
            active_scenario = scenario
            if not args.cleanup_only:
                stages = [
                    ('seed', 'sdk', 'sdk054'), ('check-initial', 'candidate', 'candidate'),
                    ('mutate', 'sdk', 'sdk054'), ('check-final', 'candidate', 'candidate'),
                ]
                if matrix or scenario in CONTINUATIONS:
                    stages = stages[:2]
                for action, artifact, flavor in stages:
                    stage = scenario + '-' + action + '-install'
                    install(artifact, scenario + '-' + action)
                    invoke(scenario, action, flavor)
            else:
                stage = scenario + '-cleanup-install'
                install('candidate', scenario + '-cleanup')
            invoke(scenario, 'cleanup', 'candidate')
        if matrix:
            lease.finish(adb, release=not lease.shared, preserve_route=lease.shared, successful_row=lease.shared)
        cancellation.check()
        if matrix:
            if matrix_native.source_manifest(HELPERS.parent) != native_sources:
                raise RuntimeError('Native execution sources changed during run')
            if [value['stage'] for value in native_stages] != matrix_native.STAGES:
                raise RuntimeError('Incomplete native matrix stages')
            result = dict(schema=1, kind='matrix-native-device', status='device-assertions-passed',
                          releaseAccepted=False, binding=matrix, stages=native_stages,
                          startedMs=native_started, endedMs=time.time_ns() // 1000000,
                          requiredExternalProofs=['wire-order-and-fdi', 'same-owned-backend-and-resolved-pair',
                              'login-session-revocation', 'artifact-source-and-dex-binding', 'owned-lifecycle-cleanup'],
                          files={path.name: sha256(path) for path in args.output.iterdir() if path.is_file()})
            (args.output / 'native-device.json').write_text(json.dumps(result, indent=2) + '\n')
        status = 0
        print('Evidence: ' + str(args.output))
    except shared.Cancelled as error:
        status = error.code
    except (OSError, RuntimeError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError) as error:
        print('upgrade: ' + str(error), file=sys.stderr)
        failure = dict(stage=stage, error=str(error), runId=run_id, scenario=active_scenario)
        if active_scenario is not None and not matrix:
            recovery = [sys.executable, str(Path(__file__).resolve()), '--cleanup-only',
                        '--run-id', run_id, '--scenario', active_scenario,
                        '--output', str(args.output) + '-recovery-' + uuid.uuid4().hex[:8]]
            for name in ['serial', 'backend', 'sdk_apk', 'sdk_test_apk', 'candidate_apk',
                         'candidate_test_apk', 'candidate_aar_sha256', 'candidate_build_provenance',
                         'aapt', 'apksigner', 'command_timeout', 'install_timeout', 'test_timeout']:
                recovery += ['--' + name.replace('_', '-'), str(getattr(args, name))]
            if args.allow_test_only:
                recovery.append('--allow-test-only')
            failure['recoveryCommand'] = recovery
            print('Independent known-owner recovery (original run remains FAILED): ' + shlex.join(recovery), file=sys.stderr)
        try:
            (args.output / 'failure.json').write_text(json.dumps(failure, indent=2) + '\n')
        except OSError as receipt_error:
            print('Could not persist failure receipt: ' + str(receipt_error), file=sys.stderr)
    finally:
        if status and lease is not None and lease.acquired:
            cancellation = shared.Cancellation()
            try:
                lease.finish(adb, release=False, preserve_route=lease.shared)
            except (OSError, RuntimeError, ValueError) as cleanup_error:
                print('Owned device drain/routing incomplete; lease retained: ' + str(cleanup_error), file=sys.stderr)
        if status:
            print('upgrade: stopped at %s; evidence: %s; run ID: %s' % (stage, args.output, run_id), file=sys.stderr)
            print('Scenario remains failed even if instrumentation recovered known owners. Inspect logs/receipts; never global reset.', file=sys.stderr)
            receipt_name = 'matrix-native-receipt' if matrix else 'upgrade-receipt'
            print('Creation response may be lost: inspect recovery.json and device ' + receipt_name + ' pendingRequestId. '
                  'Pending creation does not block known-owner cleanup, but prevents cleanupDone. '
                  'Ask the backend owner to resolve that exact request; do not retry creation.', file=sys.stderr)
    return status


if __name__ == '__main__':
    sys.exit(main())
