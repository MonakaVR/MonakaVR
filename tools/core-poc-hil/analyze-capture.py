"""Summarize measured capture; no hardware acceptance thresholds or tuning claims."""
from pathlib import Path
import argparse,json

FIELDS=('timestampNanos','sequence','sourceId','sourceProvenance','raw6dofPose','rawImuOrientationXyzw',
        'rawIkPose','correctedIkPose','solvedIkPose','correctionOffsetMeters','correctionQuaternionXyzw',
        'state','transitionReason','transitionTimestampNanos','dwellElapsedNanos','dwellThresholdNanos',
        'hysteresisState','blendProgress','finalOutputPose','positionResidualMeters','angularResidualRadians',
        'positionStepMeters','angularStepRadians')

def analyze(path):
    records=[json.loads(s) for s in path.read_text(encoding='utf-8').splitlines()]
    frames=[r for r in records if r['type']=='frame'];events=[r for r in records if r['type']=='event']
    assert frames and records[0]['type']=='capture_start' and records[-1]['type']=='capture_stop'
    assert all(r['schema']=='monaka-core-poc-capture-v1' for r in records)
    assert all(all(k in f for k in FIELDS) for f in frames)
    assert all(a['timestampNanos']<b['timestampNanos'] and a['sequence']<b['sequence'] for a,b in zip(frames,frames[1:]))
    states=[];edges=[]
    for a,b in zip(frames,frames[1:]):
        if a['state']!=b['state']:
            edges.append(dict(before=a['state'],after=b['state'],timestampNanos=b['timestampNanos'],
                              positionStepMeters=b['positionStepMeters'],angularStepRadians=b['angularStepRadians']))
        if a['state']=='RECOVERY_BLEND' and b['state']=='RECOVERY_BLEND':
            assert b['blendProgress']>=a['blendProgress']
        if b['state']=='RECOVERY_BLEND': assert b['dwellElapsedNanos']>=b['dwellThresholdNanos']
    for f in frames:
        if not states or states[-1]!=f['state']:states.append(f['state'])
    def maximum(key, selected):return max((f[key] for f in selected if isinstance(f.get(key),(int,float))),default=None)
    def metrics(f):return {k:f[k] for k in ('timestampNanos','rawPositionResidualMeters','positionResidualMeters',
             'rawAngularResidualRadians','angularResidualRadians','finalPositionResidualMeters','finalAngularResidualRadians',
             'solvedPositionResidualMeters')}
    fallback=[f for f in frames if f['state']=='FALLBACK_IK'];blend=[f for f in frames if f['state']=='RECOVERY_BLEND']
    first_loss=next((i for i,f in enumerate(frames) if f['state']=='FALLBACK_IK'),None)
    candidate=next((f for f in frames if f['state']=='RECOVERY_DWELL'),None)
    restored=next((f for i,f in enumerate(frames) if first_loss is not None and i>first_loss and f['state']=='FULL_6DOF'),None)
    durations=[]
    for a,b in zip(edges,edges[1:]):
        if a['after'] in ('RECOVERY_DWELL','RECOVERY_BLEND'):
            durations.append(dict(phase=a['after'],startedAtNanos=a['timestampNanos'],endedAtNanos=b['timestampNanos'],
                                  durationNanos=b['timestampNanos']-a['timestampNanos'],endedIn=b['after']))
    config=records[0]['config']
    result=dict(frames=len(frames),events=len(events),stateSequence=states,transitions=edges,
        eventCounts={name:sum(e['event']==name for e in events) for name in sorted({e['event'] for e in events})},
        dwellSettingMs=config['dwellMs'],blendSettingMs=config['blendMs'],
        dwellMaxPhysicalSpanNanos=maximum('dwellElapsedNanos',frames),
        maxBlendOutputVelocityMetersPerSecond=maximum('outputVelocityMetersPerSecond',blend),
        maxBlendOutputAngularVelocityRadiansPerSecond=maximum('outputAngularVelocityRadiansPerSecond',blend),
        recoveryCancellationCount=sum(e['event'] in ('dwell_reset','recovery_blend_cancel') for e in events),
        stateChatterCount=sum(e['event'] in ('dwell_reset','recovery_blend_cancel') for e in events),
        fullToFallbackCount=sum(e['before']=='FULL_6DOF' and e['after']=='FALLBACK_IK' for e in edges),
        recoveryPhaseDurations=durations,
        beforeLoss=metrics(frames[first_loss-1]) if first_loss else None,
        firstFallback=metrics(fallback[0]) if fallback else None,lastFallback=metrics(fallback[-1]) if fallback else None,
        recoveryCandidate=metrics(candidate) if candidate else None,fullRestored=metrics(restored) if restored else None,
        final=metrics(frames[-1]),realHil=False)
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('capture',type=Path);p.add_argument('--out',type=Path)
    args=p.parse_args();result=analyze(args.capture);text=json.dumps(result,indent=2,allow_nan=False)+'\n'
    if args.out:args.out.write_text(text,encoding='utf-8')
    print(text)

if __name__=='__main__':main()
