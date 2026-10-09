"""Offline, read-only verification of current 2.1 or historical 2.0 receipts."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

R = Path(__file__).resolve().parents[1]
def require(ok, message):
    if not ok: raise RuntimeError(message)
def sha(data): return hashlib.sha256(data).hexdigest()

def verify(pin_path='dependencies/monaka-protocol-v2.1.lock.json', check_tree=True):
    pin = json.loads((R/pin_path).read_bytes())
    archive, metadata = R/pin['artifact_path'], R/pin['manifest_path']
    require(sha(archive.read_bytes()) == pin['sha256'], 'ZIP SHA256 mismatch')
    require(sha(metadata.read_bytes()) == pin['manifest_sha256'], 'manifest SHA256 mismatch')
    manifest = json.loads(metadata.read_bytes())
    for key in ('sha256','source_commit','wire_version','protocol_lock_sha256','contract_revision_sha256'):
        require(manifest[key] == pin[key], 'pin/manifest mismatch: '+key)
    destination = R/'third_party/monaka-protocol-v2'
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        require(z.testzip() is None, 'ZIP CRC mismatch')
        require(len(names) == len(set(names)) == len({n.casefold() for n in names}), 'duplicate ZIP path')
        for name in names:
            p = PurePosixPath(name)
            require(not p.is_absolute() and '..' not in p.parts and ':' not in name and '\\' not in name, 'unsafe ZIP path')
        require(sha(z.read('protocol.lock.json')) == pin['protocol_lock_sha256'], 'protocol lock mismatch')
        lock = json.loads(z.read('protocol.lock.json'))
        require(lock['source_commit'] == pin['source_commit'] and lock['source_tree_clean'], 'clean source provenance')
        require(lock['wire_version'] == pin['wire_version'], 'lock version mismatch')
        for doc,key in [('C2.md','contract_c2_sha256'),('C2.1.md','contract_c21_sha256')] if pin['wire_version']['minor'] == 1 else [('C2.md','contract_revision_sha256')]:
            require(sha(z.read('docs/'+doc)) == pin[key], 'contract mismatch: '+doc)
        sums = {}
        for line in z.read('SHA256SUMS').decode().splitlines():
            digest,name = line.split('  ',1)
            require(name not in sums and sha(z.read(name)) == digest, 'internal SHA256SUMS: '+name)
            sums[name] = digest
        require(set(sums) == set(names)-{'SHA256SUMS'}, 'incomplete SHA256SUMS')
        for name,digest in lock['files_sha256'].items():
            require(sha(z.read(name)) == digest, 'lock content hash: '+name)
        if check_tree:
            actual = {p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()}
            require(actual == set(names), 'missing/stale fixed kit members')
            for name in names:
                target = destination/name
                require(not target.is_symlink() and target.read_bytes() == z.read(name), 'modified kit member: '+name)
    print('PASS exact protocol', pin['wire_version'], 'artifact/manifest/lock/contracts/content', 'tree' if check_tree else 'historical receipt')

if __name__ == '__main__': verify()
