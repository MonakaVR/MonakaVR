from pathlib import Path
import datetime
import hashlib
import json
import subprocess

repo = Path(r'C:/Users/nynyp/Documents/ChatGPT/MonakaVR_UI/.gui-work/MonakaVR-Desktop-dev')
workspace = repo.parents[1]
baseline = '1d5d556169f7a8c60e39f4f96e0eb9a6ff9e731b'
def git(path, *args):
    return subprocess.check_output(['git','-C',str(path),*args],encoding='utf-8').strip()
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
assert git(repo,'rev-parse','HEAD') == baseline
product_paths = ['gui/src','gui/electron','gui/package.json','gui/electron-builder.yml','pnpm-lock.yaml','package.json','server','src','dependencies','third_party','solarxr-protocol']
assert not git(repo,'diff',baseline,'--',*product_paths)
audit = json.loads((repo/'docs/gui-desktop-20261015/evidence/compatibility-audit.json').read_text(encoding='utf-8-sig'))
originals = {}
for name, expected in audit['original_checkouts'].items():
    path=workspace/name
    actual=dict(head=git(path,'rev-parse','HEAD'),branch=git(path,'branch','--show-current'),status=git(path,'status','--porcelain=v1'))
    assert actual == expected
    originals[name]=actual
bridge_differences=[name for name,expected in audit['frozen_bridge_sha256'].items() if sha(workspace/'MonakaBridge'/name)!=expected]
assert not bridge_differences
receipts=0
for manifest,folder in [('evidence-sha256.json','evidence'),('dev-evidence-sha256.json','dev-evidence')]:
    base=repo/'docs/gui-desktop-20261015'
    values=json.loads((base/manifest).read_text(encoding='utf-8-sig'))
    for name,expected in values.items():
        path=base/folder/name
        blob=subprocess.check_output(['git','-C',str(repo),'show',baseline+':'+path.relative_to(repo).as_posix()])
        assert hashlib.sha256(blob).hexdigest()==expected and sha(path)==expected
        receipts+=1
test_sources={str(path.relative_to(repo)).replace('\\','/'):sha(path) for path in (repo/'gui/tests/bridge-supplemental').iterdir() if path.is_file()}
legacy_sources={name:sha(workspace/'MonakaBridge'/name) for name in ['ui/steamvr_control_wpf.cs','ui/steamvr_control_tray.cs','ui/control_models.cs']}
out=dict(collected_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),baseline=baseline,
    branch=git(repo,'branch','--show-current'),remote=git(repo,'remote','get-url','origin'),
    merge_base_with_core_ingress=git(repo,'merge-base',baseline,'ebdde9188b6d1a91befa22d499f3e4228d25a47b'),
    product_and_runtime_diff=[],original_checkouts=originals,bridge_file_count=len(audit['frozen_bridge_sha256']),
    bridge_hash_differences=bridge_differences,previous_original_receipts_verified=receipts,
    supplemental_source_sha256=test_sources,legacy_source_sha256=legacy_sources,
    actions=dict(remote_push=False,service_stop=False,hardware_or_real_output=False,body_or_calibration_extension=False,new_lock_protocol=False),
    limits='Guarded main/test-package startup only; unrestricted full product startup and manual OS/HIL remain BLOCKED/NOT_RUN. Lost update is reproduced, not fixed.')
(repo/'build/gui-supplemental-20261015/boundary.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in out.items() if k not in ['original_checkouts','supplemental_source_sha256','legacy_source_sha256']},ensure_ascii=False,indent=2))
