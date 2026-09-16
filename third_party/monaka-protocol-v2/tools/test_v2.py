"""Independent v2 capability/identity/time cases; C++ and JVM both directions.

The legacy fixtures supply numeric values only. This test explicitly assigns
synthetic modality; it is not a runtime v1-to-v2 converter or vendor evidence.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import hashlib

R = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(R / 'build/revision-python-deps'))
import jsonschema

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def cases():
    result = []
    for name in ['observation', 'device-state', 'mtp-pose', 'tracker-state']:
        p = json.loads((R / f'fixtures/valid/{name}.json').read_text())
        p['version'] = {'major': 2, 'minor': 0}
        p['modality'] = 'full' if p['type'] == 'pose' else 'none'
        if p['type'] == 'pose':
            for field in ['linear_velocity', 'angular_velocity', 'linear_acceleration']: p.setdefault(field, None)
        if p['protocol'] == 'monaka.tracking':
            p['publisher_id'] = 'synthetic-bridge'
        if 'input' in p:
            p['input'].update(source_id=p['source_id'], orientation_evidence='device')
        result.append((name, copy.deepcopy(p), None))
        if p['type'] == 'pose':
            for modality in ['rotation_only', 'none']:
                q = copy.deepcopy(p)
                q['modality'] = modality
                q['validity'] = {'position': False, 'orientation': modality == 'rotation_only'}
                q['tracking_state'] = 'degraded' if modality == 'rotation_only' else 'lost'
                if 'confidence' in q:
                    q['confidence'] = {'position': 0, 'orientation': 1 if modality == 'rotation_only' else 0}
                if 'orientation_evidence' in q and modality == 'none': q['orientation_evidence'] = 'none'
                result.append((name+'-'+modality, q, None))
            for modality in ['rotation_only', 'none', 'bogus']:
                q = copy.deepcopy(p); q['modality'] = modality
                result.append((name+'-inconsistent-'+modality, q, 'UnsupportedValue' if modality == 'bogus' else 'InconsistentValidity'))
        for field in ['modality'] + (['publisher_id'] if 'publisher_id' in p else []):
            q = copy.deepcopy(p); del q[field]
            result.append((name+'-missing-'+field, q, 'MissingField'))
        q = copy.deepcopy(p); q['version']['major'] = 1
        result.append((name+'-legacy-version', q, 'UnsupportedVersion'))
        if 'battery' in p:
            q = copy.deepcopy(p)
            q['battery'] = {'fraction': None, 'charging': None, 'timestamp_ns': '9223372036854775807'}
            result.append((name+'-future-battery', q, 'OutOfRange'))
            q = copy.deepcopy(p)
            q['battery'] = {'fraction': None, 'charging': None, 'timestamp_ns': q['sent_at_ns']}
            result.append((name+'-battery-boundary', q, None))
        if 'input' in p:
            q = copy.deepcopy(p); q['input']['source_id'] = 'wrong-source'
            result.append((name+'-input-source-mismatch', q, 'InconsistentValidity'))
        if p['type'] != 'pose':
            q = copy.deepcopy(p); q['presence'] = 'absent'; q['modality'] = 'full'
            result.append((name+'-absent-full', q, 'InconsistentValidity'))
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cpp', default='build/cpp/Release/monaka_codec_runner_v2.exe')
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    folder = R / 'fixtures/v2'; folder.mkdir(exist_ok=True)
    index = []
    for name, value, error in cases():
        path = folder / (name+'.json')
        path.write_text(json.dumps(value, indent=2)+'\n', newline='\n')
        index.append({'file': path.name, 'error': error, 'decoded': value if error is None else None})
    (folder/'index.json').write_text(json.dumps(index, indent=2)+'\n', newline='\n')
    if args.generate_only: return
    cp = os.pathsep.join(str(R / p) for p in ['jvm/build/classes/kotlin/test', 'jvm/build/libs/monaka-protocol-jvm-0.1.0.jar', 'jvm/libs/gson-2.11.0.jar', 'jvm/libs/kotlin-stdlib-2.3.10.jar'])
    commands = [[str((R / args.cpp).resolve())], ['java', '-cp', cp, 'dev.monaka.protocol.v2.Runner']]
    scratch = R / 'build/cross-v2'; scratch.mkdir(exist_ok=True)
    directions = 0
    for item in index:
        path = folder/item['file']
        if item['error'] is None:
            schema_name = 'monaka-tracking' if item['decoded']['protocol'] == 'monaka.tracking' else 'tracker-observation'
            jsonschema.Draft202012Validator(json.loads((R/f'schema/v2/{schema_name}.schema.json').read_text())).validate(item['decoded'])
        for i, command in enumerate(commands):
            output = subprocess.check_output(command+[str(path)], text=True, encoding='utf-8').strip()
            if item['error']:
                require(output == 'ERROR:'+item['error'], f'{item["file"]}: {output}')
            else:
                require(json.loads(output) == item['decoded'], item['file']+' semantics')
                cross = scratch/f'{i}.json'; cross.write_text(output, encoding='utf-8')
                require(json.loads(subprocess.check_output(commands[1-i]+[str(cross)], text=True, encoding='utf-8')) == item['decoded'], 'cross semantics')
                directions += 1
    files = [p for folder in ['cpp/src','cpp/include','jvm/src','schema','fixtures/v2'] for p in (R/folder).rglob('*') if p.is_file()]
    report = {'status': 'PASS', 'cases': len(index), 'cross_language_directions': directions, 'hardware': 'NOT RUN',
              'source_sha256': {p.relative_to(R).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
              'binary_sha256': {str(p.relative_to(R)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [R/args.cpp, R/'jvm/build/libs/monaka-protocol-jvm-0.1.0.jar']}}
    (R/'build/test-v2-results.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if not k.endswith('sha256')}))

if __name__ == '__main__': main()
