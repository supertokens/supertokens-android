"""Synthetic receipt/protocol controls only; no Android or backend certification."""
import json
import tempfile
from pathlib import Path
import unittest
import zipfile
import matrix_native
import native_device
import test_upgrade
from test_r8_smoke import fixture_dex
from build_provenance import digest
from native_backend import digest as identity_digest


def config():
    value = dict(schema=1, kind='matrix-native-input', mode='COOKIE', flow='REFRESH', route='/login',
                deviceBackend='http://127.0.0.1:18081', candidateAarSha256='a' * 64, runId='b' * 48,
                nativeNonce='c' * 48, preparationNonce='d' * 48, instance='e' * 48,
                leaseNonce='c' * 48,
                challenge='1' * 48,
                qualifiedIdentitySha256='1' * 64, dependenciesSha256='2' * 64,
                coreManifestSha256='8' * 64, planSha256='9' * 64, publishedAarSha256=test_upgrade.PIN,
                nodeSdkSha256='3' * 64, coreSha256='4' * 64, executionSourcesSha256='5' * 64,
                context=dict(sha='a' * 40, nonce='f' * 48, sources='6' * 64, policySha256='7' * 64,
                             workflow='synthetic', attempt=1, fdi='4.1', backend='http://127.0.0.1:18081',
                             core='http://127.0.0.1:19001'),
                plan=dict(review='synthetic-test-only', nodeVersion='16.7.0', coreVersion='8.0.0',
                          phases=dict(publishedLogin='5', publishedHandoff='5', candidateRefresh='5')))
    value['qualifiedIdentitySha256'] = identity_digest(identity(value))
    return value


def identity(binding):
    return dict(nodeSdkVersion=binding['plan']['nodeVersion'], coreVersion=binding['plan']['coreVersion'],
                sdkImplementationSha256=binding['nodeSdkSha256'], coreSha256=binding['coreSha256'], effectiveClasspathSha256=binding['coreManifestSha256'])


def stage(binding, action):
    seed = action == 'seed'
    out = binding['flow'] == 'SIGN_OUT'
    phases = matrix_native.PHASES[:2] if seed else matrix_native.PHASES
    observed = dict(schemaVersion=2)
    for name in phases:
        absent = out and name == 'publishedHandoff'
        observed[name] = dict(status='ABSENT' if absent else 'PRESENT', tokenVersion=None if absent else '5')
    prefix = ['isolated:/test/capabilities', 'isolated:/featureFlags']
    if seed:
        operations = ['isolated:/test/native/challenge'] + prefix + ['isolated:/test/email-change-owner', 'managed:/login', 'sdk.signOut' if out else 'sdk.refresh']
        if out:
            operations += ['isolated:/test/owner-session-state']
        headers = ['SIGN_OUT' if out else 'REFRESH']
    elif action == 'check-initial':
        operations = prefix + (['managed:/login'] if out else []) + ['managed:/', 'sdk.refresh', 'managed:/', 'managed:/update-jwt', 'sdk.signOut', 'isolated:/test/owner-session-state']
        headers = ['REFRESH', 'SIGN_OUT']
    else:
        operations = ['isolated:/test/cleanup-owner']
        headers = []
    challenge = {name: binding[name] for name in ['runId', 'instance', 'nativeNonce', 'challenge', 'qualifiedIdentitySha256', 'dependenciesSha256', 'coreManifestSha256']}
    challenge.update(schema=1, kind='native-backend-challenge', identity=identity(binding))
    checks = [dict(fixtureId='synthetic-owner', handle='actual-B' if out and action == 'check-initial' else 'actual-A', owned=True, active=False)] if action == 'check-initial' or (seed and out) else []
    return dict(schema=1, kind='matrix-native-stage', stage=action, flavor='sdk054' if seed else 'candidate',
                process='synthetic-' + action, binding=binding, observed=observed, assertionsPassed=True,
                cleanupDone=action == 'cleanup', wireVerified=False, backendIdentityVerified=False,
                installedApplicationSha256='a' * 64, installedInstrumentationSha256='b' * 64,
                challengeResponse=challenge, revocationResponses=checks,
                loginRevocationVerified=False, clientOperations=operations, customHeaderRequests=headers)


