"""Import existing producer evidence without building or claiming current-tree freshness."""
import argparse
import json
from pathlib import Path
import zipfile
from build_provenance import digest, validate


def archive_receipt(archive, variant):
    provenance_text = (archive / 'provenance.json').read_text()
    producer = json.loads(provenance_text)
    artifact = producer['artifacts'][variant]
    aar = Path(artifact['archivedPath'])
    if aar.parent.resolve() != archive.resolve():
        raise ValueError('Artifact must be in the supplied archive')
    data = aar.read_bytes()
    if digest(data) != artifact['sha256'] or len(data) != artifact['bytes']:
        raise ValueError('Archived AAR does not match producer evidence')
    with zipfile.ZipFile(aar) as contents:
        if digest(contents.read('classes.jar')) != artifact['classesJarSha256']:
            raise ValueError('Archived classes do not match producer evidence')
    manifest = (archive / 'source-build-manifest.json').read_text()
    result = (archive / 'RESULT.md').read_text()
    receipt = dict(schema=2, kind='archived-build', variant=variant, aarSha256=artifact['sha256'],
                   archiveDirectory=str(archive.resolve()), producerProvenanceJson=provenance_text,
                   sourceBuildManifestJson=manifest, producerResultMarkdown=result,
                   source=dict(gitRevision=producer['gitHead'], dirty=producer['uncommitted'],
                               sourceBuildManifestSha256=digest(manifest.encode()),
                               provenanceSha256=digest(provenance_text.encode()), resultSha256=digest(result.encode())))
    return validate(receipt, artifact['sha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--variant', choices=['debug', 'release'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    receipt = archive_receipt(args.archive, args.variant)
    with args.output.open('x') as output:
        output.write(json.dumps(receipt, indent=2) + '\n')
    print('Verified archived ' + args.variant + ' AAR: ' + receipt['aarSha256'])
    print('Producer source revision: ' + receipt['source']['gitRevision'] + '; not a current-tree build')


if __name__ == '__main__':
    main()
