"""Archive mutations use fake tools; no successful release proof is fabricated."""
import json
from pathlib import Path
import zipfile
import shutil
import subprocess
import unittest
import native_archive
import test_matrix_native
from test_r8_smoke import fixture_dex
from test_native_backend import write_backend


class ArchiveControls(unittest.TestCase):
    setUp = test_matrix_native.RunnerControls.setUp
    run_case = test_matrix_native.RunnerControls.run_case
    prepare_matrix = test_matrix_native.RunnerControls.prepare_matrix

    def archive(self, mode='COOKIE', flow='REFRESH', device_mode='normal'):
        self.prepare_matrix(mode, flow)
        self.parent = self.root / 'selection'
        self.directory = self.parent / 'native' / self.binding['nativeNonce']
        self.directory.parent.mkdir(parents=True)
        self.preflight = self.root / 'preflight'
        prepared = self.run_case(device_mode, extra=['--preflight-only', '--output', str(self.preflight)])
        self.assertEqual(0, prepared.returncode, prepared.stderr)
        self.args += ['--device-lease-session', str(self.preflight)]
        index = self.args.index('--output') + 1
        self.args[index] = str(self.directory)
        result = self.run_case(device_mode)
        self.assertEqual(0, result.returncode, result.stderr)
        self.closed = self.root / 'closed'
        closed = self.run_case(device_mode, extra=['--release-lease-only', '--output', str(self.closed)])
        self.assertEqual(0, closed.returncode, closed.stderr)
        return native_archive.inspect_archive(self.directory)

    def envelope(self, inspected):
        source = self.binding
        binding = {key: source[key] for key in ['context', 'mode', 'route', 'runId', 'instance', 'qualifiedIdentitySha256',
                    'dependenciesSha256', 'nodeSdkSha256', 'coreSha256', 'coreManifestSha256', 'candidateAarSha256',
                    'publishedAarSha256', 'planSha256', 'executionSourcesSha256']}
        binding.update(nodeVersion=source['plan']['nodeVersion'], coreVersion=source['plan']['coreVersion'], sessionJwt=source['mode'] == 'EXPOSED_COOKIE')
        receipt = inspected['receipt']
        row = dict(schema=2, kind='matrix-native-upgrade', mode=source['mode'], flow=source['flow'], route='/login',
                   sessionJwt=binding['sessionJwt'], parentPreparationNonce=source['preparationNonce'], executionNonce=source['nativeNonce'],
                   rowId=source['mode'] + ':' + source['flow'] + ':' + source['nativeNonce'],
                   startedMs=receipt['startedMs'], endedMs=receipt['endedMs'], binding=binding,
                   rawEvidenceSha256=inspected['rawEvidenceSha256'], observed=receipt['stages'][-1]['observed'],
                   publishedFirst=True, candidateContinued=True, scopedCleanup=True)
        owned = self.root / 'owned'
        backend = write_backend(owned, source, receipt['stages'], row['startedMs'], row['endedMs'])
        sibling = self.parent / 'native-backend' / source['nativeNonce']
        sibling.parent.mkdir(mode=0o700)
        sibling.mkdir(mode=0o700)
        for name in ['events.jsonl', 'complete.json']:
            shutil.copyfile(backend / name, sibling / name)
            (sibling / name).chmod(0o600)
        pre = json.loads((self.preflight / 'native-preflight.json').read_text())
        close = json.loads((self.closed / 'native-lease-closed.json').read_text())
        expected = dict(binding=binding, preparationNonce=source['preparationNonce'], startedMs=pre['startedMs'], endedMs=close['endedMs'] + 1000,
                        nativeBuild=self.native_build_pin,
                        owned=str(owned), nativeSerial='fake-serial', nativeLeaseRoot=str(self.root / 'leases'), nativeTools=source['toolchain'],
                        nativePreflight=str(self.preflight), nativeLeaseClose=str(self.closed), jvmStartedMs=pre['endedMs'],
                        plan=dict(review=dict(reference=source['plan']['review']), phaseVersions={source['mode'] + ':/login': dict(
                            publishedLogin='5', candidateRefresh='5', publishedHandoff=dict(LOGIN='5', REFRESH='5', SIGN_OUT='ABSENT', PAYLOAD_UPDATE='5'))}))
        return row, expected

    def rewrite_receipt(self, name, data):
        (self.directory / name).write_bytes(data)
        receipt = json.loads((self.directory / 'native-device.json').read_text())
        receipt['files'][name] = native_archive.sha(data)
        (self.directory / 'native-device.json').write_text(json.dumps(receipt))

    def test_fixed_export_derives_positive_facts_from_complete_artifacts(self):
        row, expected = self.envelope(self.archive())
        facts = native_archive.verify_native(row, expected, self.parent)
        self.assertTrue(facts['deviceCleanup'])
        self.assertTrue(facts['workersDrained'])
        self.assertEqual(0, facts['pendingCreations'])
        script = "const {verifyNative}=require('./nativeArchive'); let input=''; process.stdin.on('data',x=>input+=x); process.stdin.on('end',()=>{ const x=JSON.parse(input); console.log(JSON.stringify(verifyNative(x.row,x.expected,x.directory))); });"
        result = subprocess.run(['node', '-e', script], input=json.dumps(dict(row=row, expected=expected, directory=str(self.parent))),
                                cwd=Path(__file__).parent, capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(facts, json.loads(result.stdout))

    def test_present_package_archive_and_conditional_path_tampering(self):
        row, expected = self.envelope(self.archive(device_mode='package-present'))
        self.assertTrue(native_archive.verify_native(row, expected, self.parent)['deviceCleanup'])
        self.check_package_archive_tampering(present=True)

    def test_absent_package_archive_tampering_at_each_lifecycle_stage(self):
        self.archive()
        self.check_package_archive_tampering(present=False)

    def test_package_listing_command_tampering_at_each_lifecycle_stage(self):
        self.archive()
        for folder, summary in [(self.preflight, 'native-preflight.json'), (self.directory, 'native-device.json'),
                                (self.closed, 'native-lease-closed.json')]:
            saved_summary = (folder / summary).read_bytes()
            saved_journal = (folder / 'native-commands.jsonl').read_bytes()
            for mutation in ['nonzero', 'wrong-target', 'missing']:
                with self.subTest(stage=summary, mutation=mutation):
                    commands = [json.loads(line) for line in saved_journal.splitlines()]
                    command = next(c for c in commands if c['output'] == 'native-preflight-test-packages.txt')
                    if mutation == 'nonzero':
                        command['exit'] = 1
                    elif mutation == 'wrong-target':
                        command['argv'][-1] += '.other'
                    else:
                        commands.remove(command)
                    journal = ('\n'.join(json.dumps(c) for c in commands) + '\n').encode()
                    (folder / 'native-commands.jsonl').write_bytes(journal)
                    receipt = json.loads(saved_summary)
                    receipt['files']['native-commands.jsonl'] = native_archive.sha(journal)
                    (folder / summary).write_text(json.dumps(receipt))
                    with self.assertRaises(ValueError):
                        native_archive.inspect_archive(folder, summary)
                    (folder / summary).write_bytes(saved_summary)
                    (folder / 'native-commands.jsonl').write_bytes(saved_journal)

    def check_package_archive_tampering(self, present):
        for folder, summary in [(self.preflight, 'native-preflight.json'), (self.directory, 'native-device.json'),
                                (self.closed, 'native-lease-closed.json')]:
            mutations = [('native-preflight-packages.txt', b'SecurityException: denied\n'),
                         ('native-preflight-test-packages.txt', b'error: device offline\n')]
            if present:
                mutations += [('native-preflight-package.txt', b''),
                              ('native-preflight-test-package.txt', b'Failure: disappeared\n'),
                              ('native-preflight-packages.txt', b'package:com.supertokens.upgrade.other\n')]
            else:
                mutations += [('native-preflight-packages.txt', b'package:com.supertokens.upgrade\n'),
                              ('native-preflight-package.txt', b'')]
            for filename, payload in mutations:
                with self.subTest(stage=summary, filename=filename, payload=payload):
                    saved = {name: (folder / name).read_bytes() if (folder / name).exists() else None
                             for name in [summary, filename, 'native-commands.jsonl']}
                    receipt = json.loads(saved[summary])
                    (folder / filename).write_bytes(payload)
                    receipt['files'][filename] = native_archive.sha(payload)
                    commands = [json.loads(line) for line in saved['native-commands.jsonl'].splitlines()]
                    for command in commands:
                        if command['output'] == filename:
                            command['outputSha256'] = native_archive.sha(payload)
                    journal = ('\n'.join(json.dumps(c) for c in commands) + '\n').encode()
                    (folder / 'native-commands.jsonl').write_bytes(journal)
                    receipt['files']['native-commands.jsonl'] = native_archive.sha(journal)
                    (folder / summary).write_text(json.dumps(receipt))
                    with self.assertRaises((ValueError, RuntimeError)):
                        native_archive.inspect_archive(folder, summary)
                    for name, data in saved.items():
                        if data is None:
                            (folder / name).unlink()
                        else:
                            (folder / name).write_bytes(data)

    def test_raw_instrumentation_cannot_be_replaced_with_summary_pass(self):
        self.archive()
        name = 'matrix-native-seed-instrument.txt'
        data = (self.directory / name).read_bytes().replace(b'OK (1 test)', b'OK (0 tests)')
        self.rewrite_receipt(name, data)
        journal = [json.loads(line) for line in (self.directory / 'native-commands.jsonl').read_text().splitlines()]
        for command in journal:
            if command['output'] == name:
                command['outputSha256'] = native_archive.sha(data)
        self.rewrite_receipt('native-commands.jsonl', ('\n'.join(json.dumps(value) for value in journal) + '\n').encode())
        with self.assertRaisesRegex(ValueError, 'instrumentation did not pass'):
            native_archive.inspect_archive(self.directory)

    def test_hash_mismatch_and_symlink_rejected(self):
        self.archive()
        file = self.directory / 'api.txt'
        original = file.read_bytes()
        file.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            native_archive.inspect_archive(self.directory)
        outside = self.root / 'outside'
        outside.write_bytes(original)
        file.unlink()
        file.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            native_archive.inspect_archive(self.directory)

    def test_archive_path_escape_rejected(self):
        self.archive()
        receipt = json.loads((self.directory / 'native-device.json').read_text())
        receipt['files']['../outside'] = 'a' * 64
        (self.directory / 'native-device.json').write_text(json.dumps(receipt))
        with self.assertRaises(ValueError):
            native_archive.inspect_archive(self.directory)

    def test_pending_owner_cannot_be_hidden_by_cleanup_boolean(self):
        self.archive()
        name = 'matrix-native-cleanup-matrix-native-receipt.xml'
        data = (self.directory / name).read_bytes().replace(b'</map>', b'<string name="pendingRequestId">lost</string></map>')
        self.rewrite_receipt(name, data)
        journal = [json.loads(line) for line in (self.directory / 'native-commands.jsonl').read_text().splitlines()]
        for command in journal:
            if command['output'] == name:
                command['outputSha256'] = native_archive.sha(data)
        self.rewrite_receipt('native-commands.jsonl', ('\n'.join(json.dumps(value) for value in journal) + '\n').encode())
        with self.assertRaisesRegex(ValueError, 'Persisted native receipt'):
            native_archive.inspect_archive(self.directory)

    def test_envelope_drift_rejected(self):
        row, expected = self.envelope(self.archive())
        row['rawEvidenceSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Envelope differs'):
            native_archive.verify_native(row, expected, self.parent)

    def test_caller_boolean_cannot_supply_authority(self):
        row, expected = self.envelope(self.archive())
        row.update(wireVerified=True, deviceCleanup=True, workersDrained=True, ownerReceiptsResolved=True, pendingCreations=0)
        (Path(expected['owned']) / 'native-evidence' / row['executionNonce'] / 'complete.json').unlink()
        with self.assertRaises(OSError):
            native_archive.verify_native(row, expected, self.parent)

    def test_pinned_signer_change_rejected(self):
        row, expected = self.envelope(self.archive())
        (self.root / 'apksigner').write_text('#!/bin/sh\nexit 0\n')
        with self.assertRaisesRegex(RuntimeError, 'Pinned apksigner'):
            native_archive.verify_native(row, expected, self.parent)

    def test_cookie_signout_complete_archive(self):
        row, expected = self.envelope(self.archive(flow='SIGN_OUT'))
        self.assertTrue(native_archive.verify_native(row, expected, self.parent)['ownerReceiptsResolved'])

    def test_exposed_refresh_complete_archive(self):
        row, expected = self.envelope(self.archive(mode='EXPOSED_COOKIE'))
        self.assertTrue(native_archive.verify_native(row, expected, self.parent)['deviceCleanup'])

    def test_exposed_signout_complete_archive(self):
        row, expected = self.envelope(self.archive(mode='EXPOSED_COOKIE', flow='SIGN_OUT'))
        self.assertEqual(0, native_archive.verify_native(row, expected, self.parent)['pendingCreations'])

    def test_preflight_must_precede_actual_jvm_start(self):
        row, expected = self.envelope(self.archive())
        expected['jvmStartedMs'] = expected['startedMs'] - 1
        with self.assertRaisesRegex(ValueError, 'enclose JVM/native'):
            native_archive.verify_native(row, expected, self.parent)

    def test_sibling_backend_bytes_must_match_original_authority(self):
        row, expected = self.envelope(self.archive())
        file = self.parent / 'native-backend' / row['executionNonce'] / 'events.jsonl'
        file.write_bytes(file.read_bytes() + b'{}\n')
        with self.assertRaisesRegex(ValueError, 'Sibling backend archive'):
            native_archive.verify_native(row, expected, self.parent)

    def test_instrumentation_sdk_implementation_is_rejected(self):
        self.archive()
        apk = self.directory / 'sdk-test.apk'
        with zipfile.ZipFile(apk, 'a') as archive:
            archive.writestr('classes2.dex', fixture_dex('Lcom/supertokens/session/SuperTokens;'))
        self.rewrite_receipt('sdk-test.apk', apk.read_bytes())
        run = json.loads((self.directory / 'run.json').read_text())
        run['artifacts']['sdk-test']['sha256'] = native_archive.sha(apk.read_bytes())
        self.rewrite_receipt('run.json', json.dumps(run).encode())
        with self.assertRaisesRegex(ValueError, 'Instrumentation APK contains SDK'):
            native_archive.inspect_archive(self.directory)

    def test_installed_apk_observation_must_match_archive(self):
        self.archive()
        name = 'matrix-native-seed-evidence.json'
        value = json.loads((self.directory / name).read_text())
        value['installedApplicationSha256'] = '0' * 64
        data = json.dumps(value).encode()
        self.rewrite_receipt(name, data)
        journal = [json.loads(line) for line in (self.directory / 'native-commands.jsonl').read_text().splitlines()]
        for command in journal:
            if command['output'] == name:
                command['outputSha256'] = native_archive.sha(data)
        self.rewrite_receipt('native-commands.jsonl', ('\n'.join(json.dumps(value) for value in journal) + '\n').encode())
        with self.assertRaisesRegex(ValueError, 'Actual installed APK'):
            native_archive.inspect_archive(self.directory)


if __name__ == '__main__':
    unittest.main()
