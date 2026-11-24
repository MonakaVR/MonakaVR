# Normal input and correction semantics (Issue17)

New MTP configurations use version 5. The normal desktop startup reads
`monaka-mtp.json` beside the selected server configuration, or from the explicit
`monaka.mtp.config` property. Enable MTP through the existing opt-in. A new
configuration may start with an empty assignment catalog for selection in the
normal input UI.

```json
{
  "version": 5,
  "convention": "rh_y_up_neg_z_forward",
  "assignments": []
}
```

Normal C3 MTP is already expressed in Standing coordinates. The consumer accepts
pose and tracker state through the verified C3 codec and maintains a shared
sequence watermark per publisher, source, tracker and session. It keeps the
original acquisition time on a geometry re-expression. Present state leaves
pose receipt and freshness untouched. Existing v1–4 configuration and C2
packet paths remain supported, with C2's independent pose and state sequence
streams. A C2 description or mapping number in a state message leaves a healthy
pose usable until its actual loss or expiry.

Compatible coordinate-space IDs/revisions are source descriptions, not physical-quality permissions. The declared coordinate convention, codec/math validation, identity/session/clock sequencing and actual component validity remain mandatory. An admitted description change clears transient history. Saved body/Q/6DoF choices and geometric interpretation survive reconnect/restart.

The normal explicit Q/6DoF assignment connects independently accepted physical IMU orientation to `BodyRotationGuidance` for each supported body target. The complete usable Main retains paired pose ownership; FULL samples continuously teach correction. On loss, a fresh independent Q uses the learned correction. Reacquired Main rotation converges at the solver input as well as visible output. Q supplies no measured position. HIP's optional historical configured learner is exclusive with the normal body learner for that target.

MVIP input is already transformed by its producer. The consumer applies no producer-specific coordinate conversion. Independent IMU capture includes its saved reset/mount transform; unspecified additional body transforms are Identity. No independent approval session, signature, or physical evidence is required. Stored frame quaternions must still be mathematically valid. Configuration writes retain CAS, backups and saved-versus-active/restart distinctions.

World normalization and attachment to an anatomical bone are separate. Existing
Full Reset and Mount Reset operations capture the selected Main input's consumer
attachment rotation for that body. The solver applies it on the right of the
Main orientation; the received paired position/orientation and Direct output are
unchanged. The attachment is Identity until captured, belongs to the same public
input reference and body, and survives Q/6DoF and component-loss transitions. It
is never borrowed from a different rotation fallback. Legacy input keeps its
existing reset/mount interpretation without a second adjustment.

IK position-offset rotation remains anchored to the original accepted Main
orientation, independently of the anatomical attachment. A Mount Reset therefore
does not rotate an already calibrated position offset or relearn it in the
mounting pose. The ordinary Full Reset still calibrates the positional offset.

For a current paired Main measurement constraining a knee, leg postprocessing
preserves the already solved anatomical knee position, including its corrected
history buffer. Foot skating and floor corrections remain enabled for the feet
and unmeasured knees. Q-only, lost/fallback, derived-position and disabled-IK
frames retain their existing postprocessing behavior.

The shared learner defaults originate in the existing Core PoC implementation (150 ms stable span, 20 ms pairing window, 0.1/0.2 s tracking/recovery, pi residual bound, 0.2 rad recovery residual, 2 rad/s correction rate, two recovery pairs, 1 s maximum step interval, 100 ms historical correction-input age). Ordinary explicit local Q, including Q-only, uses the existing configured input timeout; normal per-body correction uses that same age while retaining the numerical pairing window. A saved legacy HIP age applies only to its paired HIP input. A zero configured timeout accepts only a sample at the current tick and does not prevent startup. These values tune numerical correction and freshness; they do not certify a physical source or permit/deny ordinary Main pose use. They are provisional software values, not HIL-tuned limits. Existing explicit HIP correction settings continue supplying their saved geometry and tuning.

Background IK uses the saved coordinate interpretation, or Identity for an uncalibrated known convention. Uncalibrated is a diagnostic; the absence of a quality confirmation does not disable fallback. A revision-only change does not revoke this interpretation. Actual unsupported convention, missing root anchor, paused/disabled solver or invalid solved pose remains unavailable.

The normal HIP Q/6DoF pair also connects the existing position-correction orchestrator to current raw HMD input, independent physical Q and the live body model. Main FULL remains the measured teacher. During position loss, HMD plus Q can supply a typed derived position for the existing IK; it is never relabeled as a measured MVIP position. Fresh Main reacquisition converges at the solver and virtual output. Missing or stale HMD/Q disables this prediction. Current same-message finite HMD position/orientation, actual object ownership/status, active transport and immutable receipt time are required; historical input cannot restore it. Saved source-specific geometry is used only for the matching source/body context; otherwise Identity/zero geometry is diagnosed while ordinary Main tracking continues.

Offline acceptance observes production server tick -> private solver input -> existing IK -> computed/Direct virtual tracker values and captured output. Test fixtures and captured transport do not establish physical-device or interactive-runtime validation. Actual React/API/CAS and complete captured-wire product E2E are recorded separately from the focused numerical fixtures.
