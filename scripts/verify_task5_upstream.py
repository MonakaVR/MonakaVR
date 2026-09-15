"""Accept only actual Task4 acc329d artifacts and the supplied fixed Task1 kit."""
import argparse,hashlib,io,json,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
HEAD='acc329dce90dd6ba21387029cd54b6fc2d82fe8c'
ZIP_SHA='3fe2b3c1b1703520e5433639647c25894a64432590997275da506c262f647cd3'
MANIFEST_SHA='c2bc55d0335d207e86a842b8d9a2ac687ce5a65414d3dfab884e63cbd23c7dc7'
KIT_SHA='eef5b7f2bc490926385b99dabcd44dc5a374228bf2a7869beea01f9dad936729'
LOCK_SHA='234f0dffc46b808a179ec91da3c185794d7b7c83bc5b7d2bcb31fa73886bcb44'
C1_SHA='3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3'
def sha(b):return hashlib.sha256(b).hexdigest()
def write(path,data):
 path.parent.mkdir(parents=True,exist_ok=True)
 if path.exists():assert path.read_bytes()==data,'Existing artifact differs: '+str(path)
 else:path.write_bytes(data)
p=argparse.ArgumentParser();p.add_argument('--supply',type=Path);args=p.parse_args()
dep=ROOT/'dependencies/task4'
if args.supply:
 for f in ['monaka-bridge-handoff.zip','monaka-bridge-handoff.handoff.json','task4-final-report.json']:write(dep/f,(args.supply/f).read_bytes())
data=(dep/'monaka-bridge-handoff.zip').read_bytes();external=(dep/'monaka-bridge-handoff.handoff.json').read_bytes();report=json.loads((dep/'task4-final-report.json').read_bytes())
assert sha(data)==ZIP_SHA and sha(external)==MANIFEST_SHA
metadata=json.loads(external);assert metadata['source_commit']==HEAD and metadata['artifact']['source_commit']==HEAD and report['HEAD_SHA']==HEAD
assert metadata['artifact']['sha256']==ZIP_SHA
assert any(x['filename']=='monaka-bridge-handoff.zip' and x['sha256']==ZIP_SHA and x['source_commit']==HEAD for x in report['handoff_artifacts'])
assert any(x['filename']=='monaka-bridge-handoff.handoff.json' and x['sha256']==MANIFEST_SHA for x in report['handoff_artifacts'])
with zipfile.ZipFile(io.BytesIO(data)) as z:
 assert z.testzip() is None
 content=z.read('handoff-content-manifest.json');assert sha(content)==metadata['content_manifest_sha256'];manifest=json.loads(content);assert manifest['source_commit']==HEAD
 for entry in manifest['files']:assert sha(z.read(entry['path']))==entry['sha256'],entry['path']
 validation=json.loads(z.read('evidence/validation-results.json'));assert validation['status']=='PASS'
 for name in ['direct-standalone','ctest','separate-process-udp','fixed-codec-interop']:assert validation['checks'][name]['result']=='PASS'
 origins=json.loads(z.read('source/dependencies/task2-derived-files.json'))
 for entry in origins['files']:assert sha(z.read('source/'+entry['path']))==entry['sha256']
 kit=z.read('source/dependencies/artifacts/monaka-protocol-kit-v1.0.zip');assert sha(kit)==KIT_SHA
 write(ROOT/'dependencies/monaka-protocol-kit-v1.0.zip',kit)
 task1_metadata=z.read('source/dependencies/reports/handoff-manifest.json');write(ROOT/'dependencies/task1-handoff-manifest.json',task1_metadata)
 write(dep/'verified-content-manifest.json',content)
 write(dep/'verified-validation-results.json',z.read('evidence/validation-results.json'))
with zipfile.ZipFile(io.BytesIO(kit)) as k:
 assert k.testzip() is None
 assert sha(k.read('protocol.lock.json'))==LOCK_SHA and sha(k.read('docs/C1.md'))==C1_SHA
 write(ROOT/'dependencies/monaka-protocol.lock.json',k.read('protocol.lock.json'))
 for line in k.read('SHA256SUMS').decode().splitlines():
  digest,path=line.split(maxsplit=1);assert sha(k.read(path.lstrip('*')))==digest,path
 for name in k.namelist():
  if name.endswith('/'):continue
  path=Path(name);assert not path.is_absolute() and '..' not in path.parts
  write(ROOT/'third_party/monaka-protocol'/path,k.read(name))
acceptance={'task4_source_commit':HEAD,'task4_handoff_sha256':ZIP_SHA,'task4_external_manifest_sha256':MANIFEST_SHA,'task4_report_sha256':sha((dep/'task4-final-report.json').read_bytes()),'task4_content_files_verified':len(manifest['files']),'task1_kit_sha256':KIT_SHA,'protocol_lock_sha256':LOCK_SHA,'c1_sha256':C1_SHA,'hardware':'NOT RUN'}
write(ROOT/'dependencies/task5-upstream.lock.json',(json.dumps(acceptance,indent=2)+'\n').encode())
print('PASS actual Task4 accepted HEAD/ZIP/manifest/report/219 contents/validation; exact Task1 kit/internal sums/lock/C1')
