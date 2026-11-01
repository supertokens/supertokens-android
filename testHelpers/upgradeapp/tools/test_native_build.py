"""Trusted producer-output boundary, with explicit synthetic compilation inputs."""
import json
from pathlib import Path
import unittest
import zipfile
from unittest import mock
from types import SimpleNamespace

import build_provenance

import native_build
import test_matrix_native


class CompilationControls(unittest.TestCase):
    setUp = test_matrix_native.RunnerControls.setUp
    prepare_matrix = test_matrix_native.RunnerControls.prepare_matrix

    def verify(self):
        return native_build.verify(self.native_build_pin, Path(__file__).resolve().parents[3], self.binding,
            {label: self.root / (label + '.apk') for label in native_build.LABELS},
            json.loads(Path(self.args[self.args.index('--candidate-build-provenance') + 1]).read_bytes()))

    def test_all_four_apks_are_bound_outside_their_assets(self):
        self.prepare_matrix()
        self.verify()
        for label in native_build.LABELS:
            with self.subTest(label=label):
                apk = self.root / (label + '.apk')
                original = apk.read_bytes()
                with zipfile.ZipFile(apk) as archive:
                    entries = {name: archive.read(name) for name in archive.namelist()}
                # Preserve class names and current assets, change implementation bytes and signer metadata.
                entries['classes.dex'] += b'different SDK implementation'
                entries['META-INF/CERT.RSA'] = b'resigned by the same test signer'
                with zipfile.ZipFile(apk, 'w') as archive:
                    for name, data in entries.items():
                        archive.writestr(name, data)
                with self.assertRaisesRegex(ValueError, 'trusted compilation output'):
                    self.verify()
                apk.write_bytes(original)

    def test_rehashed_device_evidence_cannot_replace_external_compilation_pin(self):
        self.prepare_matrix()
        path = Path(self.native_build_pin['path'])
        value = json.loads(path.read_bytes())
        value['artifacts']['candidate'] = '0' * 64
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'Trusted native build receipt changed'):
            self.verify()

    def test_build_log_mutation_rejects(self):
        self.prepare_matrix()
        (Path(self.native_build_pin['path']).parent / 'build.log').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'compilation log changed'):
            self.verify()

    def producer_fixture(self):
        root = self.root / 'checkout'
        files = ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
            'testHelpers/upgradeapp/app/build.gradle', 'testHelpers/upgradeapp/build.gradle',
            'testHelpers/upgradeapp/settings.gradle', 'testHelpers/upgradeapp/gradle.properties',
            'testHelpers/upgradeapp/tools/build_provenance.py', 'testHelpers/upgradeapp/tools/native_build.py',
            'testHelpers/upgradeapp/app/src/androidTest/java/Test.java', 'app/src/test/compatibility/legacy054.sha256']
        for name in files:
            file = root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text('synthetic compilation input')
        candidate = self.root / 'input.aar'
        candidate.write_bytes(b'candidate input')
        receipt_path = Path(self.args[self.args.index('--candidate-build-provenance') + 1])
        receipt = json.loads(receipt_path.read_bytes())
        receipt['aarSha256'] = build_provenance.digest(candidate.read_bytes())
        receipt_path.write_text(json.dumps(receipt))
        output = self.root / 'producer-output'
        output.mkdir()
        return root, candidate, receipt_path, output

    def test_existing_producer_records_actual_compilation_outputs(self):
        root, candidate, receipt_path, output = self.producer_fixture()
        def compile_double(argv, **kwargs):
            kwargs['stdout'].write(b'EXPLICIT COMPILER DOUBLE')
            for flavor in ['sdk054', 'candidate']:
                for test in [False, True]:
                    relative = ('androidTest/' if test else '') + flavor + '/debug/app-' + flavor + '-debug' + ('-androidTest' if test else '') + '.apk'
                    file = root / 'testHelpers/upgradeapp/app/build/outputs/apk' / relative
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_bytes(relative.encode())
            return SimpleNamespace(returncode=0)
        with mock.patch.object(build_provenance, 'source_state', return_value={'hostDouble': True}), mock.patch.object(
                build_provenance.subprocess, 'run', side_effect=compile_double) as compile_call:
            build_provenance.build_native(root, output, candidate, receipt_path)
        receipt = json.loads((output / 'native-build.json').read_bytes())
        self.assertEqual(compile_call.call_args.args[0], receipt['command'])
        self.assertEqual(set(native_build.LABELS), set(receipt['artifacts']))
        for label in native_build.LABELS:
            self.assertEqual(build_provenance.digest((output / (label + '.apk')).read_bytes()), receipt['artifacts'][label])
        pin = dict(path=str(output / 'native-build.json'), sha256=build_provenance.digest((output / 'native-build.json').read_bytes()))
        native_build.verify(pin, root, receipt, {label: output / (label + '.apk') for label in native_build.LABELS}, receipt['candidateBuild'])

    def test_changed_compilation_input_cannot_issue_receipt(self):
        root, candidate, receipt_path, output = self.producer_fixture()
        def changed(argv, **kwargs):
            candidate.write_bytes(b'changed during compile')
            return SimpleNamespace(returncode=0)
        with mock.patch.object(build_provenance, 'source_state', return_value={}), mock.patch.object(
                build_provenance.subprocess, 'run', side_effect=changed):
            with self.assertRaisesRegex(ValueError, 'inputs changed'):
                build_provenance.build_native(root, output, candidate, receipt_path)
        self.assertFalse((output / 'native-build.json').exists())


if __name__ == '__main__':
    unittest.main()
