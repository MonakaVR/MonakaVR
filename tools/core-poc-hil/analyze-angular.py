"""Unsmooth, independent Hamilton xyzw log-map audit of a synchronized Core capture.

Output omega is a world-frame interval average: log(q_now * inverse(q_prev))/dt.
Native MTP angular velocity is retained separately, never substituted for output.
No product threshold or dt clamp is applied. Long dt is flagged at >100ms.
"""
from pathlib import Path
import argparse, csv, json, math, statistics


def norm(v):
    return math.sqrt(sum(x*x for x in v))


def unit(q):
    n = norm(q)
    if not n or not all(math.isfinite(x) for x in q):
        raise ValueError('Nonfinite/zero quaternion')
    return [x/n for x in q]


def mul(a, b):
    x,y,z,w = a; X,Y,Z,W = b
    return [w*X+x*W+y*Z-z*Y, w*Y-x*Z+y*W+z*X,
            w*Z+x*Y-y*X+z*W, w*W-x*X-y*Y-z*Z]


def derivative(prev, current, dt):
    a,b = unit(prev),unit(current)
    dot = sum(x*y for x,y in zip(a,b))
    if dot < 0:
        b = [-x for x in b]
    d = unit(mul(b,[-a[0],-a[1],-a[2],a[3]]))
    if d[3] < 0:
        d = [-x for x in d]
    s = norm(d[:3])
    angle = 2*math.atan2(s,max(0,d[3]))
    omega = [x*angle/s/dt for x in d[:3]] if s and dt and dt>0 else ([0.,0.,0.] if dt and dt>0 else None)
    return dict(angleRadians=angle,quaternionDot=dot,hemisphereFlip=dot<0,
                worldAngularVelocityRadPerSec=omega,
                angularSpeedRadPerSec=angle/dt if dt and dt>0 else None)


def angle(a,b):
    return derivative(a,b,1)['angleRadians'] if a is not None and b is not None else None


