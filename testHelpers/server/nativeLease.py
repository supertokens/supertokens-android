"""Verify selector cleanup even when JVM success required no native scenario rows."""
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'upgradeapp/tools'))
import native_archive as archive
import native_device


def verify(expected):
    require = archive.require
    pre_dir, close_dir = Path(expected['nativePreflight']), Path(expected['nativeLeaseClose'])
    pre = archive.inspect_archive(pre_dir, 'native-preflight.json')
    close = archive.inspect_archive(close_dir, 'native-lease-closed.json')
    archive.verify_compilation(pre_dir, pre, expected)
    archive.verify_compilation(close_dir, close, expected)
    source = pre['input']
    require(source == close['input'], 'Lease close input changed')
    binding = expected['binding']
    for field in ['context', 'mode', 'route', 'runId', 'instance', 'candidateAarSha256', 'publishedAarSha256',
                  'qualifiedIdentitySha256', 'dependenciesSha256', 'nodeSdkSha256', 'coreSha256',
                  'coreManifestSha256', 'planSha256', 'executionSourcesSha256']:
        require(source[field] == binding[field], 'Lease binding changed: ' + field)
    require(source['preparationNonce'] == expected['preparationNonce'] and
            source['toolchain']['apksigner'] == expected['nativeTools']['apksigner'], 'Lease executor changed')
    require(expected['startedMs'] <= pre['receipt']['startedMs'] <= pre['receipt']['endedMs'] <= expected['jvmStartedMs'] <=
            expected['jvmEndedMs'] <= close['receipt']['startedMs'] <= close['receipt']['endedMs'] <= expected['endedMs'],
            'Lease does not enclose JVM execution')
    for label in ['sdk', 'sdk-test', 'candidate', 'candidate-test']:
        require(pre['receipt']['files'][label + '.apk'] == close['receipt']['files'][label + '.apk'], 'Lease artifacts changed')
    owner_bytes = archive.read(pre_dir, 'device-lease-owner.json')
    require(owner_bytes == archive.read(close_dir, 'device-lease-owner.json'), 'Lease owner changed')
    owner = json.loads(owner_bytes)
    serial, root = expected['nativeSerial'], Path(expected['nativeLeaseRoot'])
    require(owner['schema'] == 1 and owner['serial'] == serial and type(owner['hostPid']) is int and owner['hostPid'] > 1 and
            owner['nativeNonce'] == source['nativeNonce'] and all(owner[k] == source[k] for k in
                ['runId', 'instance', 'preparationNonce']), 'Wrong lease owner')
    opened = [json.loads(line) for line in archive.read(pre_dir, 'device-lease.jsonl').splitlines()]
    closed = [json.loads(line) for line in archive.read(close_dir, 'device-lease.jsonl').splitlines()]
    require(len(opened) == 1 and len(closed) == 2 and opened[0]['action'] == 'acquired' and
            [row['action'] for row in closed] == ['borrowed', 'released'], 'Lease not acquired/closed exactly')
    require(closed[0]['preflight'] == str(pre_dir) and closed[0]['preflightSha256'] == pre['rawEvidenceSha256'], 'Wrong borrowed preflight')
    borrower_bytes = archive.read(close_dir, 'device-borrower.json')
    borrower = json.loads(borrower_bytes)
    require(borrower['schema'] == 1 and type(borrower['hostPid']) is int and borrower['hostPid'] > 1 and
            borrower['nativeNonce'] == source['nativeNonce'] and borrower['preparationNonce'] == source['preparationNonce'] and
            borrower['ownerSha256'] == archive.sha(owner_bytes) and
            all(row['borrowerSha256'] == archive.sha(borrower_bytes) for row in closed), 'Close borrower mismatch')
    require(all(row['inode'] == opened[0]['inode'] and row['ownerSha256'] == archive.sha(owner_bytes) for row in opened + closed),
            'Lease replaced during selection')
    lease_path = root / archive.sha(serial.encode())
    require(root.resolve() == root and opened[0]['root'] == str(root) and opened[0]['path'] == str(lease_path) and
            not lease_path.exists() and not lease_path.is_symlink(), 'Lease still held or wrong root')
    require(pre['receipt']['startedMs'] <= opened[0]['timeMs'] <= closed[-1]['timeMs'] <= close['receipt']['endedMs'], 'Wrong lease interval')

    def command(data, name, argv):
        matches = [row for row in data['commands'] if row['output'] == name]
        require(len(matches) == 1, 'Missing/duplicate cleanup command: ' + name)
        row = matches[0]
        require(row['argv'] == [expected['nativeTools']['adb']['path'], '-s', serial] + argv and
                opened[0]['timeMs'] <= row['startedMs'] <= row['endedMs'] <= closed[-1]['timeMs'], 'Unowned cleanup command')

    device, host = urlsplit(source['deviceBackend']), urlsplit(source['context']['backend'])
    require(device.scheme == host.scheme == 'http' and device.hostname == host.hostname == '127.0.0.1' and
            device.port and host.port, 'Unowned device routing')
    local, remote = 'tcp:' + str(device.port), 'tcp:' + str(host.port)
    routes = {}
    for data, folder, names in [(pre, pre_dir, ['before', 'active']), (close, close_dir, ['active', 'final', 'after'])]:
        require(data['run']['serial'] == serial, 'Lease device changed')
        command(data, 'native-process-probe.txt', ['shell', 'sh', '-c',
                "'command -v pidof >/dev/null; echo NATIVE_PIDOF_AVAILABLE:$?'"])
        require(archive.read(folder, 'native-process-probe.txt').decode().strip() == 'NATIVE_PIDOF_AVAILABLE:0',
                'Process absence probe unavailable')
        for name in names:
            filename = 'native-routing-' + name + '.txt'
            command(data, filename, ['reverse', '--list'])
            routes[(str(folder), name)] = native_device.routes(archive.read(folder, filename).decode())
    before, active = routes[(str(pre_dir), 'before')], routes[(str(pre_dir), 'active')]
    require(not any(row[1] == local for row in before) and len([row for row in active if row[1:] == [local, remote]]) == 1 and
            [row for row in active if row[1] != local] == before and routes[(str(close_dir), 'active')] == active and
            routes[(str(close_dir), 'final')] == active and routes[(str(close_dir), 'after')] == before, 'Routing not restored')
    command(pre, 'native-routing-create.txt', ['reverse', '--no-rebind', local, remote])
    command(close, 'native-routing-remove.txt', ['reverse', '--remove', local])
    for suffix, package in [('app', 'com.supertokens.upgrade'), ('test', 'com.supertokens.upgrade.test')]:
        command(close, 'native-final-stop-' + suffix + '.txt', ['shell', 'am', 'force-stop', package])
        name = 'native-final-process-' + suffix + '.txt'
        command(close, name, ['shell', 'sh', '-c', "'pidof " + package + "; code=$?; echo NATIVE_PIDOF_EXIT:$code'"])
        require(archive.read(close_dir, name).decode().strip() == 'NATIVE_PIDOF_EXIT:1', 'Device writers survived close')
    for data, folder, summary in [(pre, pre_dir, 'native-preflight.json'), (close, close_dir, 'native-lease-closed.json')]:
        archive.verify_compilation(folder, data, expected)
        require(archive.sha(archive.read(folder, summary)) == data['rawEvidenceSha256'] and
                all(archive.sha(archive.read(folder, name)) == digest for name, digest in data['receipt']['files'].items()),
                'Lease archive changed during verification')


if __name__ == '__main__':
    verify(json.load(sys.stdin))