class ReceiptControls(unittest.TestCase):
    def test_resource_mutation_invalidates_both_source_bindings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / 'testHelpers/upgradeapp'
            asset = app / 'app/src/androidTest/assets/cookie-test-only-cert.pem'
            resource = app / 'app/src/main/res/xml/test.xml'
            for path in [asset, resource, app / 'app/build.gradle', app / 'build.gradle',
                         app / 'settings.gradle', app / 'gradle.properties',
                         root / 'testHelpers/device/restart-pairs.py']:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('original')
            compiled = matrix_native.compiled_sources(root)
            execution = matrix_native.source_manifest(root)
            for path in [asset, resource]:
                relative = str(path.relative_to(root))
                self.assertIn(relative, compiled)
                self.assertIn(relative, execution)
                path.write_text('changed')
                self.assertNotEqual(compiled, matrix_native.compiled_sources(root))
                self.assertNotEqual(execution, matrix_native.source_manifest(root))
                path.write_text('original')
                self.assertEqual(compiled, matrix_native.compiled_sources(root))
                self.assertEqual(execution, matrix_native.source_manifest(root))

    def test_four_exact_sequences(self):
        for mode in ['COOKIE', 'EXPOSED_COOKIE']:
            for flow in ['REFRESH', 'SIGN_OUT']:
                binding = dict(config(), mode=mode, flow=flow)
                if flow == 'SIGN_OUT':
                    binding['plan']['phases']['publishedHandoff'] = 'ABSENT'
                previous = []
                for action in matrix_native.STAGES:
                    value = stage(binding, action)
                    previous.append(matrix_native.validate_stage(value, binding, action, previous))

    def test_reject_stage_mutations(self):
        binding = config()
        mutations = [
            ('assertionsPassed', False), ('cleanupDone', True), ('wireVerified', True),
            ('backendIdentityVerified', True), ('loginRevocationVerified', True),
            ('stage', 'cleanup'), ('flavor', 'candidate'), ('process', ''),
            ('observed', {}), ('customHeaderRequests', []),
            ('clientOperations', ['managed:/test/email-change-owner', 'sdk.refresh']),
            ('binding', dict(binding, nativeNonce='0' * 48)),
        ]
        for key, value in mutations:
            with self.subTest(key=key), self.assertRaises(ValueError):
                matrix_native.validate_stage(dict(stage(binding, 'seed'), **{key: value}), binding, 'seed', [])

    def test_reject_intervening_protected_request(self):
        binding = config()
        value = stage(binding, 'seed')
        value['clientOperations'].insert(-1, 'managed:/')
        with self.assertRaises(ValueError):
            matrix_native.validate_stage(value, binding, 'seed', [])

    def test_reject_reused_process_or_wrong_phase(self):
        binding = config()
        previous = [stage(binding, 'seed')]
        value = stage(binding, 'check-initial')
        value['process'] = previous[0]['process']
        with self.assertRaises(ValueError):
            matrix_native.validate_stage(value, binding, 'check-initial', previous)
        value = stage(binding, 'check-initial')
        value['observed']['publishedLogin']['tokenVersion'] = '4'
        with self.assertRaises(ValueError):
            matrix_native.validate_stage(value, binding, 'check-initial', previous)


