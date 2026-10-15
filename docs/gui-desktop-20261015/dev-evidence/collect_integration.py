"""Read-only final dev merge/source boundary audit."""
from pathlib import Path
import hashlib
import json
import subprocess
import datetime

root = Path(r'C:/Users/nynyp/Documents/ChatGPT/MonakaVR_UI')
repo = root / '.gui-work/MonakaVR-Desktop-dev'
before = 'c11dc54ed1a745ffc4b15db7f820d8d0c1993990'
candidate = '160fbf4ce09434a1abf37542a46a09b5298b115d'
merge = '4d9836077dd0ab9e598d7a215d7287057e79ea01'

def git(path, *args):
    return subprocess.check_output(['git', '-C', str(path), *args], encoding='utf-8').strip()

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

paths = git(repo, 'diff', '--name-only', before, merge).splitlines()
outside = [name for name in paths if not name.startswith(('gui/', 'docs/gui-desktop-20261015/')) and name != 'pnpm-lock.yaml']
protected = git(repo, 'diff', '--name-only', before, merge, '--', 'server', 'src', 'third_party', 'dependencies', 'solarxr-protocol', 'package.json').splitlines()
original_audit = json.loads((repo/'docs/gui-desktop-20261015/evidence/compatibility-audit.json').read_text(encoding='utf-8-sig'))
originals = {}
for name in original_audit['original_checkouts']:
    path = root/name
    originals[name] = dict(head=git(path, 'rev-parse', 'HEAD'), branch=git(path, 'branch', '--show-current'), status=git(path, 'status', '--porcelain=v1'))
    assert originals[name] == original_audit['original_checkouts'][name]
bridge = root/'MonakaBridge'
bridge_changed = [name for name, expected in original_audit['frozen_bridge_sha256'].items() if sha(bridge/name) != expected]
manifest = json.loads((repo/'docs/gui-desktop-20261015/evidence-sha256.json').read_text(encoding='utf-8-sig'))
receipt_changed = [name for name, expected in manifest.items() if sha(repo/'docs/gui-desktop-20261015/evidence'/name) != expected]
parents = git(repo, 'show', '-s', '--format=%P', merge).split()
same_tree = git(repo, 'rev-parse', merge+'^{tree}') == git(repo, 'rev-parse', candidate+'^{tree}')
prepared_ancestor = subprocess.run(['git','-C',str(repo),'merge-base','--is-ancestor','17c4c9bb712b425bd72911837c7df010dee5bd62',merge]).returncode == 0
assert parents == [before, candidate] and same_tree and not prepared_ancestor
assert not outside and not protected and not bridge_changed and not receipt_changed
result = dict(collected_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    repository='MonakaVR/MonakaVR', worktree=str(repo), local_branch=git(repo,'branch','--show-current'),
    before=before, candidate=candidate, merge=merge, parents=parents,
    merge_tree_equals_validated_candidate=same_tree, prepared_runtime_lineage_included=prepared_ancestor,
    source_gui_tree=git(repo,'rev-parse','codex/gui-bridge-controls-20261015:gui'),
    merged_gui_tree=git(repo,'rev-parse',merge+':gui'),
    changed_paths=paths, outside_authorized_surface=outside, protected_runtime_diff=protected,
    bridge_972_original_file_hash_differences=bridge_changed,
    preserved_original_receipt_hash_differences=receipt_changed, original_checkouts=originals,
    remote_dev=git(repo,'ls-remote','origin','refs/heads/dev'),
    remote_push_performed=False,
    legacy_4pf_integrity='FAIL: two original receipt files remain missing',
    limits='Local GUI-only merge. No P2R/Core branch merge, live output, hardware, packaged startup or manual OS tray click.')
(repo/'build/gui-dev-regression/boundary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n', encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k not in ('changed_paths','original_checkouts')}, ensure_ascii=False,indent=2))
