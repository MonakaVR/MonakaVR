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
parser.add_argument('--wire-minor',type=int,choices=[0,1],default=0)
args=parser.parse_args(); major=args.wire_major; minor=args.wire_minor
if minor and major!=2: parser.error('minor 1 requires major 2')
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
    report_name='test-v21-results.json' if minor else 'test-v2-results.json'
    report=json.loads((R/'build'/report_name).read_text())
    require(report['status']=='PASS','v2 validation did not pass')
    require(report.get('source_commit')==head,'v2 validation belongs to another HEAD')
    require(report.get('cases',0)>0 and report.get('cross_language_directions',0)>0,'empty v2 validation')
    require(report.get('source_sha256') and report.get('binary_sha256'),'missing v2 validation hashes')
    for group in ['source_sha256','binary_sha256']:
        for n,h in report[group].items(): require(sha((R/n).read_bytes())==h,'stale v2 validation: '+n)
    files['validation/'+('protocol-v21-results.json' if minor else 'protocol-v2-results.json')]=(R/'build'/report_name).read_bytes()
    if minor:
        require(report.get('old_decoder_new_rejections')==[10,10] and report.get('new_decoder_old_passes')==[4,4], 'missing old/new compatibility gate')
        require(report.get('ordered_scenarios',0)>=10, 'missing ordered-stream gate')
        require(max(report['max_fields_encoded_bytes'])<4096, 'authority envelope size gate')
        require(len(report.get('max_escaped_fields_encoded_bytes',[]))==2 and max(report['max_escaped_fields_encoded_bytes'])<4096, 'escaped authority envelope size gate')
hashes={n:sha(b) for n,b in sorted(files.items())}
lock={
    'schema_commit':schema_commit,'source_commit':head,'source_tree_clean':True,
    'contract_status':'C2.1 / trusted HMD superset; runtime blocked' if minor else 'C2 / current coordinated architecture revision' if major==2 else 'candidate / master reconciliation pending',
    'wire_version':{'major':major,'minor':minor},'contract_c1_sha256':hashes['docs/C1.md'],
    'contract_revision_sha256':hashes['docs/C2.1.md'] if minor else hashes['docs/C2.md'] if major==2 else hashes['docs/C1.md'],
    'toolchain_and_dependencies':json.loads((R/'dependencies.lock.json').read_text()),
    'artifact_sha256':{n:h for n,h in hashes.items() if n.endswith('.jar')},
    'schema_sha256':{n:h for n,h in hashes.items() if n.startswith('schema/')},
    'fixture_sha256':{n:h for n,h in hashes.items() if n.startswith('fixtures/')},
    'api_package_sha256':{n:h for n,h in hashes.items() if n.startswith(('cpp/','jvm/src/'))},
    'files_sha256':hashes,
}
if minor:
    lock['contract_c2_sha256']=hashes['docs/C2.md']
    lock['contract_c21_sha256']=hashes['docs/C2.1.md']
files['protocol.lock.json']=(json.dumps(lock,indent=2,ensure_ascii=False)+'\n').encode()
sums={n:sha(b) for n,b in sorted(files.items())}
files['SHA256SUMS']=''.join(f'{h}  {n}\n' for n,h in sums.items()).encode()
dist=R/'dist' if major==1 else R/('dist/v2.1' if minor else 'dist/v2');dist.mkdir(exist_ok=True,parents=True)
path=dist/f'monaka-protocol-kit-v{major}.{minor}.zip'
with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for n,b in sorted(files.items()):
        info=zipfile.ZipInfo(n,date_time=(2026,9,14,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=0o644<<16; z.writestr(info,b)
manifest={
    'artifact':path.name,'sha256':sha(path.read_bytes()),'source_commit':head,
    'schema_commit':schema_commit,'contract_c1_sha256':lock['contract_c1_sha256'],
    'wire_version':lock['wire_version'],'contract_revision_sha256':lock['contract_revision_sha256'],
    'protocol_lock_sha256':sha(files['protocol.lock.json']),
    'status':lock['contract_status'],
}
if minor:
    manifest['contract_c2_sha256']=lock['contract_c2_sha256']
    manifest['contract_c21_sha256']=lock['contract_c21_sha256']
(dist/'handoff-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(dist/'protocol.lock.json').write_bytes(files['protocol.lock.json'])
print(json.dumps(manifest,indent=2))