class RunnerControls(unittest.TestCase):
    setUp = test_upgrade.UpgradeProtocol.setUp
    run_case = test_upgrade.UpgradeProtocol.run_case
    commands = test_upgrade.UpgradeProtocol.commands

    def prepare_matrix(self, mode='COOKIE', flow='REFRESH'):
        self.binding = config()
        self.binding.update(mode=mode, flow=flow)
        if flow == 'SIGN_OUT':
            self.binding['plan']['phases']['publishedHandoff'] = 'ABSENT'
        library = self.root / 'lib'
        library.mkdir()
        (library / 'apksigner.jar').write_bytes(b'SYNTHETIC TOOL PIN - NOT A REAL SIGNER')
        (self.root / 'apksigner').write_text("#!/usr/bin/env python3\nprint('Signer #1 certificate SHA-256 digest: " + 'a' * 64 + "')\n")
        self.binding['toolchain'] = dict(apksigner=native_device.signer_identity(str(self.root / 'apksigner')))
        self.input = self.root / 'matrix.json'
        self.input.write_text(json.dumps(self.binding))
        self.args[self.args.index('--backend') + 1] = self.binding['deviceBackend']
        lease_root = self.root / 'leases'
        lease_root.mkdir(mode=0o700)
        self.args += ['--scenario', 'matrix-native', '--run-id', self.binding['runId'], '--matrix-config', str(self.input),
                      '--device-lease-root', str(lease_root)]
        for label in ['sdk', 'sdk-test', 'candidate', 'candidate-test']:
            with zipfile.ZipFile(self.root / (label + '.apk'), 'a') as apk:
                if label.endswith('-test'):
                    apk.writestr('assets/native-test-provenance.json', json.dumps(dict(schema=1, sources=matrix_native.compiled_sources(Path(__file__).resolve().parents[3]))))
                names = ['Lcom/supertokens/upgrade/MatrixNativeStage;'] if label.endswith('-test') else [
                    'Lcom/supertokens/session/SuperTokens;', 'Lcom/supertokens/session/SuperTokensHttpURLConnection;',
                    'Lcom/supertokens/session/SuperTokensPersistentCookieStore;']
                for index, name in enumerate(names):
                    apk.writestr('classes' + (str(index + 1) if index else '') + '.dex', fixture_dex(name))
        # Fake instrument and run-as agree on the selected stage; these are deliberately synthetic assertions.
        script = test_upgrade.FAKE.replace('import json, os, sys, time', 'import json, os, sys, time, base64, xml.etree.ElementTree as ET')
        script = script.replace("print('INSTRUMENTATION_STATUS: class=com.supertokens.upgrade.UpgradeTest')",
                                "print('INSTRUMENTATION_STATUS: class=' + a[a.index('class') + 1].split('#')[0])")
        script = script.replace("if mode == 'hang': time.sleep(60)",
                                "Path(os.environ['CALLS'] + '.stage').write_text(a[a.index('stage') + 1])\n        if mode == 'hang': time.sleep(60)")
        script = script.replace("if a == ['get-state']: print('device')", """if a[0] == 'reverse':
        state = Path(os.environ['CALLS'] + '.reverse')
        if a[1] == '--list': print(state.read_text() if state.exists() else '', end='')
        elif a[1] == '--no-rebind': state.write_text('fake-serial ' + a[2] + ' ' + a[3] + '\\n')
        elif a[1] == '--remove': state.unlink()
        else: raise AssertionError(a)
    elif a[:3] == ['shell', 'sh', '-c']:
        print('NATIVE_PIDOF_AVAILABLE:0' if 'command -v pidof' in a[-1] else 'NATIVE_PIDOF_EXIT:1')
    elif a[:4] == ['shell', 'pm', 'list', 'packages']:
        target = a[-1]
        if mode == 'package-nonzero': sys.exit(1)
        if mode == 'package-timeout': time.sleep(60)
        if mode in ['package-malformed', 'package-permission', 'package-offline']:
            print({'package-malformed':'package:bad name', 'package-permission':'SecurityException: permission denied', 'package-offline':'error: device offline'}[mode])
        elif mode == 'package-test-permission':
            print('SecurityException: permission denied' if target.endswith('.test') else 'package:' + target)
        elif mode == 'package-substring': print('package:' + target + '.other')
        elif mode == 'package-orphan':
            if target.endswith('.test'): print('package:' + target)
        elif mode in ['occupied-app', 'package-present', 'package-path-failure', 'package-path-empty', 'package-unresolved', 'package-hash',
                      'package-test-path-failure', 'package-test-path-empty', 'package-path-malformed']:
            print('package:' + target)
    elif a[:3] == ['shell', 'pm', 'path']:
        if mode == 'package-path-malformed': print('Error: permission denied')
        elif mode in ['package-test-path-failure', 'package-test-path-empty']:
            if not a[-1].endswith('.test'): print('package:/data/app/' + a[-1] + '/base.apk')
            elif mode == 'package-test-path-failure': sys.exit(1)
        elif mode in ['occupied-app', 'package-present', 'package-orphan', 'package-unresolved', 'package-hash']:
            print('package:/data/app/' + a[-1] + '/base.apk')
        elif mode == 'package-path-empty': pass
        else: sys.exit(1)
    elif a[:2] == ['shell', 'sha256sum']:
        import hashlib
        digest = hashlib.sha256((Path(__file__).parent / 'candidate-test.apk').read_bytes()).hexdigest()
        print(('0' * 64 if mode == 'package-hash' else digest) + '  ' + a[-1])
    elif a == ['get-state']: print('device')""")
        script = script.replace("print('<broken' if mode == 'xml' else '<map><string name=\"secret\">TOKEN_SENTINEL</string><long name=\"revision\" value=\"3\" /></map>')", """
        if a[-1] == 'shared_prefs':
            if mode != 'occupied-app': print('matrix-native-receipt.xml')
        elif a[-1].endswith('matrix-native-receipt.xml') and (mode == 'package-unresolved' or (mode == 'package-present' and not Path(os.environ['CALLS'] + '.stage').exists())):
            print('<map><boolean name="cleanupDone" value="' + ('false' if mode == 'package-unresolved' else 'true') + '" /></map>')
        elif a[-1].endswith('.json'):
            action = Path(os.environ['CALLS'] + '.stage').read_text()
            value = json.loads(Path(os.environ['CALLS'] + '.' + action).read_text())
            if mode == 'bad-binding': value['binding']['instance'] = '0' * 48
            if mode == 'fake-authority': value['wireVerified'] = True
            print(json.dumps(value))
        elif a[-1].endswith('matrix-native-receipt.xml'):
            action = Path(os.environ['CALLS'] + '.stage').read_text()
            value = json.loads(Path(os.environ['CALLS'] + '.' + action).read_text())
            tree = ET.Element('map')
            binding = value['binding']
            encoded = base64.b64encode(json.dumps(binding, sort_keys=True, separators=(',', ':')).encode()).decode()
            fields = dict(binding=encoded, runId=binding['runId'], process=value['process'], observed=json.dumps(value['observed']),
                          phase={'seed':'seeded','check-initial':'continued','cleanup':'cleaned'}[action])
            if action != 'cleanup': fields['fixtureId'] = 'synthetic-owner'
            for key, item in fields.items(): ET.SubElement(tree, 'string', name=key).text = item
            ET.SubElement(tree, 'boolean', name='cleanupDone', value='true' if action == 'cleanup' else 'false')
            print(ET.tostring(tree, encoding='unicode'))
        else: print('<map />')""")
        for action in matrix_native.STAGES:
            value = stage(self.binding, action)
            label = 'sdk' if action == 'seed' else 'candidate'
            value['installedApplicationSha256'] = digest((self.root / (label + '.apk')).read_bytes())
            value['installedInstrumentationSha256'] = digest((self.root / (label + '-test.apk')).read_bytes())
            Path(str(self.calls) + '.' + action).write_text(json.dumps(value))
        (self.root / 'adb').write_text(script)
        import native_build
        compiled = self.root / 'compiled'
        compiled.mkdir()
        for label in native_build.LABELS:
            (compiled / (label + '.apk')).write_bytes((self.root / (label + '.apk')).read_bytes())
        (compiled / 'build.log').write_bytes(b'EXPLICIT HOST COMPILATION DOUBLE; NOT RELEASE EVIDENCE')
        repo = Path(__file__).resolve().parents[3]
        candidate_receipt = Path(self.args[self.args.index('--candidate-build-provenance') + 1])
        candidate = self.root / 'candidate.aar'
        build = dict(schema=1, kind='native-apk-build', sources=native_build.sources(repo), exitCode=0,
            candidateAar=str(candidate), candidateBuildPath=str(candidate_receipt), candidateBuild=json.loads(candidate_receipt.read_bytes()),
            candidateAarSha256=self.binding['candidateAarSha256'], publishedAarSha256=self.binding['publishedAarSha256'],
            command=native_build.command(repo, candidate, candidate_receipt, self.binding['candidateAarSha256']),
            startedAt='2000-01-01T00:00:00+00:00', finishedAt='2000-01-01T00:00:01+00:00',
            buildLogSha256=digest((compiled / 'build.log').read_bytes()),
            artifacts={label: digest((compiled / (label + '.apk')).read_bytes()) for label in native_build.LABELS})
        build_path = compiled / 'native-build.json'
        build_path.write_text(json.dumps(build))
        self.native_build_pin = dict(path=str(build_path), sha256=digest(build_path.read_bytes()))
        self.args += ['--native-build-receipt', str(build_path), '--native-build-sha256', self.native_build_pin['sha256']]

    def test_native_runner_receipt_is_not_release_success(self):
        self.prepare_matrix()
        result = self.run_case()
        self.assertEqual(0, result.returncode, result.stderr)
        receipt = json.loads((self.root / 'results/native-device.json').read_text())
        self.assertFalse(receipt['releaseAccepted'])
        self.assertEqual('device-assertions-passed', receipt['status'])
        calls = [c for c in self.commands() if 'instrument' in c]
        self.assertEqual(matrix_native.STAGES, [c[c.index('stage') + 1] for c in calls])
        self.assertEqual(['MatrixNativePublishedTest', 'MatrixNativeCandidateTest', 'MatrixNativeCandidateTest'],
                         [c[c.index('class') + 1].split('.')[-1].split('#')[0] for c in calls])
        self.assertTrue(all('matrixConfig' in c for c in calls))

    def test_package_absence_and_substrings_never_query_path(self):
        self.prepare_matrix()
        for mode in ['normal', 'package-substring']:
            out = self.root / mode
            result = self.run_case(mode, ['--preflight-only', '--output', str(out)])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse((out / 'native-preflight-package.txt').exists())
            self.assertFalse((out / 'native-preflight-test-package.txt').exists())
            self.assertFalse(any(command[-4:-1] == ['shell', 'pm', 'path'] for command in self.commands()))
            closed = self.run_case(mode, ['--device-lease-session', str(out), '--release-lease-only', '--output', str(self.root / (mode + '-close'))])
            self.assertEqual(0, closed.returncode, closed.stderr)

    def test_present_packages_require_paths_hash_and_resolved_owner(self):
        self.prepare_matrix()
        out = self.root / 'present'
        result = self.run_case('package-present', ['--preflight-only', '--output', str(out)])
        self.assertEqual(0, result.returncode, result.stderr)
        import native_archive
        native_archive.inspect_archive(out, 'native-preflight.json')
        for name in ['native-preflight-package.txt', 'native-preflight-test-package.txt', 'native-preflight-test-sha256.txt']:
            self.assertTrue((out / name).read_bytes())
        self.assertFalse(any('install' in command or 'force-stop' in command for command in self.commands()))

    def test_package_discovery_failures_never_install_or_stop(self):
        self.prepare_matrix()
        cases = {'package-malformed': 'Malformed package listing', 'package-permission': 'Malformed package listing',
                 'package-test-permission': 'Malformed package listing', 'package-offline': 'Malformed package listing',
                 'package-nonzero': 'Command failed (1)', 'package-timeout': 'Command failed (124)', 'package-orphan': 'Orphan instrumentation',
                 'package-path-failure': 'Command failed (1)', 'package-path-empty': 'Invalid/empty installed package path',
                 'package-test-path-failure': 'Command failed (1)', 'package-test-path-empty': 'Invalid/empty installed package path',
                 'package-path-malformed': 'Invalid/empty installed package path',
                 'package-unresolved': 'Prior fixture owner unresolved', 'package-hash': 'instrumentation does not match'}
        for mode, reason in cases.items():
            with self.subTest(mode=mode):
                out = self.root / mode
                leases = self.root / (mode + '-leases')
                leases.mkdir(mode=0o700)
                result = self.run_case(mode, ['--preflight-only', '--output', str(out), '--command-timeout', '1',
                                             '--device-lease-root', str(leases)])
                self.assertNotEqual(0, result.returncode)
                self.assertIn(reason, result.stderr)
                self.assertTrue((out / 'native-preflight-packages.txt').exists(), result.stderr)
                self.assertFalse((out / 'native-preflight.json').exists())
                self.assertFalse(any('install' in command or 'force-stop' in command for command in self.commands()))
    def test_wrong_binding_never_completes(self):
        self.prepare_matrix()
        self.assertNotEqual(0, self.run_case('bad-binding').returncode)
        self.assertFalse((self.root / 'results/native-device.json').exists())
        failure = json.loads((self.root / 'results/failure.json').read_text())
        self.assertNotIn('recoveryCommand', failure)

    def test_claimed_wire_authority_never_completes(self):
        self.prepare_matrix()
        self.assertNotEqual(0, self.run_case('fake-authority').returncode)
        self.assertFalse((self.root / 'results/native-device.json').exists())

    def test_skipped_or_wrong_instrumentation_never_completes(self):
        self.prepare_matrix()
        self.assertNotEqual(0, self.run_case('zero').returncode)
        self.assertFalse((self.root / 'results/native-device.json').exists())

    def test_invalid_inputs_fail_before_tools(self):
        self.prepare_matrix()
        for key, value in [('route', '/login-2.18'), ('mode', 'HEADER'), ('flow', 'LOGIN'),
                           ('candidateAarSha256', '0' * 64), ('nativeNonce', 'old')]:
            with self.subTest(key=key):
                self.input.write_text(json.dumps(dict(self.binding, **{key: value})))
                self.assertNotEqual(0, self.run_case().returncode)
                self.assertEqual([], self.commands())

    def test_unreviewed_plan_fails_before_tools(self):
        self.prepare_matrix()
        self.binding['plan']['review'] = ''
        self.input.write_text(json.dumps(self.binding))
        self.assertNotEqual(0, self.run_case().returncode)
        self.assertEqual([], self.commands())

    def test_unresolved_or_malformed_plan_fails_before_tools(self):
        self.prepare_matrix()
        for plan in [None, [], dict(self.binding['plan'], phases=None), dict(self.binding['plan'], phases={}),
                     dict(self.binding['plan'], phases=dict(publishedLogin='5', publishedHandoff='UNKNOWN', candidateRefresh='5'))]:
            with self.subTest(plan=plan):
                self.input.write_text(json.dumps(dict(self.binding, plan=plan)))
                self.assertNotEqual(0, self.run_case().returncode)
                self.assertEqual([], self.commands())

    def test_cleanup_only_is_not_native_acceptance(self):
        self.prepare_matrix()
        self.assertNotEqual(0, self.run_case(extra=['--cleanup-only']).returncode)
        self.assertEqual([], self.commands())

    def test_preflight_refuses_unowned_app_without_stopping_it(self):
        self.prepare_matrix()
        result = self.run_case('occupied-app', ['--preflight-only'])
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any('force-stop' in command or 'install' in command for command in self.commands()))
        self.assertFalse((self.root / 'results/native-preflight.json').exists())

    def test_preflight_rejects_stale_instrumentation_build_before_device(self):
        self.prepare_matrix()
        apk = self.root / 'sdk-test.apk'
        with zipfile.ZipFile(apk) as archive:
            entries = {info.filename: archive.read(info) for info in archive.infolist()}
        entries['assets/native-test-provenance.json'] = json.dumps(dict(schema=1, sources={})).encode()
        with zipfile.ZipFile(apk, 'w') as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        result = self.run_case(extra=['--preflight-only'])
        self.assertNotEqual(0, result.returncode)
        self.assertIn('trusted compilation output', result.stderr)
        self.assertFalse(any(command[0] == 'adb' for command in self.commands()))

    def test_preflight_rejects_stale_cookie_asset_before_device(self):
        self.prepare_matrix()
        apk = self.root / 'sdk-test.apk'
        with zipfile.ZipFile(apk) as archive:
            entries = {info.filename: archive.read(info) for info in archive.infolist()}
        provenance = json.loads(entries['assets/native-test-provenance.json'])
        key = 'testHelpers/upgradeapp/app/src/androidTest/assets/cookie-test-only-cert.pem'
        self.assertIn(key, provenance['sources'])
        provenance['sources'][key] = digest(b'previous asset contents')
        entries['assets/native-test-provenance.json'] = json.dumps(provenance).encode()
        with zipfile.ZipFile(apk, 'w') as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        result = self.run_case(extra=['--preflight-only'])
        self.assertNotEqual(0, result.returncode)
        self.assertIn('trusted compilation output', result.stderr)
        self.assertFalse(any(command[0] == 'adb' for command in self.commands()))

    def test_retained_lease_spans_two_rows_and_coordinator_close(self):
        self.prepare_matrix()
        preflight = self.root / 'preflight'
        result = self.run_case(extra=['--preflight-only', '--output', str(preflight)])
        self.assertEqual(0, result.returncode, result.stderr)
        lease = next((self.root / 'leases').iterdir())
        owner = (lease / 'owner.json').read_bytes()
        self.assertFalse(any('instrument' in command or 'install' in command for command in self.commands()))
        first_input = json.loads(self.input.read_text())
        for flow, nonce in [('REFRESH', first_input['nativeNonce']), ('SIGN_OUT', 'a1' * 24)]:
            binding = json.loads(json.dumps(first_input))
            binding.update(flow=flow, nativeNonce=nonce)
            if flow == 'SIGN_OUT':
                binding['challenge'] = 'b2' * 24
                binding['plan']['phases']['publishedHandoff'] = 'ABSENT'
            self.input.write_text(json.dumps(binding))
            for action in matrix_native.STAGES:
                value = stage(binding, action)
                label = 'sdk' if action == 'seed' else 'candidate'
                value['installedApplicationSha256'] = digest((self.root / (label + '.apk')).read_bytes())
                value['installedInstrumentationSha256'] = digest((self.root / (label + '-test.apk')).read_bytes())
                Path(str(self.calls) + '.' + action).write_text(json.dumps(value))
            result = self.run_case(extra=['--device-lease-session', str(preflight), '--output', str(self.root / flow)])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(owner, (lease / 'owner.json').read_bytes())
        self.input.write_text(json.dumps(first_input))
        result = self.run_case(extra=['--device-lease-session', str(preflight), '--release-lease-only', '--output', str(self.root / 'closed')])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(lease.exists())
        self.assertFalse(Path(str(self.calls) + '.reverse').exists())

    def test_failed_child_retains_lease_until_coordinator_cleanup_without_reclassification(self):
        self.prepare_matrix()
        preflight = self.root / 'preflight'
        result = self.run_case(extra=['--preflight-only', '--output', str(preflight)])
        self.assertEqual(0, result.returncode, result.stderr)
        failed = self.root / 'failed'
        result = self.run_case('zero', ['--device-lease-session', str(preflight), '--output', str(failed)])
        self.assertNotEqual(0, result.returncode)
        original = (failed / 'failure.json').read_bytes()
        self.assertEqual(1, len(list((self.root / 'leases').iterdir())))
        result = self.run_case(extra=['--device-lease-session', str(preflight), '--release-lease-only', '--output', str(self.root / 'closed')])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(original, (failed / 'failure.json').read_bytes())
        self.assertFalse((failed / 'native-device.json').exists())

    def test_competing_preflight_cannot_acquire_or_release_held_selector_lease(self):
        self.prepare_matrix()
        result = self.run_case(extra=['--preflight-only', '--output', str(self.root / 'preflight')])
        self.assertEqual(0, result.returncode, result.stderr)
        lease = next((self.root / 'leases').iterdir())
        owner = (lease / 'owner.json').read_bytes()
        result = self.run_case(extra=['--preflight-only', '--output', str(self.root / 'competing')])
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(owner, (lease / 'owner.json').read_bytes())

    def test_unfinished_borrower_blocks_new_row_and_coordinator_release(self):
        self.prepare_matrix()
        preflight = self.root / 'preflight'
        result = self.run_case(extra=['--preflight-only', '--output', str(preflight)])
        self.assertEqual(0, result.returncode, result.stderr)
        lease = next((self.root / 'leases').iterdir())
        marker = lease / 'borrower.json'
        marker.write_bytes(b'original interrupted borrower; no automatic takeover')
        original = marker.read_bytes()
        for suffix, flags in [('row', []), ('close', ['--release-lease-only'])]:
            result = self.run_case(extra=['--device-lease-session', str(preflight), '--output', str(self.root / suffix)] + flags)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(original, marker.read_bytes())


if __name__ == '__main__':
    unittest.main()
