# Body rotation guidance audit (Stage A1)

BASE_SHA: `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589` (origin/dev, fetched 2026-10-21 JST).
Branch: `feature/monaka-body-rotation-guidance`.

## Audit before implementation

Read Phase 1, Hybrid foundation/vertical slice and Phase 2B-6L documentation;
HipRotationCorrection and its tests; AssignedImuSampleFreshness;
SlimeIndependentImuOrientationCapture; SlimeRawImuProductionBoundary;
ObservationSampleProvenance; TrackerBodyAssignments; ResolvedTrackingPose;
ConstraintResolver; MainFallbackPolicy; FeedbackExclusion.

Phase 1 already separates learning from application, uses physical acceptance
sequences rather than ticks, retains calibration through loss, bounds quaternion
updates and applies world-side correction once after resolver selection. It is
HIP-specific at output and freshness admission, and permits ROTATION_ONLY teachers.
The new contract requires FULL teachers only. ObservationSampleProvenance alone
does not prove physical origin or declare static Pos+Q capabilities. Reusing the
Phase 1 public update API would therefore weaken the requested admission gate.

Within this write boundary, reuse RotationCorrectionTuning and the existing
MainFallbackPolicy/RotationOwner. Introduce a pure immutable state transition and
a separate numerical function under tracking/correction. It accepts explicit
assignment, exact epoch/frame approval, accepted sample and independent receipt
evidence. This is a stricter successor seam, not another active runtime learner.
Do not copy HipRotationCorrection or connect both learners. A later PR should
adapt the Phase 1 caller to this seam, compare old/new traces, and retire its
learner after deciding the intentional ROTATION_ONLY behavior change. That
requires edits outside the present boundary and is deferred.

## Production evidence / Stage A2 gate

Tracker.setRotation and SlimeIndependentImuOrientationCapture provide real
acceptance sequence, mapped sample time, source and calibration epochs and an
orientation excluding constraintFix/Stay Aligned/drift compensation. The dormant
SlimeRawImuProductionBoundary additionally checks device/isImu/internal/computed
and requires an explicit exact-space assertion, but rejects non-HIP targets.
Its binding is an operator assertion, not physical calibration proof.

MtpPoseAdapter uses wire xyzw -> Quaternion(w,x,y,z) and supplies accepted sequence,
sample time, session/clock, input session, mapping and space. Capability evidence
is held separately in MtpObservationBackend. Ordinary PoseObservation does not
carry all receipt/physical-origin/frame approval facts needed by this new API.
No complete production supplier for approved non-HIP frame bindings plus both
independent receipts has been demonstrated. A data type or a synthetic fixture
does not establish that supplier. Stage A2 runtime wiring proposal: DEFERRED.

No config, assignment registry, server integration, startup flag, solver, output
identity/body role, body lengths, bridge, backend, GUI or wire changes are needed
for A1. Production Position Correction remains OFF. HIL: NOT RUN.
