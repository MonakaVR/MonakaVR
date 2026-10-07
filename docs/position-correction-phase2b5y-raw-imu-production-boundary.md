# Phase 2B-5Y — Raw IMU Production Boundary

`SlimeRawImuProductionBoundary` is an internal, dormant production value adapter.
It produces `RawImuOrientationInput` from a physical HIP IMU and caller-supplied
`SlimeRawImuCoordinateSpaceBinding`. There is no runtime registration or consumer.
The existing input and prediction epoch contracts remain unchanged.

RAW_IMU means independent physical IMU orientation captured before solver,
Main-derived correction and visible output, with only explicit fixed mount/reset
calibration. It need not be the untouched sensor quaternion.

`Tracker.setRotation` is the orientation acceptance point, advancing sequence
and recording `System.nanoTime()` receipt time. `dataTick`, heartbeat, tick and
poll do not advance it. Shared `SlimeIndependentImuOrientationCapture` reads
`getCorrectionReferenceRotationFrom(getRawRotation())`. It retains, in existing
order, `mountingOrientation`, `gyroFix`, `attachmentFix`, `mountRotFix` conjugation,
`tposeDownFix` and `yawFix`.

The reference transform is `adjustToReference(rotation, false)`: `constraintFix`
is excluded and `adjustToDrift` is not called. The boundary reads no Stay Aligned
yaw, filtering, yaw reset smoothing, learned Phase 1 `q_corr`, Main observation,
resolver, IK, computed tracker or output rotation. Fixed reset calibration may
have an explicitly established reference; no Main reference is dynamically
acquired or learned here. Zero, tiny, NaN, Inf and overflowed quaternions reject
without identity substitution. Valid non-unit results are normalized only at
the new boundary; existing observation numerical semantics are retained.

Capture holds the reset-handler monitor and then the tracker monitor. Fixed
calibration writers (manual mounting setter, full/yaw/mount reset, clear mounting
and saved mounting reset) hold the reset-handler monitor. `setRotation` holds
the tracker monitor through raw value and receipt publication. This closes the
raw-publication gap and prevents partial fixed calibration capture. Before/after
sample/source/calibration checks still fail closed, including reentrant changes.
The synchronization introduces no new epoch and changes no transform.

Receipt mapping remains `age = receiptClock() - receivedAtSystemNanos`,
`sampleAtNanos = observedAtNanos - age`. Negative/unmappable age rejects. A weak-key
per-tracker sequence cache maps each sample once per capture instance. Repeated
polls retain sequence/time. Both adapters use the helper; callers needing one
mapping across both paths share `SlimeTrackerPoseObservationAdapter.independentImuCapture`
with the new boundary. Capture's receipt clock must be the physical system receipt
clock; observedAt is the runtime monotonic domain.

The source ID follows the existing prefix convention, normally `slime:<name>`.
Eligibility requires device, IMU, rotation capability, HIP assignment, noninternal,
noncomputed and non-HMD. Output namespaces are checked on the original tracker
name and final source ID before prefixing can hide them. `OK` is tracked; `BUSY`
and rotation-only `OCCLUDED` are degraded. `TIMED_OUT`, `ERROR`, `DISCONNECTED`
and modality NONE reject. Existing FULL/OCCLUDED remains LOST. No new sample-age
default is introduced: future assembly/predictor policy owns freshness. HIP is
only a structural gate; future assembly must bind current assignment generation
and source selection.

The independent binding supplies source ID, exact CoordinateSpace and confirmation.
It requires a confirmed, nonblank source matching the actual source, nonblank
space ID, nonnegative revision and `rh_y_up_neg_z_forward` convention. Invalid
bindings return typed rejection. This means the caller explicitly established
the fixed-reference orientation's physical space interpretation. It is **not
provider cryptographic proof, automatic frame detection, or Strong Trusted
provenance**. It does not depend on Phase 1 configuration or feature enablement.
No canonical space is inferred automatically from Slime.

Input and provenance receive the same exact space. Observation provenance space
stays null. Slime mappingRevision stays null in both paths; no binding counter
is fabricated as a mapping revision. Changed physical interpretation requires
the caller to update appropriate CoordinateSpace ID/revision; supported convention
remains fixed.

Provenance retains accepted sequence, mapped receipt time, correctionSourceEpoch
(tracker observation instance plus reconnect generation), and correctionCalibrationEpoch
(reset generation plus exact six fixed quaternions). Handshake reuse, status
reconnect and same-name tracker replacement change source lineage. Mount/reset
changes calibration lineage even at equal sensor quaternion. Dynamic corrections
do not enter calibration identity. MainDecoupledHipInput.epoch retains IMU source
ID/source epoch/calibration epoch/null mapping and common exact space. Reconnect,
mount/reset and space changes alter prediction lineage. Sequence, sample time
and numeric orientation progression alone do not.

Dedicated tests use actual Tracker/UDPDevice fixtures and the production boundary.
Synthetic HMD, immutable body snapshot and explicit bound Fixed Calibration
numerical fixtures (5Z) verify epoch handoff without establishing production HMD
capability or physical calibration acquisition. Existing PoseObservation position, rotation, modality, quality,
correctionRotation and timing, Phase 1 tests and full software suite are regression
gates. Public constraint feedback, Stay Aligned, filtering and learned q_corr
changes are tested; drift exclusion is additionally established by source audit.

Raw IMU assembly, predictor, temporal pairing and Position Correction runtimes
and correction IK writeback are **NOT CONNECTED**. Predictor algorithm, learner
and correction law are **NOT IMPLEMENTED**. Fixed Calibration is now a **DORMANT NUMERICAL SNAPSHOT** in [5Z](position-correction-phase2b5z-fixed-calibration-numerical-foundation.md). Its HMD-to-HEAD relation is independent of IMU mount/reset and does not change IMU provenance.
OpenVR HMD is **POSE_ONLY**, Strong Trusted **UNSUPPORTED**, production RawHmdPoseInput
**BLOCKED BY BACKEND**, 2B-5P **NOT READY**. IMU space confirmation does not relax
HMD policy. 5S HIL remains pending. HIL here is **NOT REQUIRED / NOT RUN**, because
this dormant value boundary has no runtime consumer. Software E2E is no physical
tracking proof.

The recommended next phase is **Phase 2B-5Z — Fixed Calibration Numerical
Foundation**: independent Raw IMU and immutable body boundaries now exist, while
FixedCalibrationIdentity still contains no numerical geometry. Trusted HMD
backend, predictor algorithm, runtime assembly, learner, correction law and 5S
HIL remain deferred.

Fresh tests, audit, protected-patch and Git evidence:
`build/reports/phase2b5y-raw-imu-production-boundary-20261007/report.md`.

Phase 2B-5Z completes that numerical foundation; the historical 5Y next-phase recommendation above is satisfied. The fixed snapshot contains no IMU transform or provider/world-frame calibration, and acquisition, persistence/UI and runtime assembly remain deferred.
