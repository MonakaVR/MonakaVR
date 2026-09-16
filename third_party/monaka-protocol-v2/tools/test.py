"""Fixture, independent schema, and C++ <-> JVM semantic interoperability tests."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(R/'build/python-deps'))
import jsonschema
p=argparse.ArgumentParser()
p.add_argument('--cpp',default='build/cpp/Release/monaka_codec_runner.exe' if os.name=='nt' else 'build/cpp/monaka_codec_runner')
a=p.parse_args()
cpp=[str((R/a.cpp).resolve())]
cp=os.pathsep.join(str(R/x) for x in ['jvm/build/classes/kotlin/test','jvm/build/libs/monaka-protocol-jvm-0.1.0.jar','jvm/libs/gson-2.11.0.jar','jvm/libs/kotlin-stdlib-2.3.10.jar'])
jvm=['java','-Dfile.encoding=UTF-8','-cp',cp,'dev.monaka.protocol.v1.Runner']
def run(cmd,path,*args):
    r=subprocess.run(cmd+[str(path),*args],capture_output=True,check=True)
    return r.stdout.decode('utf-8').strip()
schemas={name:json.loads((R/'schema'/f'{name}.schema.json').read_text()) for name in ['tracker-observation','monaka-tracking']}
for s in schemas.values(): jsonschema.Draft202012Validator.check_schema(s)
index=json.loads((R/'fixtures/index.json').read_text(encoding='utf-8'))
errors=[]; count=0; cross=0
temp=R/'build/cross';temp.mkdir(parents=True,exist_ok=True)
for item in index:
    path=R/'fixtures'/item['file']
    try:
        expected_wire=copy.deepcopy(item.get('decoded'))
        if expected_wire is not None: expected_wire['version']['minor']=0
        if 'schema_valid' in item:
            value=json.loads(path.read_bytes())
            s=schemas['monaka-tracking' if value.get('protocol')=='monaka.tracking' else 'tracker-observation']
            actual=jsonschema.Draft202012Validator(s).is_valid(value)
            assert actual==item['schema_valid'],f'schema expected {item["schema_valid"]}, got {actual}'
        results=[]
        for label,cmd in [('cpp',cpp),('jvm',jvm)]:
            out=run(cmd,path)
            if 'error' in item: assert out=='ERROR:'+item['error']['code'],f'{label}: {out}'
            else: assert json.loads(out)==expected_wire,f'{label} semantics: {out}'
            results.append(out)
        if 'decoded' in item:
            for i,cmd in enumerate([jvm,cpp]):
                wire=temp/f'{path.stem}-{i}.json';wire.write_text(results[i],encoding='utf-8')
                out=run(cmd,wire)
                assert json.loads(out)==expected_wire,f'cross {i}: {out}'
                assert out==run(cmd,wire),f'non deterministic encoder {i}'
                cross+=1
        count+=1
    except Exception as e: errors.append(f'{item["file"]}: {e}')
print(run(cpp,'--self-test'))
print(run(jvm,R/'fixtures/valid/observation.json','--self-test'))
for cmd in [cpp,jvm]:
    assert run(cmd,R/'fixtures/valid/future-minor.json','--version-minor')=='65535'
report={'fixtures_passed':count,'fixture_count':len(index),'cross_language_directions_passed':cross,'errors':errors}
(R/'build/test-results.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
sys.exit(bool(errors))
