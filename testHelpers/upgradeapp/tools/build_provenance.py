"""Source/build receipts for the explicit candidate AAR; no Gradle runs on import."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_state(root):
    def git(*args):
        return subprocess.check_output(['git', '-C', str(root)] + list(args), timeout=30)
    diff = git('diff', '--binary', 'HEAD', '--')
    untracked = []
    for name in sorted(filter(None, git('ls-files', '--others', '--exclude-standard', '-z').split(b'\0'))):
        path = root / name.decode('utf-8')
        data = str(path.readlink()).encode() if path.is_symlink() else path.read_bytes()
        untracked.append(dict(path=name.decode('utf-8'), sha256=digest(data)))
    return dict(gitRevision=git('rev-parse', 'HEAD').decode().strip(),
                dirty=bool(git('status', '--porcelain', '--untracked-files=all')),
                dirtyDiffSha256=digest(diff), untrackedSha256=digest(json.dumps(untracked, sort_keys=True).encode()))


def validate(receipt, aar_hash):
    if not isinstance(receipt, dict) or receipt.get('aarSha256') != aar_hash:
        raise ValueError('Candidate build receipt/hash mismatch')
    if receipt.get('schema') == 2:
        producer = json.loads(receipt['producerProvenanceJson'])
        manifest_text = receipt['sourceBuildManifestJson']
        source = receipt['source']
        artifact = producer['artifacts'][receipt['variant']]
        if (receipt.get('kind') != 'archived-build' or artifact['sha256'] != aar_hash
                or source['gitRevision'] != producer['gitHead'] or source['dirty'] != producer['uncommitted']
                or source['sourceBuildManifestSha256'] != digest(manifest_text.encode())
                or source['sourceBuildManifestSha256'] != producer['sourceBuildManifestSha256']
                or source['provenanceSha256'] != digest(receipt['producerProvenanceJson'].encode())
                or source['resultSha256'] != digest(receipt['producerResultMarkdown'].encode())):
            raise ValueError('Archived source/artifact evidence mismatch')
        manifest = json.loads(manifest_text)
        if not manifest or not all(re.fullmatch('[0-9a-f]{64}', value) for value in manifest.values()):
            raise ValueError('Invalid archived source manifest')
        return receipt
    if receipt.get('schema') != 1:
        raise ValueError('Unknown candidate build receipt schema')
    source = receipt.get('source', {})
    for key, length in [('gitRevision', 40), ('dirtyDiffSha256', 64), ('untrackedSha256', 64)]:
        if not re.fullmatch('[0-9a-f]{%d}' % length, str(source.get(key, ''))):
            raise ValueError('Missing candidate source provenance: ' + key)
    if type(source.get('dirty')) is not bool or receipt.get('command') != ['./gradlew', ':app:assembleDebug', '--rerun-tasks']:
        raise ValueError('Invalid candidate build provenance')
    if receipt.get('exitCode') != 0 or not receipt.get('buildLogSha256'):
        raise ValueError('Candidate build did not succeed')
    start = datetime.datetime.fromisoformat(receipt['startedAt'])
    end = datetime.datetime.fromisoformat(receipt['finishedAt'])
    if start.tzinfo is None or end.tzinfo is None or end < start:
        raise ValueError('Invalid build time range')
    return receipt


def main():
    parser = argparse.ArgumentParser(description='Run ONLY after the exclusive Gradle lane is released.')
    parser.add_argument('--output', required=True, type=Path, help='New evidence directory outside the repository')
    parser.add_argument('--native-apks', action='store_true', help='Compile four native APKs from an existing candidate build receipt')
    parser.add_argument('--candidate-aar', type=Path)
    parser.add_argument('--candidate-build-provenance', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    output = args.output.resolve()
    if output == root or root in output.parents or output.exists():
        parser.error('Output must be a new directory outside the repository')
    output.mkdir(parents=True, mode=0o700)
    if args.native_apks:
        build_native(root, output, args.candidate_aar, args.candidate_build_provenance)
        print(output)
        return
    before = source_state(root)
    command = ['./gradlew', ':app:assembleDebug', '--rerun-tasks']
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with (output / 'build.log').open('wb') as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
    if result.returncode:
        raise SystemExit('Candidate build failed; see ' + str(output / 'build.log'))
    if source_state(root) != before:
        raise SystemExit('Source changed during candidate build; no receipt issued')
    artifact = root / 'app/build/outputs/aar/app-debug.aar'
    data = artifact.read_bytes()
    pin = (root / 'app/src/test/compatibility/legacy054.sha256').read_text().strip()
    if digest(data) == pin:
        raise SystemExit('Candidate is the published baseline')
    (output / 'candidate.aar').write_bytes(data)
    receipt = dict(schema=1, source=before, command=command, exitCode=0, startedAt=started,
                   finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   aarSha256=digest(data), buildLogSha256=digest((output / 'build.log').read_bytes()))
    validate(receipt, digest(data))
    (output / 'candidate-build.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(output)


def build_native(root, output, candidate, receipt_path):
    import native_build
    if candidate is None or receipt_path is None:
        raise ValueError('Native compilation requires existing candidate AAR and producer receipt')
    candidate, receipt_path = candidate.resolve(), receipt_path.resolve()
    candidate_hash = digest(candidate.read_bytes())
    receipt_bytes = receipt_path.read_bytes()
    candidate_build = validate(json.loads(receipt_bytes), candidate_hash)
    before = source_state(root)
    sources = native_build.sources(root)
    argv = native_build.command(root, candidate, receipt_path, candidate_hash)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with (output / 'build.log').open('wb') as log:
        result = subprocess.run(argv, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
    if result.returncode or source_state(root) != before or native_build.sources(root) != sources or \
            digest(candidate.read_bytes()) != candidate_hash or receipt_path.read_bytes() != receipt_bytes:
        raise ValueError('Native compilation failed or inputs changed; no receipt issued')
    artifacts = {}
    for label in native_build.LABELS:
        flavor = 'sdk054' if label.startswith('sdk') else 'candidate'
        test = label.endswith('-test')
        relative = ('androidTest/' if test else '') + flavor + '/debug/app-' + flavor + '-debug' + ('-androidTest' if test else '') + '.apk'
        data = (root / 'testHelpers/upgradeapp/app/build/outputs/apk' / relative).read_bytes()
        (output / (label + '.apk')).write_bytes(data)
        artifacts[label] = digest(data)
    if source_state(root) != before or native_build.sources(root) != sources or \
            digest(candidate.read_bytes()) != candidate_hash or receipt_path.read_bytes() != receipt_bytes:
        raise ValueError('Native compilation inputs changed while archiving; no receipt issued')
    receipt = dict(schema=1, kind='native-apk-build', source=before, sources=sources, command=argv,
        candidateAar=str(candidate), candidateBuildPath=str(receipt_path), candidateBuild=candidate_build,
        candidateAarSha256=candidate_hash,
        publishedAarSha256=(root / 'app/src/test/compatibility/legacy054.sha256').read_text().strip(),
        startedAt=started, finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), exitCode=0,
        buildLogSha256=digest((output / 'build.log').read_bytes()), artifacts=artifacts)
    (output / 'native-build.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
