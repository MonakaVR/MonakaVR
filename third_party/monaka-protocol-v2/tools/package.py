"""Create a deterministic, self-contained kit from a clean, committed source tree."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--wire-major',type=int,choices=[1,2],default=1)
major=parser.parse_args().wire_major
R=Path(__file__).resolve().parents[1]
def git(*args): return subprocess.check_output(['git',*args],cwd=R,text=True).strip()
def sha(data): return hashlib.sha256(data).hexdigest()
def require(ok,message):
    if not ok: raise RuntimeError(message)
require(not git('status','--porcelain'), 'Commit source changes before packaging; never fake source_tree_clean')
head=git('rev-parse','HEAD')
schema_commit=git('log','-1','--format=%H','--','schema')
files={}
for name in git('ls-files').splitlines():
    if name.startswith(('cpp/','jvm/','schema/','fixtures/','docs/','licenses/','tools/')) or name in ['README.md','NOTICE','dependencies.lock.json']:
        files[name]=(R/name).read_bytes()
jar=R/'jvm/build/libs/monaka-protocol-jvm-0.1.0.jar'
require(jar.is_file(), 'Build JVM JAR before packaging')
files['jvm/libs/'+jar.name]=jar.read_bytes()
if major==2:
    report=json.loads((R/'build/test-v2-results.json').read_text())
    require(report['status']=='PASS','v2 validation did not pass')
    for group in ['source_sha256','binary_sha256']:
        for n,h in report[group].items(): require(sha((R/n).read_bytes())==h,'stale v2 validation: '+n)
    files['validation/protocol-v2-results.json']=(R/'build/test-v2-results.json').read_bytes()
hashes={n:sha(b) for n,b in sorted(files.items())}
lock={
    'schema_commit':schema_commit,'source_commit':head,'source_tree_clean':True,
    'contract_status':'candidate / master reconciliation pending',
    'wire_version':{'major':major,'minor':0},'contract_c1_sha256':hashes['docs/C1.md'],
    'contract_revision_sha256':hashes['docs/C2.md'] if major==2 else hashes['docs/C1.md'],
    'toolchain_and_dependencies':json.loads((R/'dependencies.lock.json').read_text()),
    'artifact_sha256':{n:h for n,h in hashes.items() if n.endswith('.jar')},
    'schema_sha256':{n:h for n,h in hashes.items() if n.startswith('schema/')},
    'fixture_sha256':{n:h for n,h in hashes.items() if n.startswith('fixtures/')},
    'api_package_sha256':{n:h for n,h in hashes.items() if n.startswith(('cpp/','jvm/src/'))},
    'files_sha256':hashes,
}
files['protocol.lock.json']=(json.dumps(lock,indent=2,ensure_ascii=False)+'\n').encode()
sums={n:sha(b) for n,b in sorted(files.items())}
files['SHA256SUMS']=''.join(f'{h}  {n}\n' for n,h in sums.items()).encode()
dist=R/'dist' if major==1 else R/'dist/v2';dist.mkdir(exist_ok=True,parents=True)
path=dist/f'monaka-protocol-kit-v{major}.0.zip'
with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for n,b in sorted(files.items()):
        info=zipfile.ZipInfo(n,date_time=(2026,9,14,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=0o644<<16; z.writestr(info,b)
manifest={
    'artifact':path.name,'sha256':sha(path.read_bytes()),'source_commit':head,
    'schema_commit':schema_commit,'contract_c1_sha256':lock['contract_c1_sha256'],
    'wire_version':lock['wire_version'],'contract_revision_sha256':lock['contract_revision_sha256'],
    'protocol_lock_sha256':sha(files['protocol.lock.json']),
    'status':'candidate / master reconciliation pending',
}
(dist/'handoff-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(dist/'protocol.lock.json').write_bytes(files['protocol.lock.json'])
print(json.dumps(manifest,indent=2))
