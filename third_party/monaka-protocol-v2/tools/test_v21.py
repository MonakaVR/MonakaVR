"""Source-bound v2.1 tests; codecs stay stateless, stream oracle is test-only."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

R=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(R/'build/revision-python-deps'))
import jsonschema
from fixtures_v21 import valid
from ordered_stream_reference import Reference
from test_v2 import resolve_cpp_runner, require

PIN='572e58cfa20b8b4335207ea5dbcb3f04c587ddff'
BASE='6d2ae625c42117bb804538f21aa1ada16fe7a8a5'
def sha(b): return hashlib.sha256(b).hexdigest()
def java(root):
    cp=os.pathsep.join(str(root/p) for p in ['jvm/build/classes/kotlin/test','jvm/build/libs/monaka-protocol-jvm-0.1.0.jar','jvm/libs/gson-2.11.0.jar','jvm/libs/kotlin-stdlib-2.3.10.jar'])
    return ['java','-cp',cp,'dev.monaka.protocol.v2.Runner']
def run(command,path): return subprocess.check_output(command+[str(path)],encoding='utf-8').strip()
def git_bytes(commit,name): return subprocess.check_output(['git','show',commit+':'+name],cwd=R)

def main():
    p=argparse.ArgumentParser(); p.add_argument('--cpp'); a=p.parse_args()
    cpp=resolve_cpp_runner(a.cpp); commands=[[str(cpp)],java(R)]
    old=R/'build/old-v20'; oldcpp=old/'build/cpp/Release/monaka_codec_runner_v2.exe' if os.name=='nt' else old/'build/cpp/monaka_codec_runner_v2'
    require(oldcpp.is_file(),'run prepare_old_v20.py first')
    oldcommands=[[str(oldcpp)],java(old)]
    folder=R/'fixtures/v2.1'; scratch=R/'build/cross-v21'; scratch.mkdir(parents=True,exist_ok=True)
    schema=json.loads((R/'schema/v2/trusted-hmd-authority.schema.json').read_text())
    index=json.loads((folder/'index.json').read_text()); directions=0; sizes={}
    for item in index:
        path=folder/item['file']
        if item['error'] is None: jsonschema.Draft202012Validator(schema).validate(item['decoded'])
        for i,command in enumerate(commands):
            out=run(command,path)
            if item['error']: require(out=='ERROR:'+item['error'],item['file']+': '+out)
            else:
                expected=copy.deepcopy(item['decoded']); expected['version']['minor']=1; expected.pop('future_note',None)
                if 'source' in expected: expected['source']['source_space'].pop('future_note',None)
                require(json.loads(out)==expected,item['file']+' semantic equality')
                canonical=scratch/f'cross-{i}.json'; canonical.write_text(out,encoding='utf-8')
                require(json.loads(run(commands[1-i],canonical))==expected,item['file']+' cross-language semantics')
                directions+=1; sizes[item['file']]=max(sizes.get(item['file'],0),len(out.encode('utf-8')))
    old_counts=[0,0]; reject_counts=[0,0]; canonical_hashes={}
    for n in valid():
        for i,c in enumerate(oldcommands):
            require(run(c,folder/'valid'/(n+'.json'))=='ERROR:UnsupportedMessage','old decoder must reject '+n)
            reject_counts[i]+=1
    for n in ['observation','device-state','mtp-pose','tracker-state']:
        path=R/f'fixtures/v2/{n}.json'; canonical_hashes[n]={}
        for i,c in enumerate(commands):
            original=run(oldcommands[i],path); out=run(c,path)
            require(out==original,'v2.0 canonical byte regression '+n)
            require(json.loads(out)==json.loads(path.read_text()),'v2.0 semantic regression '+n)
            canonical_hashes[n]['cpp' if i==0 else 'jvm']=sha(out.encode()); old_counts[i]+=1
    # Frozen C2, schemas and ALL fixture bytes, checked against the exact base.
    names=subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'docs/C2.md','schema/v2','fixtures/v2'],cwd=R,text=True).splitlines()
    preserved={}
    for n in names:
        require((R/n).read_bytes()==git_bytes(BASE,n),'frozen v2.0 byte regression '+n)
        preserved[n]=sha((R/n).read_bytes())
    scenario_count=0; event_count=0; stream_evidence=[]
    for item in json.loads((folder/'ordered-streams/index.json').read_text()):
        events=[json.loads(line) for line in (folder/item['file']).read_text().splitlines()]
        ref=Reference(); outcomes=[]
        for event in events:
            raw=scratch/'event.json'; raw.write_text(json.dumps(event),encoding='utf-8')
            for command in commands: require(not run(command,raw).startswith('ERROR:'),'stream wire must decode')
            outcomes.append(ref.accept(event)); event_count+=1
        require(outcomes==item['outcomes'],item['file']+': '+str(outcomes))
        if 'revoke-no-pose' in item['file']: require(not ref.pose,'revocation must close pose with no next sample')
        if 'duplicate' in item['file']: require(ref.freshness_updates==1,'duplicate/conflict refreshed freshness')
        stream_evidence.append(dict(file=item['file'],outcomes=outcomes,freshness_updates=ref.freshness_updates,pose_available=ref.pose))
        scenario_count+=1
    # Max legitimate field widths and large finite binary64 values; no fake oversized optional padding.
    largest=valid()['common-hmd-pose']
    def maximize(x,key=''):
        if isinstance(x,dict): return {k:maximize(v,k) for k,v in x.items()}
        if isinstance(x,list): return x
        if isinstance(x,str) and key in ['publisher_id','owner_id','world_epoch','calibration_epoch','source_id','source_authority_session_epoch','source_space_id','source_time_domain_id','id']: return 'x'*96
        if key in ['sequence','timestamp_ns','sent_at_ns','observation_id','source_space_generation']: return '9223372036854775807'
        if key=='source_locate_time_ns': return '-9223372036854775808'
        if key in ['revision','mapping_revision']: return 4294967295
        return x
    largest=maximize(largest)
    for k in ['source_position','common_position']: largest[k]=[1.7976931348623157e308,-1.7976931348623157e308,1.7976931348623157e308]
    path=scratch/'max-fields.json'; path.write_text(json.dumps(largest,separators=(',',':')))
    max_sizes=[]
    for c in commands:
        out=run(c,path)
        require(not out.startswith('ERROR:'),'maximum authority envelope failed: '+out)
        require(json.loads(out)==largest,'maximum authority envelope semantics')
        max_sizes.append(len(out.encode()))
    require(max(max_sizes)<4096,'complete common envelope exceeds limit')
    # Quotes/backslashes expand in JSON while still satisfying the 96-byte Id
    # bound. Combine escaped IDs with long binary64 unit-quaternion components.
    escaped=copy.deepcopy(largest)
    def escape_ids(x):
        if isinstance(x,dict): return {k:escape_ids(v) for k,v in x.items()}
        if isinstance(x,list): return x
        return '\\'*96 if x=='x'*96 else x
    escaped=escape_ids(escaped)
    for k in ['source_orientation','common_orientation']:
        escaped[k]=[0.5773502691896257,0.5773502691896257,0.5773502691896257,2.2250738585072014e-308]
    path=scratch/'max-escaped-fields.json'; path.write_text(json.dumps(escaped,separators=(',',':')))
    escaped_sizes=[]
    for c in commands:
        out=run(c,path)
        require(not out.startswith('ERROR:'),'maximum escaped authority envelope failed: '+out)
        require(json.loads(out)==escaped,'maximum escaped authority envelope semantics')
        escaped_sizes.append(len(out.encode()))
    require(max(escaped_sizes)<4096,'escaped common envelope exceeds limit')
    files=[p for f in ['cpp/include','cpp/src','cpp/tests','jvm/src','schema','fixtures','docs','tools'] for p in (R/f).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    report=dict(status='PASS',source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),cases=len(index),valid_cases=sum(x['error'] is None for x in index),invalid_cases=sum(x['error'] is not None for x in index),cross_language_directions=directions,ordered_scenarios=scenario_count,ordered_events=event_count,streams=stream_evidence,old_decoder_source=PIN,old_decoder_new_rejections=reject_counts,new_decoder_old_passes=old_counts,canonical_v20_sha256=canonical_hashes,frozen_v20_sha256=preserved,encoded_sizes=sizes,max_fields_encoded_bytes=max_sizes,hardware='NOT RUN',source_sha256={p.relative_to(R).as_posix():sha(p.read_bytes()) for p in files},binary_sha256={cpp.relative_to(R).as_posix():sha(cpp.read_bytes()),'jvm/build/libs/monaka-protocol-jvm-0.1.0.jar':sha((R/'jvm/build/libs/monaka-protocol-jvm-0.1.0.jar').read_bytes())})
    report['max_escaped_fields_encoded_bytes']=escaped_sizes
    (R/'build/test-v21-results.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k in ['status','cases','valid_cases','invalid_cases','cross_language_directions','ordered_scenarios','ordered_events','max_fields_encoded_bytes','old_decoder_new_rejections','new_decoder_old_passes']}))

if __name__=='__main__': main()
