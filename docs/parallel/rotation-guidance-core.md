# Body rotation guidance: pure Stage A1 core

The new `dev.monaka.tracking.correction` package is dormant. It neither replaces
Phase 1 nor registers a learner with the server. See [the audit](rotation-guidance-gap.md)
for the migration boundary and missing production evidence.

## API and responsibility

`BodyRotationGuidance.advance(previous, input, tuning)` returns `BodyRotationResult`
without mutating its arguments. Each body has its own `BodyRotationState`; a body
ID is opaque, with no HIP special case. The caller owns storage, serialization,
clock mapping and lifecycle. No tracker, server singleton, socket, filesystem or
clock read is performed here. `RotationGuidanceMath` contains the numerical model.
The existing `RotationCorrectionTuning` is reused, without new tuning defaults.

`GuidanceBinding` supplies the current explicit Main/IMU identities and epochs,
assignment revision and approved mounts, including during occlusion. Structured
identity compares publisher/source/tracker separately; a display name is insufficient.
`GuidanceFrames` approval names the exact Main and IMU identities/epochs, including
session, source/calibration epoch, mapping revision and space. Changing authority
or transforms revokes readiness. Reusing approval from another epoch is rejected.
An unexpected sample epoch also revokes readiness while authority is refreshed.
This conservative behavior never guesses that a new session uses old calibration.

`GuidanceTeacher` must have declared static Pos+Q support, current FULL modality,
usable finite position and orientation, fresh receipt and independent Main
acceptance evidence. ROTATION_ONLY/NONE cannot teach. `GuidanceImu` requires a
usable physical IMU acceptance receipt, whose identity, sequence, sample/receipt
time, epoch and orientation match the input. Generic Q-only, MTP pose orientation,
derived output and absent evidence are rejected. Output namespaces are checked
in every identity component, reusing `FeedbackExclusion`.

Evidence records are an input contract, **not authentication or production proof**.
The future trusted adapter must construct them from the physical acceptance
boundary. Filling a DTO from an MTP FULL orientation does not make it a physical
IMU. No such adapter is introduced in A1; the exact-space/mount assertions still
need physical calibration and HIL. All sample timestamps must already be mapped
once into the same monotonic domain; remote timestamps cannot be subtracted here.

`BodyRotationResult` reports separate teacher, IMU, learning and application reasons,
correction state, last learned time, residual and existing policy decision.
`correctedImu` is a diagnostic orientation available only with a ready correction
and a currently eligible IMU. `fallbackCandidate` additionally requires the caller's
resolver owner and the existing `MainFallbackPolicy` to agree on FALLBACK. During
usable FULL, Main retains ownership and no fallback candidate is emitted. No
position is generated. These values are not `ResolvedTrackingPose` or output writes.
A future integration may substitute one selected fallback component exactly once
and feed the existing continuity path; it must not apply correction to Main.

## Numerical model and time

Hamilton quaternion constructor order is **wxyz**; the API parses neither wire
xyzw nor configuration arrays. Explicit fixed transforms must refer to the
already normalized world input, avoiding duplicate Bridge coordinate transforms.

```text
q_main_body = normalize(q_world_tracker * q_tracker_body)
q_imu_body  = normalize(q_world_imu * q_imu_body_mount)
q_target    = normalize(q_main_body * inverse(q_imu_body))
q_corrected = normalize(q_corr * q_imu_body)
theta       = shortest_SO3_angle(q_corr, q_target)
alpha       = 1 - exp(-dt / tau)
beta        = min(alpha, maxRadiansPerSecond * dt / theta)
q_corr_next = normalize(slerp_shortest(q_corr, q_target, beta))
```

The zero-angle case returns the existing orientation. Double intermediates prevent
Float norm overflow, while results use the existing Float quaternion type. Zero,
near-zero norm (<1e-10), NaN and infinity are rejected; valid finite non-unit inputs
are normalized. Quaternion signs are equivalent. Shortest-path interpolation,
including near 180 degrees, never follows the long arc. The angle/rate bound is
subject to Float quantization, tested with numerical tolerances.

Both source sequences and sample times must advance for a new pair. The pair
clock is `max(main.sampleAt, imu.sampleAt)`; `dt` comes from consecutive admitted
new FULL physical pairs, never server time. Each source has an independent
watermark even if pairing fails. Duplicate sequences cannot change their receipt
or orientation; sign-equivalent quaternions are allowed. Receipt time cannot be
before sample time, after now, or roll back for a newer accepted sample. Caller
time rollback rejects the operation with unchanged state and no candidate.

The first qualified pair establishes a baseline and does not update correction.
Only subsequent qualified pairs can learn. Residual outliers interrupt dwell and
do not train; excessive dt establishes a new baseline without integrating the gap.
Missing/stale/invalid sources and pair-window failures interrupt dwell without
discarding the learned correction. Repeated pairs advance neither dwell nor dt.
During reacquisition, fresh accepted pairs must span dwell and satisfy the residual
threshold for the configured count. `ready` retains same-epoch qualification during
loss/reacquisition; phase can be REACQUIRING while ready remains true. Only current
physical IMU validity permits application. Total loss yields no Q or position.

## Threshold meanings (all deployment values remain provisional)

| Existing tuning field | Meaning in this core |
|---|---|
| fullStableNanos | New qualified FULL pair timestamps must span this dwell |
| pairWindowNanos | Maximum Main/IMU sample-time skew for training; inclusive |
| trackingTauSeconds | Exponential response time while TRACKING |
| recoveryTauSeconds | Exponential response time during initial/reacquisition learning |
| maxResidualRadians | Reject teacher-vs-corrected-IMU residual above this angle |
| recoveryResidualRadians | Post-update residual limit for a recovery-qualified pair |
| maxRadiansPerSecond | Maximum accepted SO(3) correction change divided by pair dt |
| recoveryPairs | Number of consecutive recovery-qualified new pairs after dwell |
| maxDtNanos | Maximum admitted learning interval; inclusive, gap is not integrated |
| maxImuSampleAgeNanos | Physical IMU and teacher maximum age; inclusive |

No values are declared optimal. Timestamp skew/jitter, acceptance-to-measurement
delay, frame/mount error, physical IMU bias, teacher error and Float/measurement
quantization remain error sources. A pair window bounds timestamp skew, not true
hardware synchronization. Numerical rate bounds do not establish real-device
latency, drift or angular-rate capacity.

## Validation scope

Deterministic tests cover HIP and LEFT_FOOT with identical math, independent
states, FULL/ROTATION_ONLY/NONE/FULL/re-loss, receipt and sequence integrity,
freshness, epochs/approval, provenance, capability/validity gates, total loss,
feedback, deterministic replay, noncommuting transforms, shortest arc, normalization,
rate/tau limits, outliers, dt/pair-window boundaries and accepted-sample dwell.
The full Core suite includes the unchanged HipRotationCorrectionTests,
RecoveryAngularDynamicsTests, HybridFoundationTests and MainFallbackPolicyTests.
Execution receipts and actual counts are recorded in rotation-guidance-validation.md.

Stage A2 production supplier/wiring: DEFERRED. Production activation: OFF.
Hardware calibration, drift, latency, SteamVR quality and physical HIL: NOT RUN.
