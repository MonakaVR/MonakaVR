"""Verify/restore the exact pinned revision kit; no live source checkout required."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

R = Path(__file__).resolve().parents[1]
def require(ok, message):
    if not ok: raise RuntimeError(message)
def sha(data): return hashlib.sha256(data).hexdigest()

def main():
    pin = json.loads((R/'dependencies/monaka-protocol-v2.lock.json').read_text())
    archive = R/pin['artifact_path']
    metadata = R/pin['manifest_path']
    require(sha(archive.read_bytes()) == pin['sha256'], 'v2 ZIP SHA256 mismatch')
    require(sha(metadata.read_bytes()) == pin['manifest_sha256'], 'v2 manifest SHA256 mismatch')
    manifest = json.loads(metadata.read_text())
    require(manifest['sha256'] == pin['sha256'] and manifest['source_commit'] == pin['source_commit'], 'v2 source/artifact mismatch')
    require(manifest['wire_version'] == {'major': 2, 'minor': 0}, 'v2 version mismatch')
    destination = R/'third_party/monaka-protocol-v2'
    with zipfile.ZipFile(archive) as z:
        require(z.testzip() is None, 'v2 ZIP integrity')
        require(len(z.namelist()) == len(set(z.namelist())), 'duplicate ZIP path')
        require(sha(z.read('protocol.lock.json')) == manifest['protocol_lock_sha256'], 'v2 protocol lock mismatch')
        lock = json.loads(z.read('protocol.lock.json'))
        require(lock['source_commit'] == pin['source_commit'] and lock['source_tree_clean'], 'v2 clean source provenance')
        require(sha(z.read('docs/C2.md')) == manifest['contract_revision_sha256'], 'v2 contract mismatch')
        for line in z.read('SHA256SUMS').decode().splitlines():
            digest, name = line.split('  ', 1)
            require(sha(z.read(name)) == digest, 'v2 internal hash: '+name)
        for name, digest in lock['files_sha256'].items():
            require(sha(z.read(name)) == digest, 'v2 content manifest: '+name)
        for name in z.namelist():
            path = PurePosixPath(name)
            require(not path.is_absolute() and '..' not in path.parts and ':' not in name and '\\' not in name, 'unsafe ZIP path')
            target = destination.joinpath(*path.parts)
            data = z.read(name)
            if target.exists(): require(target.is_file() and target.read_bytes() == data, 'modified fixed kit: '+name)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
    print('PASS protocol v2 artifact/manifest/source/contract/content hashes')

if __name__ == '__main__': main()
