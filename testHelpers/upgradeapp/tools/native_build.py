"""External APK compilation evidence produced by build_provenance, never APK assets."""
import datetime
import json
from pathlib import Path

from build_provenance import digest, validate
import matrix_native


LABELS = ['sdk', 'sdk-test', 'candidate', 'candidate-test']
TASKS = [':app:assembleSdk054Debug', ':app:assembleSdk054DebugAndroidTest',
         ':app:assembleCandidateDebug', ':app:assembleCandidateDebugAndroidTest']


def sources(root):
    values = matrix_native.compiled_sources(root)
    for path in sorted((root / 'testHelpers/upgradeapp/app/src').rglob('*')):
        if path.is_file():
            values[str(path.relative_to(root))] = digest(path.read_bytes())
    for name in ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
                 'testHelpers/upgradeapp/tools/build_provenance.py', 'testHelpers/upgradeapp/tools/native_build.py']:
        values[name] = digest((root / name).read_bytes())
    return values


def command(root, candidate, receipt, candidate_hash):
    return [str(root / 'gradlew'), '-p', str(root / 'testHelpers/upgradeapp'), 'clean'] + TASKS + [
        '--rerun-tasks', '--no-build-cache', '-PcandidateAar=' + str(candidate),
        '-PcandidateAarSha256=' + candidate_hash, '-PcandidateBuildProvenance=' + str(receipt)]


def verify(pin, root, binding, artifacts, candidate_build, before_ms=None):
    """pin comes from executor configuration, independently of any device archive."""
    path = Path(pin['path'])
    matrix_native.require(path.is_absolute() and path.resolve() == path and path.is_file(), 'Canonical native build receipt required')
    raw = path.read_bytes()
    matrix_native.require(digest(raw) == pin['sha256'], 'Trusted native build receipt changed')
    value = json.loads(raw)
    matrix_native.require(value['schema'] == 1 and value['kind'] == 'native-apk-build' and value['exitCode'] == 0,
                          'Native APK compilation did not succeed')
    matrix_native.require(value['sources'] == sources(root), 'Native APK compilation sources changed')
    matrix_native.require(value['candidateAarSha256'] == binding['candidateAarSha256'] and
                          value['publishedAarSha256'] == binding['publishedAarSha256'] and
                          value['candidateBuild'] == candidate_build, 'Native compilation AAR inputs changed')
    validate(candidate_build, binding['candidateAarSha256'])
    matrix_native.require(value['command'] == command(root, Path(value['candidateAar']), Path(value['candidateBuildPath']),
                                                     binding['candidateAarSha256']), 'Unexpected APK compilation command')
    start, end = [datetime.datetime.fromisoformat(value[key]) for key in ['startedAt', 'finishedAt']]
    matrix_native.require(start.tzinfo is not None and end.tzinfo is not None and start <= end and
                          (before_ms is None or end.timestamp() * 1000 <= before_ms), 'APK compilation interval invalid')
    log = path.parent / 'build.log'
    matrix_native.require(not log.is_symlink() and digest(log.read_bytes()) == value['buildLogSha256'], 'APK compilation log changed')
    matrix_native.require(set(value['artifacts']) == set(LABELS) and set(artifacts) == set(LABELS), 'Missing compiled APK outputs')
    for label in LABELS:
        output = path.parent / (label + '.apk')
        matrix_native.require(not output.is_symlink() and digest(output.read_bytes()) == value['artifacts'][label] ==
                              digest(Path(artifacts[label]).read_bytes()), 'APK differs from trusted compilation output: ' + label)
    return value
