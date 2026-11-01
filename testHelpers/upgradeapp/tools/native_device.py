"""Exclusive device ownership and pinned-group command execution for matrix runs."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit


def require(value, message):
    if not value:
        raise RuntimeError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def signer_identity(executable):
    resolved = shutil.which(str(executable))
    require(resolved is not None, 'Cannot discover apksigner')
    script = Path(resolved).resolve()
    jar = script.parent / 'lib/apksigner.jar'
    require(script.is_file() and jar.is_file() and not jar.is_symlink(), 'apksigner must have its pinned sibling lib/apksigner.jar')
    return dict(path=str(script), sha256=sha(script), jarPath=str(jar), jarSha256=sha(jar))


def verify_signer(identity):
    require(set(identity) == {'path', 'sha256', 'jarPath', 'jarSha256'}, 'Invalid signer pin')
    require(signer_identity(identity['path']) == identity, 'Pinned apksigner implementation changed')


def group_snapshot(pid):
    result = subprocess.run(['ps', '-axo', 'pid=,pgid=,stat='], capture_output=True, text=True, timeout=5, check=True)
    rows = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] == str(pid):
            # Darwin ps/tasks.c emits '?' for unavailable Mach thread state. It is
            # unresolved/live here; only an observed Z can ever prove drain.
            pattern = r'[RUSITHZ?][<NXEVLs+]*' if sys.platform == 'darwin' else r'[RSDTtZXIWPK][<NLSsl+]*'
            require(len(fields) == 3 and fields[0].isdigit() and re.fullmatch(pattern, fields[2]),
                    'Unrecognized process state: ' + repr(line))
            rows.append(dict(pid=int(fields[0]), pgid=int(fields[1]), state=fields[2]))
    require(any(row['pid'] == pid for row in rows), 'Owned unreaped group leader disappeared')
    return rows


def run_command(argv, timeout, log, cancellation):
    """Keep the unreaped leader pinning its PGID until every group member is stopped.

    Popen.poll/wait would release that identity before descendant cleanup. ps observes
    zombie completion without reaping; only this function waits for the child.
    """
    cancellation.check()
    started = time.monotonic()
    reason = None
    before = after = None
    killed = False
    with Path(log).open('wb') as output:
        child = subprocess.Popen(argv, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            while True:
                before = group_snapshot(child.pid)
                leader = next(row for row in before if row['pid'] == child.pid)
                if leader['state'].startswith('Z'):
                    break
                if cancellation.number or time.monotonic() - started >= timeout:
                    reason = 'cancelled' if cancellation.number else 'timeout'
                    break
                time.sleep(0.025)
            if any(not row['state'].startswith('Z') for row in before):
                os.killpg(child.pid, signal.SIGKILL)
                killed = True
            deadline = time.monotonic() + 5
            while True:
                after = group_snapshot(child.pid)
                if all(row['state'].startswith('Z') for row in after):
                    break
                require(time.monotonic() < deadline, 'Owned command group did not drain')
                time.sleep(0.025)
            code = child.wait(timeout=5)
        except BaseException:
            # The unreaped leader still pins the owned group. Never signal after wait.
            try:
                try:
                    if not killed:
                        os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            finally:
                child.wait(timeout=5)
            raise
    return (124 if reason else code), dict(leader=child.pid, beforeKill=before, afterKill=after,
                                           actualExit=code, reason=reason)


def routes(text):
    result = []
    for line in text.splitlines():
        fields = line.split()
        if not fields:
            continue
        require(len(fields) == 3 and fields[1].startswith('tcp:') and fields[2].startswith('tcp:'), 'Unrecognized adb reverse mapping')
        result.append(fields)
    require(len({row[1] for row in result}) == len(result), 'Ambiguous reverse mappings')
    return result


def packages(text):
    names = []
    for line in text.splitlines():
        if not line:
            continue
        require(re.fullmatch(r'package:[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*', line),
                'Malformed package listing: ' + repr(line))
        names.append(line[len('package:'):])
    require(len(names) == len(set(names)), 'Duplicate package listing')
    return set(names)


def package_paths(text, instrumentation=False):
    lines = text.splitlines()
    require(lines and all(re.fullmatch(r'package:/[^\s]+\.apk', line) for line in lines) and
            len(lines) == len(set(lines)) and (not instrumentation or len(lines) == 1),
            'Invalid/empty installed package path')
    return [line[len('package:'):] for line in lines]


def session_archive(directory, filename):
    directory = Path(directory)
    require(directory.is_absolute() and directory.resolve() == directory and directory.stat().st_uid == os.getuid() and
            directory.stat().st_mode & 0o077 == 0, 'Shared device session archive must be canonical/private/owned')
    summary = directory / filename
    require(not summary.is_symlink() and summary.is_file(), 'Missing shared device session archive')
    value = json.loads(summary.read_bytes())
    require(set(value['files']) == {item.name for item in directory.iterdir()} - {filename}, 'Shared session archive entries changed')
    for name, digest in value['files'].items():
        require(re.fullmatch(r'[A-Za-z0-9_.-]+', name) and name not in ['.', '..'], 'Shared archive path escape')
        file = directory / name
        require(file.is_file() and not file.is_symlink() and file.stat().st_size <= 128 * 1024 * 1024 and sha(file) == digest,
                'Shared device archive changed: ' + name)
    return value


class DeviceLease:
    def __init__(self, root, serial, binding, output):
        self.root = Path(root)
        self.serial = serial
        self.binding = binding
        self.output = Path(output)
        self.path = self.root / hashlib.sha256(serial.encode()).hexdigest()
        self.owner = dict(schema=1, serial=serial, hostPid=os.getpid(), nativeNonce=binding['nativeNonce'],
                          runId=binding['runId'], instance=binding['instance'], preparationNonce=binding['preparationNonce'])
        self.bytes = (json.dumps(self.owner, sort_keys=True) + '\n').encode()
        self.acquired = False
        self.route = None
        self.original_routes = None
        self.app_owned = False
        self.shared = False
        self.borrower_bytes = None
        self.borrower_digest = None

    def acquire(self):
        require(self.root.is_absolute() and self.root.resolve() == self.root and self.root.is_dir(), 'Canonical pre-created device lease root required')
        require(self.root.stat().st_uid == os.getuid() and self.root.stat().st_mode & 0o077 == 0, 'Device lease root must be private and owned')
        self.path.mkdir(mode=0o700)
        self.acquired = True
        with (self.path / 'owner.json').open('xb') as stream:
            stream.write(self.bytes)
            stream.flush()
            os.fsync(stream.fileno())
        self.inode = self.path.stat().st_ino
        (self.output / 'device-lease-owner.json').write_bytes(self.bytes)
        (self.output / 'device-lease.jsonl').write_text(json.dumps(dict(action='acquired', root=str(self.root), path=str(self.path),
                                                                    inode=self.inode, timeMs=time.time_ns() // 1000000,
                                                                    ownerSha256=hashlib.sha256(self.bytes).hexdigest())) + '\n')

    def check(self):
        require(self.acquired and not self.path.is_symlink() and self.path.stat().st_ino == self.inode and
                not (self.path / 'owner.json').is_symlink() and (self.path / 'owner.json').read_bytes() == self.bytes,
                'Device lease owner changed; retain state for reconciliation')
        if self.borrower_bytes is not None:
            borrower = self.path / 'borrower.json'
            require(not borrower.is_symlink() and borrower.read_bytes() == self.borrower_bytes, 'Active device borrower changed')

    def borrow(self, session):
        session = Path(session)
        prepared = session_archive(session, 'native-preflight.json')
        require(prepared['schema'] == 1 and prepared['kind'] == 'native-device-preflight', 'Wrong shared preflight schema')
        source = prepared['binding']
        require(all(source[name] == self.binding[name] for name in ['context', 'runId', 'instance', 'preparationNonce',
                'mode', 'route', 'candidateAarSha256', 'publishedAarSha256', 'deviceBackend', 'toolchain', 'executionSourcesSha256',
                'qualifiedIdentitySha256', 'dependenciesSha256', 'nodeSdkSha256', 'coreSha256', 'coreManifestSha256', 'planSha256']),
                'Shared device preflight binding changed')
        require(self.binding.get('leaseNonce') == source['nativeNonce'], 'Wrong shared lease nonce')
        self.bytes = (session / 'device-lease-owner.json').read_bytes()
        self.owner = json.loads(self.bytes)
        require(self.owner['serial'] == self.serial and self.owner['nativeNonce'] == self.binding['leaseNonce'], 'Wrong shared device')
        records = [json.loads(line) for line in (session / 'device-lease.jsonl').read_text().splitlines()]
        require(len(records) == 1 and records[0]['action'] == 'acquired' and records[0]['root'] == str(self.root) and
                records[0]['path'] == str(self.path), 'Shared device lease was not retained by preflight')
        self.inode = records[0]['inode']
        self.acquired = True
        self.check()
        borrower = dict(schema=1, hostPid=os.getpid(), nativeNonce=self.binding['nativeNonce'],
                        preparationNonce=self.binding['preparationNonce'], ownerSha256=hashlib.sha256(self.bytes).hexdigest())
        encoded = (json.dumps(borrower, sort_keys=True) + '\n').encode()
        try:
            with (self.path / 'borrower.json').open('xb') as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
        except BaseException:
            self.acquired = False
            raise
        self.borrower_bytes = encoded
        self.borrower_digest = hashlib.sha256(encoded).hexdigest()
        (self.output / 'device-borrower.json').write_bytes(encoded)
        self.shared = True
        self.original_routes = routes((session / 'native-routing-before.txt').read_text())
        active = routes((session / 'native-routing-active.txt').read_text())
        additions = [row for row in active if row not in self.original_routes]
        require(len(additions) == 1, 'Ambiguous preflight routing')
        self.route = tuple(additions[0][1:])
        (self.output / 'device-lease-owner.json').write_bytes(self.bytes)
        (self.output / 'device-lease.jsonl').write_text(json.dumps(dict(action='borrowed', root=str(self.root), path=str(self.path),
            inode=self.inode, timeMs=time.time_ns() // 1000000, ownerSha256=hashlib.sha256(self.bytes).hexdigest(),
            borrowerSha256=self.borrower_digest,
            preflight=str(session), preflightSha256=sha(session / 'native-preflight.json'))) + '\n')

    def check_shared_route(self, adb):
        current = routes(adb(['reverse', '--list'], 'native-routing-active.txt'))
        local, remote = self.route
        require(len([row for row in current if row[1:] == [local, remote]]) == 1 and
                [row for row in current if row[1] != local] == self.original_routes, 'Shared owned routing changed')

    def configure_route(self, adb):
        self.check()
        device, host = urlsplit(self.binding['deviceBackend']), urlsplit(self.binding['context']['backend'])
        require(device.scheme == host.scheme == 'http' and device.hostname == host.hostname == '127.0.0.1' and
                device.port and host.port, 'Matrix device routing requires explicit loopback HTTP ports with owned adb reverse')
        self.original_routes = routes(adb(['reverse', '--list'], 'native-routing-before.txt'))
        local, remote = 'tcp:' + str(device.port), 'tcp:' + str(host.port)
        require(not any(row[1] == local for row in self.original_routes), 'Device reverse port already owned; do not replace it')
        self.route = (local, remote)
        adb(['reverse', '--no-rebind', local, remote], 'native-routing-create.txt')
        current = routes(adb(['reverse', '--list'], 'native-routing-active.txt'))
        require(len([row for row in current if row[1:] == [local, remote]]) == 1 and
                [row for row in current if row[1] != local] == self.original_routes, 'Owned routing was not established exactly')

    def claim_fixture_app(self, adb):
        self.check()
        self.app_owned = False
        installed = 'com.supertokens.upgrade' in packages(adb(
            ['shell', 'pm', 'list', 'packages', 'com.supertokens.upgrade'], 'native-preflight-packages.txt'))
        instrumentation = 'com.supertokens.upgrade.test' in packages(adb(
            ['shell', 'pm', 'list', 'packages', 'com.supertokens.upgrade.test'], 'native-preflight-test-packages.txt'))
        if installed:
            package_paths(adb(['shell', 'pm', 'path', 'com.supertokens.upgrade'], 'native-preflight-package.txt'))
        if instrumentation:
            require(installed, 'Orphan instrumentation package is not owned; do not replace or stop it')
            code_path = package_paths(adb(['shell', 'pm', 'path', 'com.supertokens.upgrade.test'],
                'native-preflight-test-package.txt'), instrumentation=True)[0]
            actual = adb(['shell', 'sha256sum', code_path], 'native-preflight-test-sha256.txt').strip().split()
            require(len(actual) == 2 and actual[1] == code_path and actual[0] in
                    [sha(self.output / (label + '.apk')) for label in ['sdk-test', 'candidate-test']],
                    'Installed instrumentation does not match owned APK inputs')
        if installed:
            files = adb(['exec-out', 'run-as', 'com.supertokens.upgrade', 'ls', 'shared_prefs'], 'native-preflight-prefs.txt').splitlines()
            markers = [name for name in ['matrix-native-receipt.xml', 'upgrade-receipt.xml'] if name in files]
            require(markers, 'Existing app has no recognized completed owner receipt; do not replace or stop it')
            for name in markers:
                text = adb(['exec-out', 'run-as', 'com.supertokens.upgrade', 'cat', 'shared_prefs/' + name], 'native-preflight-' + name)
                require('<!DOCTYPE' not in text and '<!ENTITY' not in text, 'Invalid owner receipt XML')
                tree = ET.fromstring(text)
                require(tree.tag == 'map' and len({item.attrib.get('name') for item in tree}) == len(list(tree)), 'Ambiguous prior owner receipt')
                values = {item.attrib['name']: item.text if item.tag == 'string' else item.attrib.get('value') for item in tree}
                require(values.get('cleanupDone') == 'true' and 'pendingRequestId' not in values and 'fixtureId' not in values and
                        json.loads(values.get('owners', '[]')) == [], 'Prior fixture owner unresolved; do not replace or stop it')
        self.app_owned = True

    def finish(self, adb, release=True, preserve_route=False, successful_row=False):
        self.check()
        for package in (['com.supertokens.upgrade', 'com.supertokens.upgrade.test'] if self.app_owned else []):
            suffix = 'app' if package == 'com.supertokens.upgrade' else 'test'
            adb(['shell', 'am', 'force-stop', package], 'native-final-stop-' + suffix + '.txt')
            # Android pidof exits 1 for absence; echo captures that status without masking it.
            text = adb(['shell', 'sh', '-c', "'pidof " + package + "; code=$?; echo NATIVE_PIDOF_EXIT:$code'"],
                       'native-final-process-' + suffix + '.txt')
            require(text.strip() == 'NATIVE_PIDOF_EXIT:1', 'Owned Android package still has running processes')
        if self.route:
            local, remote = self.route
            current = routes(adb(['reverse', '--list'], 'native-routing-final.txt'))
            require(len([row for row in current if row[1:] == [local, remote]]) == 1, 'Owned reverse mapping changed; do not delete another owner')
            if not preserve_route:
                adb(['reverse', '--remove', local], 'native-routing-remove.txt')
                after = routes(adb(['reverse', '--list'], 'native-routing-after.txt'))
                require(after == self.original_routes, 'Device routing did not return to original state')
                self.route = None
        self.check()
        if self.borrower_bytes is not None:
            borrower = self.path / 'borrower.json'
            require(not borrower.is_symlink() and borrower.read_bytes() == self.borrower_bytes, 'Shared device borrower changed; retain lease')
            borrower.unlink()
            self.borrower_bytes = None
        if not release:
            with (self.output / 'device-lease.jsonl').open('a') as journal:
                journal.write(json.dumps(dict(action='row-completed' if successful_row else 'retained-after-failure', inode=self.inode,
                                              timeMs=time.time_ns() // 1000000,
                                              borrowerSha256=self.borrower_digest,
                                              ownerSha256=hashlib.sha256(self.bytes).hexdigest())) + '\n')
            if successful_row:
                self.acquired = False
            return
        require(set(path.name for path in self.path.iterdir()) == {'owner.json'}, 'Unexpected lease files; retain lock')
        (self.path / 'owner.json').unlink()
        self.path.rmdir()
        self.acquired = False
        with (self.output / 'device-lease.jsonl').open('a') as journal:
            journal.write(json.dumps(dict(action='released', inode=self.inode, timeMs=time.time_ns() // 1000000,
                                          borrowerSha256=self.borrower_digest,
                                          ownerSha256=hashlib.sha256(self.bytes).hexdigest())) + '\n')
