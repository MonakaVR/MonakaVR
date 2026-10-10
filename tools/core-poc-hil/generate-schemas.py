"""Build explicit local diagnostic carrier and capture schemas; wire envelopes use the pinned codec."""
from pathlib import Path
import json

ROOT=Path(__file__).resolve().parent
SCHEMA='https://json-schema.org/draft/2020-12/schema'
vec={'type':['array','null'],'items':{'type':'number'},'minItems':3,'maxItems':3}
quat={'type':['array','null'],'items':{'type':'number'},'minItems':4,'maxItems':4}
pose={'type':'object','required':['positionMeters','quaternionXyzw'],
      'properties':{'positionMeters':vec,'quaternionXyzw':quat},'additionalProperties':False}
provenance={'type':'object','required':['sequence','sampleAtNanos','sourceEpoch','calibrationEpoch','mappingRevision','commonWorldEpoch'],
            'properties':{'sequence':{'type':'integer','minimum':0},'sampleAtNanos':{'type':'integer','minimum':0},
              'sourceEpoch':{'type':'string','minLength':1},'calibrationEpoch':{'type':'string','minLength':1},
              'mappingRevision':{'type':['integer','null']},'commonWorldEpoch':{'type':['string','null']}}}
required=['timestampNanos','sequence','sourceId','sourceProvenance','imuProvenance','hmdProvenance','world',
          'raw6dofValid','sixDofValid','gateAvailable','raw6dofPose','rawImuOrientationXyzw','rawIkPose','correctedIkPose',
          'solvedIkPose','correctionOffsetMeters','correctionQuaternionXyzw','positionCorrectionState','rotationCorrectionState',
          'state','nativeContinuityState','transitionReason','transitionTimestampNanos','dwellElapsedNanos','dwellThresholdNanos',
          'hysteresisState','blendProgress','finalOutputPose','outputPath','positionResidualMeters','angularResidualRadians',
          'positionStepMeters','angularStepRadians','rawPositionResidualMeters','rawAngularResidualRadians',
          'finalPositionResidualMeters','finalAngularResidualRadians','solvedPositionResidualMeters',
          'outputVelocityMetersPerSecond','outputAngularVelocityRadiansPerSecond']
props={key:{} for key in required}
for key in ['raw6dofPose','rawIkPose','correctedIkPose','solvedIkPose','finalOutputPose']:props[key]=pose
for key in ['sourceProvenance','imuProvenance','hmdProvenance']:props[key]=provenance
props['state']={'enum':['FULL_6DOF','FALLBACK_IK','RECOVERY_DWELL','RECOVERY_BLEND','UNAVAILABLE']}
for key in ['sixDofValid','raw6dofValid','gateAvailable']:props[key]={'type':'boolean'}
for key in ['rawImuOrientationXyzw','correctionQuaternionXyzw']:props[key]=quat
props['correctionOffsetMeters']=vec
for key in ['timestampNanos','sequence','transitionTimestampNanos','dwellElapsedNanos','dwellThresholdNanos']:props[key]={'type':'integer','minimum':0}
for key in [k for k in required if k.endswith(('Meters','Radians','PerSecond')) and k not in ('correctionOffsetMeters',)]:props[key]={'type':['number','null'],'minimum':0}
props['blendProgress']={'type':['number','null'],'minimum':0,'maximum':1}
capture={'$schema':SCHEMA,'title':'Monaka Core PoC capture v1','type':'object','required':['type','schema'],
 'properties':{'type':{'enum':['capture_start','frame','event','mark','capture_stop']},'schema':{'const':'monaka-core-poc-capture-v1'}},
 'allOf':[{'if':{'properties':{'type':{'const':'frame'}}},'then':{'required':required,'properties':props}},
          {'if':{'properties':{'type':{'const':'event'}}},'then':{'required':['event','timestampNanos','sequence','stateBefore','stateAfter','reason','sixDofValid','positionResidualMeters','angularResidualRadians','positionStepMeters','angularStepRadians'],
           'properties':{'event':{'enum':['6dof_valid','6dof_lost','fallback_entered','recovery_candidate','dwell_start','dwell_reset','recovery_blend_start','recovery_blend_cancel','full_restored','correction_updated']}}}},
          {'if':{'properties':{'type':{'const':'capture_start'}}},'then':{'required':['config','stateMachineVersion','hilMode','gateAvailable']}},
          {'if':{'properties':{'type':{'const':'mark'}}},'then':{'required':['note','timestampNanos']}}]}
sample={'type':'object','required':['sourceId','sequence','sampleAtNanos','sourceEpoch','calibrationEpoch','quaternionXyzw','valid'],
 'properties':{'sourceId':{'type':'string','minLength':1},'sequence':{'type':'integer','minimum':0},
 'sampleAtNanos':{'type':'integer','minimum':0},'sourceEpoch':{'type':'string','minLength':1},'calibrationEpoch':{'type':'string','minLength':1},
 'positionMeters':vec,'quaternionXyzw':quat,'valid':{'type':'boolean'},'mappingRevision':{'type':['integer','null']},
 'commonWorldEpoch':{'type':['string','null']}},'additionalProperties':False}
source={'$schema':SCHEMA,'title':'Core Common Pose diagnostic input (not a wire codec)','type':'object',
 'required':['timestampNanos','sequence','imu','hmd'],'properties':{'timestampNanos':{'type':'integer','minimum':0},
 'sequence':{'type':'integer','minimum':0},'main':sample,'imu':sample,'hmd':sample,
 'mainMtp':{'type':'object','$comment':'Unchanged envelope; validated by actual hash-pinned MonakaCodec, not this carrier schema'},
 'mainReceivedAtNanos':{'type':'integer','minimum':0}},'oneOf':[{'required':['main'],'not':{'required':['mainMtp']}},
 {'required':['mainMtp','mainReceivedAtNanos'],'not':{'required':['main']}}],'additionalProperties':False}
for name,data in [('capture-schema.json',capture),('input-schema.json',source)]:
 (ROOT/name).write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
print('Generated capture and input schemas')
