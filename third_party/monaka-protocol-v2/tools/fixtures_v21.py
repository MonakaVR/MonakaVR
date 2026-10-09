"""Deterministic individual and ordered-stream fixtures, all synthetic facts."""
import copy
import json
from pathlib import Path

R = Path(__file__).resolve().parents[1]
SESSION = '11111111-1111-4111-8111-111111111111'
SECOND = '22222222-2222-4222-8222-222222222222'
S = dict(source_id='synthetic-alvr', source_authority_session_epoch='00112233445566778899aabbccddeeff', source_space_id='stage-view', source_space_generation='0')
W = dict(owner_id='synthetic-bridge', world_epoch='world-A', coordinate_space=dict(id='common-A', convention='rh_y_up_neg_z_forward', revision=1))
M = dict(world=W, source_space=S, calibration_epoch='calibration-A', mapping_revision=7)
O = dict(source_space=S, observation_id='9007199254740993', source_locate_time_ns='-123', source_time_domain_id='xr-time-A')
V = dict(position_valid=True, orientation_valid=True, position_tracked=True, orientation_tracked=True, view_position_valid=True, view_orientation_valid=True)
P = [1.0, 2.0, 3.0]
Q = [0.0, 0.0, 0.0, 1.0]

def fixture(t, **payload):
    source = t.startswith('source_')
    # Each wire reference is independent: deepcopy would preserve shared Python aliases.
    return json.loads(json.dumps(dict(version=dict(major=2, minor=1), protocol='monaka.hmd_authority' if source else 'monaka.common_world', type=t, publisher_id='synthetic-alvr' if source else 'synthetic-bridge', session_id=SESSION, clock_id=SECOND, sequence='0', timestamp_ns='9007199254740993', sent_at_ns='9007199254740994', timestamp_kind='receive', **payload)))

def valid():
    return {
        'source-authority': fixture('source_authority', source_space=S, source_space_kind='openxr_view_in_stage', source_time_domain_id='xr-time-A'),
        'source-pose': fixture('source_pose', source=O, position=P, orientation=Q, validity=V),
        'source-unavailable': fixture('source_unavailable', source_space=S, reason='tracking_lost', validity={**V, 'position_valid': False}),
        'source-revocation': fixture('source_revocation', source_space=S, reason='event_stream_lost'),
        'world-authority': fixture('world_authority', world=W, anchor_source=S),
        'world-mapping': fixture('mapping_publication', mapping=M, transform=dict(rotation_xyzw=Q, translation_xyz=[0.0, 0.0, 0.0])),
        'common-hmd-pose': fixture('common_pose', mapping=M, source=O, source_position=P, source_orientation=Q, validity=V, common_position=P, common_orientation=Q),
        'common-hmd-unavailable': fixture('common_unavailable', mapping=M, reason='pose_unavailable', validity=None),
        'mapping-revocation': fixture('mapping_revocation', mapping=M, reason='mapping_withdrawn'),
        'world-revocation': fixture('world_revocation', world=W, reason='anchor_authority_lost'),
    }

def set_path(p, path, value):
    keys=path.split('.')
    for k in keys[:-1]: p=p[k]
    if value is DELETE: del p[keys[-1]]
    else: p[keys[-1]]=value

DELETE = object()

