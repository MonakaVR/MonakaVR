"""Generate wire bytes and independent explicit expected models/error codes."""
import copy
import json
from pathlib import Path
R=Path(__file__).resolve().parents[1]
F=R/'fixtures'
F.mkdir(exist_ok=True)
index=[]
base=dict(protocol='monaka.observation',type='pose',version=dict(major=1,minor=0),source_id='backend-A',device_id='device-01',session_id='11111111-1111-1111-1111-111111111111',clock_id='22222222-2222-2222-2222-222222222222',sequence='0',timestamp_ns='9007199254740993',sent_at_ns='9007199254741093',timestamp_kind='receive',position=[1.0,2.0,3.0],orientation=[0.0,0.0,0.0,1.0],validity=dict(position=True,orientation=True),orientation_evidence='device',tracking_state='tracked',battery=None,coordinate_space=dict(id='room-A',convention='rh_y_up_neg_z_forward',revision=0),capabilities=['position','orientation'])
def save(name,data,error=None,expected=None,schema_valid=None):
    directory='invalid' if error else 'valid'
    path=f'{directory}/{name}.json'
    (F/directory).mkdir(exist_ok=True)
    wire=data if isinstance(data,bytes) else json.dumps(data,ensure_ascii=False,separators=(',',':')).encode()
    (F/path).write_bytes(wire)
    entry=dict(file=path)
    if error: entry['error']=dict(code=error)
    else:
        exp=copy.deepcopy(expected if expected is not None else data)
        if exp['type']=='pose':
            for n in ['linear_velocity','angular_velocity','linear_acceleration']: exp.setdefault(n,None)
        entry['decoded']=exp
    if schema_valid is not None: entry['schema_valid']=schema_valid
    index.append(entry)
def mutate(name,fn,error=None,schema_valid=None):
    d=copy.deepcopy(base); fn(d); save(name,d,error,schema_valid=schema_valid)
save('observation',base,schema_valid=True)
mutate('orientation-only',lambda d:d.update(position=None,validity=dict(position=False,orientation=True),tracking_state='degraded',orientation_evidence='sample_sanity'),schema_valid=True)
mutate('invalid-position-moving',lambda d:d.update(position=[999.0,-44.0,23.0],validity=dict(position=False,orientation=True),tracking_state='degraded'),schema_valid=True)
mutate('position-only',lambda d:d.update(orientation=None,validity=dict(position=True,orientation=False),orientation_evidence='none',tracking_state='degraded'),schema_valid=True)
mutate('unknown-battery',lambda d:d.update(battery=dict(fraction=None,charging=None,timestamp_ns='9007199254740000')),schema_valid=True)
mutate('known-battery',lambda d:d.update(battery=dict(fraction=0.5,charging=False,timestamp_ns=d['timestamp_ns']),capabilities=d['capabilities']+['battery_fraction','charging']),schema_valid=True)
mutate('negative-quaternion',lambda d:d.update(orientation=[0,0,0,-1]),schema_valid=True)
mutate('observation-unnormalized',lambda d:d.update(orientation=[0,0,0,1.4]),schema_valid=True)
mutate('session-reset',lambda d:d.update(session_id='33333333-3333-3333-3333-333333333333',sequence='0'),schema_valid=True)
mutate('different-clock-epoch',lambda d:d.update(clock_id='44444444-4444-4444-4444-444444444444',timestamp_ns='0',sent_at_ns='100'),schema_valid=True)
mutate('u63-maximum',lambda d:d.update(sequence='9223372036854775807',timestamp_ns='9223372036854775807',sent_at_ns='9223372036854775807'),schema_valid=True)
mutate('derivatives',lambda d:d.update(linear_velocity=dict(value=[1,2,3],frame='space',evidence='measured'),angular_velocity=dict(value=[0,1,0],frame='device',evidence='derived'),linear_acceleration=dict(value=[0,0,0],frame='space',evidence='derived'),capabilities=d['capabilities']+['linear_velocity','angular_velocity','linear_acceleration']),schema_valid=True)
future=copy.deepcopy(base); future['version']['minor']=65535; future['extension']={'a':[1,2,3]}
future_expected=copy.deepcopy(future); future_expected.pop('extension')
save('future-minor',future,expected=future_expected,schema_valid=True)
save('reordered-whitespace',json.dumps(dict(reversed(list(base.items()))),indent=1).encode(),expected=base,schema_valid=True)
mutate('utf8-id-limit',lambda d:d.update(device_id='あ'*32),schema_valid=True)
state={k:copy.deepcopy(v) for k,v in base.items() if k not in ['position','orientation','validity','orientation_evidence']}
state.update(type='device_state',presence='present')
save('device-state',state,schema_valid=True)
mtp={k:copy.deepcopy(v) for k,v in base.items() if k not in ['device_id','battery','orientation_evidence']}
mtp.update(protocol='monaka.tracking',tracker_id='tracker-A',mapping_revision=0,confidence=dict(position=1.0,orientation=0.5),input=dict(device_id='device-01',session_id=base['session_id'],sequence='0'))
save('mtp-pose',mtp,schema_valid=True)
ms={k:copy.deepcopy(v) for k,v in state.items() if k!='device_id'}
ms.update(protocol='monaka.tracking',type='tracker_state',tracker_id='tracker-A',mapping_revision=4294967295)
save('tracker-state',ms,schema_valid=True)
for n,v in [('zero',0),('half',0.5),('onehalf',1.5)]:
    mutate('quat-boundary-'+n,lambda d,v=v:d.update(orientation=[0,0,0,v]),'InvalidQuaternion' if v==0 else None,schema_valid=True)
