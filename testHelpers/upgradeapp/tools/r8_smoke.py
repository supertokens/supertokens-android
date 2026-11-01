#!/usr/bin/env python3
"""Run one bounded, data-preserving R8 stage on an explicitly selected emulator."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import zipfile
import xml.etree.ElementTree as ET
from upgrade import shared, sha256, PACKAGE, TEST_PACKAGE
from build_provenance import validate


def dex_classes(data):
    string_count, strings = struct.unpack_from('<II', data, 56)
    type_count, types = struct.unpack_from('<II', data, 64)
    class_count, classes = struct.unpack_from('<II', data, 96)
    names = []
    for index in range(string_count):
        offset = struct.unpack_from('<I', data, strings + index * 4)[0]
        while data[offset] & 128:
            offset += 1
        offset += 1
        names.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    identifiers = [names[struct.unpack_from('<I', data, types + index * 4)[0]] for index in range(type_count)]
    return [identifiers[struct.unpack_from('<I', data, classes + index * 32)[0]] for index in range(class_count)]


def require_not_debuggable(manifest):
    values = re.findall(r'android:debuggable[^\n]*', manifest)
    if not values:
        return  # Android defaults to false when the attribute is absent.
    if len(values) != 1 or not re.search(r'=(?:\(type 0x12\))?(?:0x0|"false")\s*$', values[0]):
        raise RuntimeError('Mapping-stage app is debuggable or has an unrecognized debuggable value')


def mapped_store(mapping, definitions):
    stores = re.findall(r'^com\.supertokens\.session\.SuperTokensPersistentCookieStore -> ([^:]+):$', mapping, re.M)
    if len(stores) != 1 or not re.fullmatch(r'r8[ab]\.[\w.$]+', stores[0]):
        raise RuntimeError('Expected obfuscated cookie-store name in mapping')
    if ('L' + stores[0].replace('.', '/') + ';') not in definitions:
        raise RuntimeError('Mapped cookie store absent from app DEX')
    return stores[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['serial', 'backend', 'run-id']:
        parser.add_argument('--' + name, required=True)
    for name in ['app', 'test', 'receipt', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--mapping', type=Path)
    parser.add_argument('--mapping-a-evidence', type=Path,
                        help='Completed mapping-a output directory; required for mapping-b')
    parser.add_argument('--stage', choices=['seed', 'mapping-a', 'mapping-b', 'cleanup'], required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9-]{1,80}', args.run_id):
        parser.error('Invalid run ID')
    if args.output.exists():
        parser.error('Output directory must be new')
    if args.stage.startswith('mapping-') and not args.mapping:
        parser.error('Minified stage requires its mapping file')
    if args.stage == 'mapping-b' and not args.mapping_a_evidence:
        parser.error('mapping-b requires --mapping-a-evidence')
    os.umask(0o077)
    args.output.mkdir(parents=True)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    build_tools = Path(os.environ.get('ANDROID_BUILD_TOOLS', str(sdk / 'build-tools/30.0.3')))
    cancel = shared.Cancellation()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, cancel.receive)

    def command(argv, name, timeout=30):
        log = args.output / name
        code = shared.bounded_command([str(x) for x in argv], timeout, log, cancel)
        if code:
            raise RuntimeError('Command failed; see ' + str(log))
        return log.read_text(errors='replace')

    def adb(argv, name, timeout=30):
        return command([sdk / 'platform-tools/adb', '-s', args.serial] + argv, name, timeout)

    receipt = json.loads(args.receipt.read_text())
    validate(receipt, receipt['aarSha256'])
    certificates = []
    artifacts = {}
    app_definitions = []
    for label, source, package in [('app', args.app, PACKAGE), ('test', args.test, TEST_PACKAGE)]:
        target = args.output / (label + '.apk')
        shutil.copyfile(source, target)
        metadata = command([build_tools / 'aapt', 'dump', 'badging', target], label + '-badging.txt')
        check = re.search(r"^package: name='([^']+)' versionCode='([^']*)'", metadata, re.M)
        if not check or check.group(1) != package or (label == 'app' and check.group(2) != '1'):
            raise RuntimeError('Wrong package/version')
        if label == 'app' and "sdkVersion:'17'" not in metadata:
            raise RuntimeError('Expected minSdk 17')
        manifest = command([build_tools / 'aapt', 'dump', 'xmltree', target, 'AndroidManifest.xml'], label + '-manifest.txt')
        if label == 'app' and args.stage.startswith('mapping-'):
            require_not_debuggable(manifest)
        if label == 'test' and (not re.search(r'android:targetPackage[^\n]*="' + re.escape(PACKAGE) + '"', manifest)
                                or 'androidx.test.runner.AndroidJUnitRunner' not in manifest):
            raise RuntimeError('Wrong instrumentation target')
        if 'android:testOnly' in manifest:
            raise RuntimeError('Unexpected testOnly APK; inspect manifest')
        signed = command([build_tools / 'apksigner', 'verify', '--print-certs', target], label + '-signer.txt')
        certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signed, re.M)
        if len(certs) != 1:
            raise RuntimeError('Expected one signer')
        certificates.append(certs[0])
        with zipfile.ZipFile(target) as apk:
            definitions = sorted(name for dex in apk.namelist() if re.fullmatch(r'classes\d*\.dex', dex)
                                 for name in dex_classes(apk.read(dex)))
            (args.output / (label + '-dex-classes.json')).write_text(json.dumps(definitions, indent=2))
            if label == 'app':
                app_definitions = definitions
                embedded = json.loads(apk.read('assets/sdk-provenance.json'))
                if embedded['aarSha256'] != receipt['aarSha256'] or embedded.get('candidateBuild') != receipt:
                    raise RuntimeError('Candidate provenance mismatch')
            elif any(name.startswith(('Lcom/supertokens/session/', 'Lr8a/', 'Lr8b/')) for name in definitions):
                raise RuntimeError('Test APK contains SDK implementation')
        artifacts[label] = dict(sha256=sha256(target), path=str(target))
    if certificates[0] != certificates[1]:
        raise RuntimeError('App/test signers differ')
    store_name = None
    previous_evidence = None
    if args.mapping:
        shutil.copyfile(args.mapping, args.output / 'mapping.txt')
        mapping = args.mapping.read_text()
        if not re.search(r'^com\.supertokens\.session\.\w+ -> r8[ab]\.', mapping, re.M):
            raise RuntimeError('No obfuscated SDK class in mapping')
        store_name = mapped_store(mapping, app_definitions)
    if args.stage == 'mapping-b':
        previous = args.mapping_a_evidence
        prior = json.loads((previous / 'run.json').read_text())
        if ((previous / 'PASS').read_text().strip() != 'mapping-a' or prior.get('stage') != 'mapping-a'
                or prior.get('runId') != args.run_id or prior.get('serial') != args.serial
                or prior.get('backend') != args.backend or prior.get('candidateBuild') != receipt
                or prior.get('certificate') != certificates[0]):
            raise RuntimeError('Mapping A evidence must be a completed stage from this run/device/backend/candidate/signer')
        prior_mapping = previous / 'mapping.txt'
        prior_app = previous / 'app.apk'
        if (sha256(prior_mapping) != prior.get('mappingSha256')
                or sha256(prior_app) != prior['artifacts']['app']['sha256']):
            raise RuntimeError('Mapping A evidence artifact hash mismatch')
        prior_manifest = command([build_tools / 'aapt', 'dump', 'xmltree', prior_app, 'AndroidManifest.xml'],
                                 'mapping-a-manifest.txt')
        require_not_debuggable(prior_manifest)
        with zipfile.ZipFile(prior_app) as apk:
            prior_definitions = [name for dex in apk.namelist() if re.fullmatch(r'classes\d*\.dex', dex)
                                 for name in dex_classes(apk.read(dex))]
        prior_store = mapped_store(prior_mapping.read_text(), prior_definitions)
        if prior_store == store_name:
            raise RuntimeError('Mapping B cookie-store name must change from mapping A')
        previous_evidence = dict(path=str(previous.resolve()), runSha256=sha256(previous / 'run.json'),
                                 mappingSha256=sha256(prior_mapping), cookieStore=prior_store)
    (args.output / 'run.json').write_text(json.dumps(dict(stage=args.stage, runId=args.run_id,
        serial=args.serial, backend=args.backend, artifacts=artifacts, certificate=certificates[0],
        candidateBuild=receipt, mappingSha256=sha256(args.mapping) if args.mapping else None,
        cookieStore=store_name, mappingAEvidence=previous_evidence), indent=2))
    adb(['shell', 'getprop', 'ro.build.fingerprint'], 'fingerprint.txt')
    adb(['shell', 'getprop', 'ro.build.version.sdk'], 'api.txt')
    adb(['shell', 'am', 'force-stop', PACKAGE], 'stop.txt')
    for label in ['app', 'test']:
        result = adb(['install', '-r', artifacts[label]['path']], label + '-install.txt', 120)
        if 'Success' not in result.splitlines():
            raise RuntimeError('Install failed')
    shared.CLASS = PACKAGE + '.R8SmokeTest'
    text = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', shared.CLASS + '#runStage',
                '-e', 'stage', args.stage, '-e', 'runId', args.run_id, '-e', 'backend', args.backend,
                TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'], 'instrument.txt', 120)
    # API34 emulator root reads only this fixture's files; release is deliberately not debuggable.
    for name in ['r8-receipt', 'supertokens-cookie-shared-prefs', 'supertokens-android-shared-preferences']:
        adb(['exec-out', 'su', '0', 'cat', '/data/data/' + PACKAGE + '/shared_prefs/' + name + '.xml'], name + '.xml')
        ET.parse(args.output / (name + '.xml'))
    adb(['logcat', '-d', '-t', '500'], 'logcat.txt')
    if not shared.exactly_one_pass(text, 'runStage'):
        raise RuntimeError('Stage failed; state preserved. Inspect instrument.txt and r8-receipt.xml before scoped cleanup')
    (args.output / 'PASS').write_text(args.stage + '\n')
    print(args.stage + ' PASS: ' + str(args.output))


if __name__ == '__main__':
    main()