def cases():
    result=[(n,p,None) for n,p in valid().items()]
    def add(n, base, path, value, error):
        p=copy.deepcopy(valid()[base]); set_path(p,path,value); result.append((n,p,error))
    for n,b,path,x,e in [
        ('authority-minor0','source-authority','version.minor',0,'UnsupportedVersion'),
        ('missing-publisher-session','source-authority','session_id',DELETE,'MissingField'),
        ('missing-sequence','source-authority','sequence',DELETE,'MissingField'),
        ('blank-source-authority-session','source-authority','source_space.source_authority_session_epoch','   ','OutOfRange'),
        ('missing-source-time-domain','source-pose','source.source_time_domain_id',DELETE,'MissingField'),
        ('nonfinite-source-position','source-pose','position',[1e309,0,0],'OutOfRange'),
        ('nonunit-source-quaternion','source-pose','orientation',[0,0,0,2],'InvalidQuaternion'),
        ('bad-validity','source-pose','validity.position_valid',1,'InvalidType'),
        ('missing-validity','source-pose','validity.view_orientation_valid',DELETE,'MissingField'),
        ('blank-world-epoch','world-authority','world.world_epoch',' ','OutOfRange'),
        ('wrong-world-convention','world-authority','world.coordinate_space.convention','left_handed','UnsupportedValue'),
        ('mapping-revision-overflow','world-mapping','mapping.mapping_revision',4294967296,'OutOfRange'),
        ('nonunit-mapping-quaternion','world-mapping','transform.rotation_xyzw',[0,0,0,2],'InvalidQuaternion'),
        ('nonfinite-mapping-translation','world-mapping','transform.translation_xyz',[1e309,0,0],'OutOfRange'),
        ('common-pose-reference-mismatch','common-hmd-pose','source.source_space.source_space_generation','1','InconsistentValidity'),
        ('blank-calibration','common-hmd-pose','mapping.calibration_epoch',' ','OutOfRange'),
        ('unknown-revocation-reason','world-revocation','reason','lost_pose','UnsupportedValue'),
        ('timestamp-after-send','source-authority','timestamp_ns','9223372036854775807','OutOfRange'),
        ('missing-common-position','common-hmd-pose','common_position',DELETE,'MissingField'),
        ('null-common-orientation','common-hmd-pose','common_orientation',None,'InvalidType'),
        ('unknown-protocol','source-authority','protocol','monaka.future','UnsupportedMessage'),
        ('unknown-type','source-authority','type','future','UnsupportedMessage'),
        ('id-byte-overflow','source-authority','publisher_id','あ'*33,'OutOfRange'),
        ('too-large-authority-message','common-hmd-pose','padding','x'*4096,'TooLarge'),
    ]: add(n,b,path,x,e)
    for label,base,path in [('sequence','source-authority','sequence'),('generation','source-authority','source_space.source_space_generation'),('observation','source-pose','source.observation_id')]:
        for suffix,x,e in [('zero','0',None),('one','1',None),('max','9223372036854775807',None),('overflow','9223372036854775808','OutOfRange'),('negative','-1','OutOfRange'),('decimal','1.0','OutOfRange'),('exponent','1e3','OutOfRange'),('leading-zero','01','OutOfRange'),('number',1,'InvalidType')]:
            add(f'{label}-{suffix}',base,path,x,e)
    for suffix,x,e in [('min','-9223372036854775808',None),('max','9223372036854775807',None),('underflow','-9223372036854775809','OutOfRange'),('negative-zero','-0','OutOfRange'),('plus','+1','OutOfRange')]:
        add('locate-'+suffix,'source-pose','source.source_locate_time_ns',x,e)
    for n in valid(): add(n+'-future-minor',n,'version.minor',2,None)
    add('unknown-field','source-authority','future_note','ignored',None)
    add('source-pose-negative-q','source-pose','orientation',[0.0,0.0,0.0,-1.0],None)
    add('source-unavailable-unknown-flags','source-unavailable','validity',None,None)
    add('blank-unicode-id','source-authority','publisher_id','\u3000','OutOfRange')
    add('nested-future-field','common-hmd-pose','source.source_space.future_note','ignored',None)
    return result

def event(n, seq, **changes):
    p=valid()[n]; p['sequence']=str(seq)
    for path,value in changes.items(): set_path(p,path,value)
    return p

