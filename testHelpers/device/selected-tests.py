#!/usr/bin/env python3
"""Explicit backend-free selectors, using the restart runner's bounded supervision."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--api', required=True, type=int)
    parser.add_argument('--package', required=True)
    parser.add_argument('--test-package', required=True)
    parser.add_argument('--test-class', required=True)
    parser.add_argument('--expected-sdk-name', choices=['sdk054', 'candidate'])
    parser.add_argument('--method', required=True, action='append')
    parser.add_argument('--apk', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.test_class == 'com.supertokens.upgrade.OldRuntimeTest' and not args.expected_sdk_name:
        parser.error('OldRuntimeTest requires --expected-sdk-name')
    for value in [args.package, args.test_package, args.test_class] + args.method:
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]*', value):
            parser.error('Invalid selector/package: ' + value)
    if args.output.exists() or any(not p.is_file() for p in args.apk):
        parser.error('Output must be new and APKs must exist')
    spec = importlib.util.spec_from_file_location('restart', Path(__file__).with_name('restart-pairs.py'))
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    runner.CLASS = args.test_class
    cancellation = runner.Cancellation()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, cancellation.receive)
    args.output.mkdir(parents=True)
    completed = []
    artifacts = []

    def adb(arguments, label, timeout=30):
        log = args.output / (label + '.log')
        command = [os.environ.get('ADB', 'adb'), '-s', args.serial] + arguments
        code = runner.bounded_command(command, timeout, log, cancellation)
        if code:
            raise RuntimeError('adb failed: ' + str(log))
        return log.read_text(errors='replace')

    try:
        actual = adb(['shell', 'getprop', 'ro.build.version.sdk'], 'api').strip()
        if actual != str(args.api):
            raise RuntimeError('Expected API %s, got %s; no API substitution' % (args.api, actual))
        adb(['shell', 'getprop', 'ro.build.fingerprint'], 'fingerprint')
        for index, apk in enumerate(args.apk):
            data = apk.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            copy = args.output / ('%d-%s.apk' % (index, digest))
            copy.write_bytes(data)
            artifacts.append(dict(source=str(apk.resolve()), sha256=digest))
            if 'Success' not in adb(['install', '-r', str(copy)], 'install-%d' % index, 120).splitlines():
                raise RuntimeError('Install did not report Success')
        for method in args.method:
            adb(['shell', 'am', 'force-stop', args.package], method + '-stop')
            text = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                        args.test_class + '#' + method] +
                       (['-e', 'expectedSdkName', args.expected_sdk_name] if args.expected_sdk_name else []) + [
                        args.test_package + '/androidx.test.runner.AndroidJUnitRunner'], method, 120)
            if not runner.exactly_one_pass(text, method):
                raise RuntimeError('Expected exactly one unskipped passing method: ' + method)
            completed.append(method)
    finally:
        (args.output / 'result.json').write_text(json.dumps(dict(
            api=args.api, serial=args.serial, expectedSdkName=args.expected_sdk_name,
            artifacts=artifacts, completed=completed,
            expected=args.method, success=len(completed) == len(args.method)), indent=2) + '\n')


if __name__ == '__main__':
    main()