def read(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def audit(run, out):
    records = read(run/'core-live-session/readiness-and-primary.jsonl')
    frames = [r for r in records if r['type']=='frame']
    roots = {r['sequence']:r for r in read(run/'root-observations.jsonl')}
    native = {r['receivedAtNanos']:r for r in read(run/'live-mtp-native.jsonl')}
    mark = next((r['timestampNanos'] for r in records if r['type']=='mark' and r['note']=='P0_PICO_FULL_BASELINE'),None)
    timeline=[]; previous=None; previous_raw=None
    for f in frames:
        root=roots[f['sequence']]
        gt=root.get('rawPicoCaptureOnly')
        if gt:
            envelope=native[gt['receivedAtNanos']]['envelope']
            receipt=gt['receivedAtNanos']; sample=gt['sampleAtNanos']
            raw_q=gt['quaternionXyzw']; raw_p=gt['positionMeters']
            submitted=root['coreMainPoseSubmitted']
        else:
            envelope=f['sourceNativeEnvelope']
            receipt=next((n['receivedAtNanos'] for n in native.values() if n['envelope']==envelope),None)
            sample=f['sixDofTimestampNanos']
            raw_q=envelope.get('orientation');raw_p=envelope.get('position')
            submitted=True
        q=f['finalOutputPose']['quaternionXyzw']
        dt=(f['timestampNanos']-previous['timestampNanos'])/1e9 if previous else None
        deriv=derivative(previous['finalOutputPose']['quaternionXyzw'],q,dt) if previous else None
        raw_dt=(sample-previous_raw['sample'])/1e9 if previous_raw else None
        raw_deriv=derivative(previous_raw['q'],raw_q,raw_dt) if previous_raw and previous_raw['q'] and raw_q else None
        fallback_q=f['correctedIkPose']['quaternionXyzw']
        fallback_deriv=derivative(previous['correctedIkPose']['quaternionXyzw'],fallback_q,dt) if previous else None
        source_dt=(int(envelope['timestamp_ns'])-previous_raw['sourceTimestamp'])/1e9 if previous_raw else None
        flags=[]
        if dt is not None and dt<=0:flags.append('NON_POSITIVE_CORE_DT')
        if dt is not None and dt>.1:flags.append('LONG_CORE_DT_OVER_100MS')
        if raw_dt is not None and raw_dt<=0:flags.append('NON_ADVANCING_ADMITTED_RAW_SAMPLE')
        if source_dt is not None and source_dt<=0:flags.append('NON_ADVANCING_SOURCE_TIMESTAMP')
        if previous and f['sequence']<=previous['sequence']:flags.append('NON_ADVANCING_FRAME_SEQUENCE')
        if previous and 'derivativePreviousFrameSequence' in f:
            if f['derivativePreviousFrameSequence']!=previous['sequence']:flags.append('DERIVATIVE_HISTORY_SEQUENCE_MISMATCH')
            if f['derivativePreviousTimestampNanos']!=previous['timestampNanos']:flags.append('DERIVATIVE_HISTORY_TIMESTAMP_MISMATCH')
            if f['derivativePreviousQuaternionXyzw']!=previous['finalOutputPose']['quaternionXyzw']:flags.append('DERIVATIVE_HISTORY_QUATERNION_MISMATCH')
            if f['derivativeDtSeconds']!=dt:flags.append('DERIVATIVE_DT_MISMATCH')
        row=dict(timestampNanos=f['timestampNanos'],captureTimestampNanos=f['timestampNanos'],sequence=f['sequence'],
            state=f['state'],previousState=previous['state'] if previous else None,
            sixdof_valid=f['sixDofValid'],gateAvailable=f['gateAvailable'],coreMainPoseSubmitted=submitted,
            rawPicoPositionMeters=raw_p,rawPicoQuaternionXyzw=raw_q,
            rawPicoAngularVelocityRadPerSec=envelope.get('angular_velocity'),
            rawPicoAngularSpeedRadPerSec=norm(envelope['angular_velocity']) if envelope.get('angular_velocity') else None,
            rawVelocityProvenance='PICO native velocity after Bridge canonical mapping; same-tracker IMU semantics NOT VERIFIED',
            rawVelocityFrame='Mapped MTP canonical carrier; native angular frame authenticity NOT VERIFIED (angular_space_verified=false). Magnitude comparison only.',
            sourceTimestampNanos=int(envelope['timestamp_ns']),sourceSentAtNanos=int(envelope['sent_at_ns']),
            sourceTimestampKind=envelope.get('timestamp_kind'),sourceClockId=envelope['clock_id'],
            sourceSequence=int(envelope['sequence']),nativeSequence=int(envelope['input']['sequence']),
            sourceTimestampDtSeconds=source_dt,rawAdmittedSampleAtNanos=sample,rawReceivedAtNanos=receipt,
            sourceSampleAgeNanos=f['timestampNanos']-sample,coreMainSampleAtNanos=f['sixDofTimestampNanos'],
            rawReconstructedAngularVelocity=raw_deriv,
            fallbackInputQuaternionXyzw=f['rawImuOrientationXyzw'],fallbackInputAngularVelocityRadPerSec=[0,0,0],
            fallbackInputProvenance='Synthetic constant identity; no real IMU claim',
            fallbackQuaternionXyzw=fallback_q,fallbackQuaternionDefinition='Corrected rotation fallback selected on loss; not solved IK rotation',
            fallbackDerivedAngularVelocity=fallback_deriv,correctedIkQuaternionXyzw=fallback_q,
            solvedIkQuaternionXyzw=f['solvedIkPose']['quaternionXyzw'],
            finalOutputPositionMeters=f['finalOutputPose']['positionMeters'],finalOutputQuaternionXyzw=q,
            reportedFinalAngularSpeedRadPerSec=f.get('outputAngularVelocityRadiansPerSecond'),
            reportedFinalAngularVelocityVector=None,reportedVelocityProvenance=f.get('angularVelocityProvenance','Core diagnostic scalar acos(abs(float unit quaternion dot))/Core tick dt')+'; no production output omega vector available',
            reconstructedFinalAngularVelocity=deriv,dtSeconds=dt,dtUsedByReportedDerivativeSeconds=f.get('derivativeDtSeconds',dt),
            previousTimestampNanos=previous['timestampNanos'] if previous else None,
            previousQuaternionXyzw=f.get('derivativePreviousQuaternionXyzw',previous['finalOutputPose']['quaternionXyzw'] if previous else None),
            previousQuaternionProvenance='Core explicit field' if 'derivativePreviousQuaternionXyzw' in f else 'Exact previous captured Core frame; source path verified',
            previousFrameSequence=previous['sequence'] if previous else None,
            dwellElapsedNanos=f['dwellElapsedNanos'],dwellThresholdNanos=f['dwellThresholdNanos'],
            dwellState=f['hysteresisState'],blendAlpha=f['blendProgress'],
            finalAngularResidualToRawRadians=angle(q,raw_q),correctedIkAngularResidualToRawRadians=angle(fallback_q,raw_q),
            finalQuaternionNorm=norm(q),flags=flags,primary=mark is not None and f['timestampNanos']>=mark)
        if deriv and row['reportedFinalAngularSpeedRadPerSec'] is not None:
            row['reportedMinusReconstructedRadPerSec']=row['reportedFinalAngularSpeedRadPerSec']-deriv['angularSpeedRadPerSec']
        timeline.append(row)
        previous=f;previous_raw=dict(q=raw_q,sample=sample,sourceTimestamp=int(envelope['timestamp_ns']))
    primary=[r for r in timeline if r['primary']]
    loss=next((r for r in primary if r['state']=='FALLBACK_IK'),None)
    dwell=next((r for r in primary if r['state']=='RECOVERY_DWELL'),None)
    blend=next((r for r in primary if r['state']=='RECOVERY_BLEND'),None)
    full=next((r for r in primary if dwell and r['state']=='FULL_6DOF' and r['timestampNanos']>dwell['timestampNanos']),None)
    recovery=[r for r in primary if dwell and r['timestampNanos']>=dwell['timestampNanos'] and (not full or r['timestampNanos']<=full['timestampNanos'])]
    def peak(rows,key):return max((r[key] for r in rows if r.get(key) is not None),default=None)
    def r_speed(r):return (r.get('reconstructedFinalAngularVelocity') or {}).get('angularSpeedRadPerSec')
    ranked=sorted((r for r in recovery if r_speed(r) is not None),key=r_speed,reverse=True)
    boundaries=[]
    for i,r in enumerate(primary):
        if r['state']!=r['previousState']:
            boundaries.append(dict(before=r['previousState'],after=r['state'],sequence=r['sequence'],
                neighbors=primary[max(0,i-1):i+2]))
    summary=dict(run=str(run),primaryStarted=mark is not None,complete=full is not None,
        frameCount=len(timeline),primaryFrameCount=len(primary),maskedRawCaptureOnlyFrames=sum(not r['coreMainPoseSubmitted'] for r in primary),
        peakReportedRecoveryRadPerSec=peak(recovery,'reportedFinalAngularSpeedRadPerSec'),
        peakReconstructedRecoveryRadPerSec=max((r_speed(r) for r in recovery if r_speed(r) is not None),default=None),
        peakRawPicoRecoveryRadPerSec=peak(recovery,'rawPicoAngularSpeedRadPerSec'),
        maxRecoveryQuaternionStepRadians=max((r['reconstructedFinalAngularVelocity']['angleRadians'] for r in ranked),default=None),
        minRecoveryQuaternionDot=min((r['reconstructedFinalAngularVelocity']['quaternionDot'] for r in ranked),default=None),
        minDtSeconds=min((r['dtSeconds'] for r in primary if r['dtSeconds'] is not None),default=None),
        maxDtSeconds=peak(primary,'dtSeconds'),flags=[dict(sequence=r['sequence'],flags=r['flags']) for r in primary if r['flags']],
        recoveryHemisphereFlipCount=sum(r['reconstructedFinalAngularVelocity']['hemisphereFlip'] for r in ranked),
        blendStartResidualRadians=blend['finalAngularResidualToRawRadians'] if blend else None,
        blendEndResidualRadians=full['finalAngularResidualToRawRadians'] if full else None,
        dwellMs=(blend['timestampNanos']-dwell['timestampNanos'])/1e6 if blend and dwell else None,
        blendMs=(full['timestampNanos']-blend['timestampNanos'])/1e6 if full and blend else None,
        peakFrames=ranked[:5],boundaries=boundaries,
        method='Hamilton log(q_current * inverse(q_previous)), normalized double, q/-q shortest arc, actual Core dt, no smoothing/clamping',
        authenticity='PICO same-tracker IMU authenticity: NOT VERIFIED / DEFERRED')
    out.mkdir(exist_ok=True,parents=True)
    (out/'angular-timeline.jsonl').write_text(''.join(json.dumps(r,allow_nan=False)+'\n' for r in timeline),encoding='utf-8')
    (out/'angular-audit.json').write_text(json.dumps(summary,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    fields=['timestampNanos','sequence','state','previousState','dtSeconds','sourceSampleAgeNanos','sourceSequence','nativeSequence','dwellElapsedNanos','blendAlpha','reportedFinalAngularSpeedRadPerSec','reconstructedRadPerSec','rawPicoAngularSpeedRadPerSec','stepRadians','dot','finalAngularResidualToRawRadians','correctedIkAngularResidualToRawRadians','flags']
    with (out/'angular-comparison.csv').open('w',newline='',encoding='utf-8') as file:
        writer=csv.DictWriter(file,fieldnames=fields);writer.writeheader()
        for r in primary:
            d=r['reconstructedFinalAngularVelocity'] or {}
            v={k:r.get(k) for k in fields};v.update(reconstructedRadPerSec=d.get('angularSpeedRadPerSec'),stepRadians=d.get('angleRadians'),dot=d.get('quaternionDot'))
            writer.writerow(v)
    return {k:v for k,v in summary.items() if k not in ('peakFrames','boundaries')}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('run',type=Path);parser.add_argument('out',type=Path)
    args=parser.parse_args();print(json.dumps(audit(args.run.resolve(),args.out.resolve()),indent=2))
