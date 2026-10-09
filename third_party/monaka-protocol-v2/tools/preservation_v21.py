"""Read-only sibling preservation receipts; paths come from prior source audit."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

R=Path(__file__).resolve().parents[1]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def capture():
    workspace=R.parents[2] if R.name=='trusted-hmd-authority-wire-v21-worktree' else R.parent
    vr=workspace/'MonakaVR'
    repos={'MonakaVR5Z':vr/'build/openxr-common-world-authority-foundation-worktree','ALVR5X':vr/'build/alvr-provider-authority-prototype/source','MonakaBridge':workspace/'MonakaBridge'}
    expected={'MonakaVR5Z':'62d096ad98137aee7aaeb115c2b142a4e6448768','ALVR5X':'e94ff1967c70da480531c3ec9f6956d3be8b5ce5','MonakaBridge':'84f5b7529694d19aee98461580f3cfe628bf3cbc'}
    result={}
    for n,p in repos.items():
        def git(*args): return subprocess.check_output(['git',*args],cwd=p,text=True).strip()
        head=git('rev-parse','HEAD')
        if head!=expected[n]: raise RuntimeError(n+' pinned HEAD mismatch')
        result[n]=dict(path=str(p),head=head,status=git('status','--porcelain'),sha256={f:sha(p/f) for f in git('ls-files').splitlines() if (p/f).is_file()})
    keys=json.loads((vr/'build/reports/phase2b5y-alvr-openxr-monaka-mapping-authority-20261009/preservation-before.json').read_text())['protected']['5s_hashes']
    result['5S']={n:sha(vr/'build/direct-6dof-worktree'/n) for n in keys}
    if result['5S']!=keys: raise RuntimeError('5S differs from the historical protected hashes')
    return result
def main():
    p=argparse.ArgumentParser(); p.add_argument('--after',action='store_true'); a=p.parse_args()
    data=capture(); path=R/('build/preservation-after.json' if a.after else 'build/preservation-before.json')
    if a.after and data!=json.loads((R/'build/preservation-before.json').read_text()): raise RuntimeError('protected sibling bytes/HEAD/status changed')
    path.write_text(json.dumps(data,indent=2)+'\n')
    print('PASS preservation',len(data['5S']),'5S files and 3 pinned source trees')
if __name__=='__main__': main()
