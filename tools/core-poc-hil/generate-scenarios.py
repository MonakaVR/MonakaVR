"""Deterministic Common Pose diagnostic inputs. No device, receiver or network interaction."""
from pathlib import Path
import argparse, json, math

def quaternion(yaw): return [0, math.sin(yaw/2), 0, math.cos(yaw/2)]

def scenario(name):
    sequence=0
    def tick(drift=.4, offset=0, angle=0):
        nonlocal sequence
        sequence+=1; at=1_000_000_000+sequence*20_000_000
        def sample(source, epoch, position, yaw, valid=True):
            return dict(sourceId='hil:synthetic:'+source, sourceEpoch=epoch+':1', calibrationEpoch='cal:1',
                        mappingRevision=0, commonWorldEpoch='world:1', sequence=sequence, sampleAtNanos=at,
                        positionMeters=position, quaternionXyzw=quaternion(yaw), valid=valid)
        return dict(op='input',frame=dict(timestampNanos=at,sequence=sequence,
            main=sample('main','main',[.1+offset,1.1,0],angle),
            imu=sample('imu','imu',None,drift), hmd=sample('hmd','hmd',[drift*.25,1.7,0],0)))
    yield dict(op='mark', note=name)
    for i in range(100): yield tick(drift=i*.004)
    yield dict(op='6dof-valid', value='off')
    fallback_count=1 if name=='short-dropout' else 20
    for i in range(fallback_count): yield tick(drift=.4+i*.004)
    recovery_drift=.4+(fallback_count-1)*.004
    offset=.01 if name=='small-residual' else 1 if name=='large-residual' else .2
    if name=='chatter':
        for _ in range(8):
            yield dict(op='6dof-valid',value='on')
            for _ in range(3): yield tick(drift=recovery_drift,offset=offset,angle=.5)
            yield dict(op='6dof-valid',value='off'); yield tick(drift=recovery_drift,offset=offset,angle=.5)
    yield dict(op='6dof-valid',value='on')
    if name in ('dwell-reloss','blend-reloss'):
        for _ in range(4 if name=='dwell-reloss' else 15): yield tick(drift=recovery_drift,offset=offset,angle=.5)
        yield dict(op='6dof-valid',value='off')
        for _ in range(6): yield tick(drift=recovery_drift,offset=offset,angle=.5)
        yield dict(op='6dof-valid',value='on')
    for _ in range(50): yield tick(drift=recovery_drift,offset=offset,angle=.5)

NAMES=('full-cycle','short-dropout','chatter','dwell-reloss','blend-reloss','large-residual','small-residual')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    for name in NAMES:
        with (args.out/(name+'.jsonl')).open('w',encoding='utf-8',newline='\n') as f:
            for request in scenario(name): f.write(json.dumps(request,sort_keys=True,separators=(',',':'))+'\n')
    print('Generated 7 deterministic scenarios')

if __name__=='__main__': main()