def streams():
    a=event('source-authority',0); p=event('source-pose',1); u=event('source-unavailable',2); r=event('source-revocation',2)
    w=event('world-authority',0); m=event('world-mapping',1); c=event('common-hmd-pose',2)
    scenarios = {
        'source-normal': ([a,p,u,event('source-pose',3,**{'source.observation_id':'9007199254740994'})],['authority','pose','unavailable','pose']),
        'source-revoke-no-pose': ([a,p,r],['authority','pose','revoked']),
        'common-normal': ([w,m,c,event('common-hmd-unavailable',3),event('common-hmd-pose',4,**{'source.observation_id':'9007199254740994'})],['world','mapping','pose','unavailable','pose']),
        'common-revoke-no-pose': ([w,m,c,event('world-revocation',3)],['world','mapping','pose','revoked']),
        'mapping-revoke-no-pose': ([w,m,c,event('mapping-revocation',3)],['world','mapping','pose','revoked']),
        'gap': ([w,m,event('common-hmd-pose',3),event('world-revocation',4),event('common-hmd-pose',5)],['world','mapping','gap','closed','closed']),
        'duplicate-same': ([a,p,copy.deepcopy(p)],['authority','pose','duplicate']),
        'duplicate-resend': ([a,p,event('source-pose',1,sent_at_ns='9007199254740995')],['authority','pose','duplicate']),
        'duplicate-conflict': ([a,p,event('source-pose',1,position=[4,2,3]),event('source-pose',2)],['authority','pose','conflict','closed']),
        'reorder': ([a,p,copy.deepcopy(a),event('source-pose',2)],['authority','pose','old','pose']),
        'delayed-after-revoke': ([w,m,c,event('world-revocation',3),c,event('common-hmd-pose',4)],['world','mapping','pose','revoked','old','untrusted']),
        'pose-before-authority': ([event('source-pose',0)],['bootstrap-failed']),
        'common-before-mapping': ([w,event('common-hmd-pose',1)],['world','untrusted']),
        'stale-mapping-pose': ([w,m,c,event('world-mapping',3,**{'mapping.mapping_revision':8}),event('common-hmd-pose',4)],['world','mapping','pose','mapping','untrusted']),
        'common-mapping-update': ([w,m,c,event('world-mapping',3,**{'mapping.mapping_revision':8}),event('common-hmd-pose',4,**{'mapping.mapping_revision':8})],['world','mapping','pose','mapping','pose']),
        'common-world-replace': ([w,m,c,event('world-revocation',3),event('world-authority',4,**{'world.world_epoch':'world-B','world.coordinate_space.revision':2}),event('world-mapping',5,**{'mapping.world.world_epoch':'world-B','mapping.world.coordinate_space.revision':2,'mapping.calibration_epoch':'calibration-B'}),event('common-hmd-pose',6,**{'mapping.world.world_epoch':'world-B','mapping.world.coordinate_space.revision':2,'mapping.calibration_epoch':'calibration-B'})],['world','mapping','pose','revoked','world','mapping','pose']),
        'source-generation': ([a,p,event('source-authority',2,**{'source_space.source_space_generation':'1'}),event('source-pose',3,**{'source.source_space.source_space_generation':'1','source.observation_id':'9007199254740994'})],['authority','pose','authority','pose']),
        'fresh-session-after-gap': ([a,event('source-pose',2),event('source-authority',0,session_id=SECOND),event('source-pose',1,session_id=SECOND),p],['authority','gap','authority','pose','retired']),
        'source-revoked-token-replay': ([a,p,r,event('source-authority',3),event('source-pose',4)],['authority','pose','revoked','untrusted','untrusted']),
        'mapping-high-water-after-revoke': ([w,m,c,event('mapping-revocation',3),event('world-mapping',4,**{'mapping.calibration_epoch':'calibration-B'}),event('world-mapping',5,**{'mapping.calibration_epoch':'calibration-B','mapping.mapping_revision':8}),event('common-hmd-pose',6,**{'mapping.calibration_epoch':'calibration-B','mapping.mapping_revision':8})],['world','mapping','pose','revoked','untrusted','mapping','pose']),
        'retired-world-fresh-session': ([w,m,c,event('world-revocation',3),event('world-authority',0,session_id=SECOND),event('world-mapping',1,session_id=SECOND)],['world','mapping','pose','revoked','untrusted','untrusted']),
    }
    events=[w,m]+[event('common-hmd-pose',i,**{'source.observation_id':str(9007199254740993+i)}) for i in range(2,100)]
    events += [event('world-mapping',100,**{'mapping.mapping_revision':8}),event('common-hmd-pose',101,**{'mapping.mapping_revision':8,'source.observation_id':'9007199254741094'}),event('world-revocation',102)]
    scenarios['common-seq102-revoke-no-pose']=(events,['world','mapping']+['pose']*98+['mapping','pose','revoked'])
    mapping8=event('world-mapping',3,**{'mapping.mapping_revision':8})
    freshworld=event('world-authority',0,session_id=SECOND)
    freshmapping=event('world-mapping',1,session_id=SECOND,**{'mapping.mapping_revision':8})
    freshpose=event('common-hmd-pose',2,session_id=SECOND,**{'mapping.mapping_revision':8})
    scenarios['mapping-high-water-reconnect'] = ([w,m,c,mapping8,freshworld,event('world-mapping',1,session_id=SECOND),freshpose],['world','mapping','pose','mapping','world','untrusted','untrusted'])
    scenarios['mapping-conflict-reconnect'] = ([w,m,c,mapping8,freshworld,event('world-mapping',1,session_id=SECOND,**{'mapping.mapping_revision':8,'transform.translation_xyz':[1.0,0.0,0.0]}),freshpose],['world','mapping','pose','mapping','world','untrusted','untrusted'])
    scenarios['mapping-snapshot-reconnect'] = ([w,m,c,mapping8,freshworld,freshmapping,freshpose],['world','mapping','pose','mapping','world','mapping','pose'])
    scenarios['mapping-high-water-republication'] = ([w,m,c,mapping8,event('world-authority',4),event('world-mapping',5),event('common-hmd-pose',6)],['world','mapping','pose','mapping','world','untrusted','untrusted'])
    scenarios['source-ambiguous-revoke-generation'] = ([a,p,event('source-revocation',2,reason='source_space_ambiguous'),event('source-authority',3,**{'source_space.source_space_generation':'1'}),event('source-pose',4,**{'source.source_space.source_space_generation':'1','source.observation_id':'9007199254740994'})],['authority','pose','revoked','authority','pose'])
    scenarios['source-lost-token-new-generation'] = ([a,p,r,event('source-authority',3,**{'source_space.source_space_generation':'1'}),event('source-pose',4,**{'source.source_space.source_space_generation':'1'})],['authority','pose','revoked','untrusted','untrusted'])
    return scenarios

def generate():
    folder=R/'fixtures/v2.1'; folder.mkdir(parents=True,exist_ok=True)
    index=[]
    for n,p,e in cases():
        group='invalid' if e else 'valid'; path=folder/group/(n+'.json'); path.parent.mkdir(exist_ok=True)
        # Finite lexical 1e309 makes strict parsers return OutOfRange, rather than a non-JSON Infinity token.
        raw=json.dumps(p,indent=2).replace('Infinity','1e309')+'\n'
        path.write_text(raw,encoding='utf-8',newline='\n')
        index.append(dict(file=f'{group}/{n}.json',error=e,decoded=p if e is None else None))
    (folder/'index.json').write_text(json.dumps(index,indent=2)+'\n',newline='\n')
    si=[]
    for n,(events,outcomes) in streams().items():
        path=folder/'ordered-streams'/(n+'.jsonl'); path.parent.mkdir(exist_ok=True)
        path.write_text(''.join(json.dumps(p,separators=(',',':'))+'\n' for p in events),newline='\n')
        si.append(dict(file=f'ordered-streams/{n}.jsonl',outcomes=outcomes))
    (folder/'ordered-streams/index.json').write_text(json.dumps(si,indent=2)+'\n',newline='\n')

if __name__=='__main__': generate()