def bad(name,key,value,code='OutOfRange',sv=False): mutate(name,lambda d:d.update({key:value}),code,sv)
bad('numeric-u63','sequence',9007199254740993,'InvalidType')
for value,label in [('-1','negative'),('01','leading-zero'),('9223372036854775808','overflow'),('1.0','decimal'),('1e3','exponent'),('','empty')]: bad('u63-'+label,'sequence',value,sv=True if label=='overflow' else False)
bad('empty-id','source_id','')
bad('id-byte-overflow','device_id','あ'*33,sv=True)
bad('id-control','device_id','a\x00b')
bad('id-c1-control','device_id','a\u0080b')
bad('uppercase-uuid','session_id','AAAAAAAA-1111-1111-1111-111111111111')
bad('unknown-enum','tracking_state','optical','UnsupportedValue')
bad('unknown-protocol','protocol','potb','UnsupportedMessage')
bad('unknown-message','type','new_message','UnsupportedMessage')
bad('unsupported-major','version',dict(major=2,minor=0),'UnsupportedVersion')
bad('minor-overflow','version',dict(major=1,minor=65536))
bad('fractional-minor','version',dict(major=1,minor=0.5),'InvalidType')
bad('bad-vec-length','position',[0,1])
bad('bad-number-type','position',['1',2,3],'InvalidType')
bad('null-valid-position','position',None,'InconsistentValidity',True)
bad('invalid-state','tracking_state','lost','InconsistentValidity',True)
bad('missing-capability','capabilities',['orientation'],'InconsistentValidity',True)
bad('duplicate-capability','capabilities',['position','orientation','position'],'InconsistentValidity')
bad('future-timestamp','timestamp_ns','9223372036854775807','OutOfRange',True)
bad('bad-quaternion','orientation',[0,0,0,2],'InvalidQuaternion',True)
bad('none-evidence-valid','orientation_evidence','none','InconsistentValidity',True)
bad('derivative-without-capability','linear_velocity',dict(value=[1,2,3],frame='space',evidence='derived'),'InconsistentValidity',True)
bad('battery-range','battery',dict(fraction=2,charging=None,timestamp_ns=base['timestamp_ns']))
mutate('missing-field',lambda d:d.pop('battery'),'MissingField',False)
for name,change,code,sv in [
    ('mtp-unnormalized',dict(orientation=[0,0,0,1.1]),'InvalidQuaternion',True),
    ('mtp-confidence-zero',dict(confidence=dict(position=0,orientation=1)),'InconsistentValidity',True),
    ('mtp-wrong-convention',dict(coordinate_space=dict(id='room',convention='other',revision=0)),'UnsupportedValue',False),
    ('mtp-revision-overflow',dict(mapping_revision=4294967296),'OutOfRange',False)]:
    d=copy.deepcopy(mtp);d.update(change);save(name,d,code,schema_valid=sv)
raw=json.dumps(base,separators=(',',':')).encode()
for name,data,code in [
    ('empty',b'','MalformedJson'),('truncated',raw[:-1],'MalformedJson'),('extra-object',raw+b'{}','MalformedJson'),
    ('bom',b'\xef\xbb\xbf'+raw,'MalformedJson'),('utf8',b'\xff','InvalidUtf8'),
    ('overlong-utf8',b'"\xc0\xaf"','InvalidUtf8'),('surrogate-utf8',b'"\xed\xa0\x80"','InvalidUtf8'),
    ('duplicate',raw[:-1]+b',"sequence":"1"}','DuplicateKey'),
    ('duplicate-escaped-key',raw[:-1]+b',"sequen\\u0063e":"1"}','DuplicateKey'),
    ('duplicate-unknown',raw[:-1]+b',"extra":{"a":1,"a":2}}','DuplicateKey'),
    ('nonfinite',raw.replace(b'[1.0,2.0,3.0]',b'[1e400,2,3]'),'OutOfRange'),
    ('nan',raw.replace(b'[1.0,2.0,3.0]',b'[NaN,2,3]'),'MalformedJson'),
    ('comment',b'/* comment */'+raw,'MalformedJson'),('trailing-comma',raw[:-1]+b',}','MalformedJson'),
    ('unpaired-surrogate',raw.replace(b'device-01',b'\\ud800'),'MalformedJson'),
    ('depth-17',raw[:-1]+b',"extension":'+b'['*16+b'0'+b']'*16+b'}','MalformedJson'),
    ('too-large',b' '*4097,'TooLarge')]: save(name,data,code)
save('depth-16',raw[:-1]+b',"extension":'+b'['*15+b'0'+b']'*15+b'}',expected=base,schema_valid=True)
save('size-4096',raw+b' '*(4096-len(raw)),expected=base,schema_valid=True)
(F/'index.json').write_text(json.dumps(index,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
# Declarative consumer scenarios; the protocol library intentionally has no cache.
scenarios={
 'sequence_scope':['protocol','type','source_id','device_id_or_tracker_id','session_id'],
 'cases':[
  {'name':'partial-loss','frames':['valid/observation.json','valid/orientation-only.json','valid/invalid-position-moving.json'],'expect':'position becomes invalid; orientation stays usable'},
  {'name':'stale-replay','frames':['valid/observation.json','valid/observation.json'],'expect':'consumer ignores repeated sequence; freshness unchanged'},
  {'name':'session-reset','frames':['valid/observation.json','valid/session-reset.json','valid/observation.json'],'expect':'accept new session; reject retired session replay'},
  {'name':'clock-epoch','frames':['valid/observation.json','valid/different-clock-epoch.json'],'expect':'never directly subtract timestamps from distinct clocks'},
  {'name':'state-does-not-refresh-pose','frames':['valid/observation.json','valid/device-state.json'],'expect':'state stream does not update pose freshness'}]}
(F/'consumer-scenarios.json').write_text(json.dumps(scenarios,indent=2)+'\n')
print(f'{len(index)} fixtures')
