# HIP rotation correction, Phase 1

This is an opt-in software foundation, not a calibrated hardware profile. Omitting
`rotationCorrection` (or setting `enabled: false`) preserves the existing Hybrid,
resolver, IK, Direct output, and continuity paths. Position correction is absent.
The tuning values in an enabled config are **PROVISIONAL** and must be chosen and
validated for the actual hardware; the existing output-continuity timing is not
reused as correction timing.

## Independent samples and epochs

Slime physical IMU orientation provenance comes from `Tracker.setRotation`, the
orientation acceptance boundary. Its monotonic sequence changes on an accepted
orientation write, never on `dataTick`, heartbeat, server poll, or solver update.
The receipt timestamp uses `System.nanoTime` and is mapped once into the Monaka
runtime clock by the Slime adapter. Poll time is **not** physical sample time.
Only assigned physical IMU trackers (`device != null`, `isImu()`) expose this
correction provenance. Phase 1 config requires an MTP Main and an explicitly
assigned Slime fallback; an unrelated MTP pose cannot masquerade as the IMU.
Reusing a tracker on UDP handshake, reconnecting via
status, or replacing the tracker object changes its source epoch. Mount/reset
operations and the fixed-transform quaternion values change its calibration
epoch. An epoch change revokes readiness; normal modality loss does not erase
the learned quaternion.

MTP provenance is the accepted pose sequence, the one-time age-adjusted local
sample timestamp, publisher/source/tracker identity, source session/clock,
input session, mapping revision, and coordinate-space ID/convention/revision.
The accepted sample is cached; its timestamp cannot become newer by another
server tick. Session, mapping or space changes are a new correction epoch.
Packet wire format is unchanged.

## Frames and learning

Quaternion composition is left-to-right frame chaining: `q(W<-B) =
q(W<-T) * q(T<-B)`. `W` is the approved Monaka canonical world, `B` is the
common HIP body frame, `T` is the Main tracker frame, and `S` is the IMU sensor
frame. Slime's correction-only orientation applies its existing fixed
mount/reset transform to raw physical IMU orientation, but excludes the
solver's `constraintFix`, Stay Aligned and drift compensation. The explicit
`fallbackToBodyWxyz` is any *remaining* fixed body-frame transform after that
Slime transform. `mainTrackerToBodyWxyz` maps the normalized MTP Main tracker
orientation into the same body frame. Neither transform is inferred as
identity. Both four-component quaternions, the exact common-space assertion,
and `framesConfirmed: true` are required for an enabled configuration. This is
an operator assertion, not proof of physical alignment; HIL must verify it.

For synchronized, fresh, independent accepted samples in the same space:

```
q_main = normalize(q(W<-T) * q(T<-B))
q_imu  = normalize(q_slime_fixed(W<-S) * q(S<-B)_remaining)
q_target = normalize(q_main * inverse(q_imu))
q_corrected = normalize(q_corr * q_imu)
residual = 2 acos(clamp(abs(dot(q_main, q_corrected)), 0, 1))
alpha = 1 - exp(-dt / tau_rot)
beta = min(alpha, W_rot_max * dt / max(shortest_angle(q_corr,q_target), epsilon))
q_corr_next = normalize(slerp_shortest(q_corr, q_target, beta))
```

The left multiplication makes `q_corr` a world-side dynamic drift correction;
it is distinct from fixed mounting calibration. Both sample sequences must
advance for a new pair. Accepted FULL samples, rather than server ticks, span
the recovery dwell. Sign-equivalent quaternions use the shortest path. Invalid
quaternions, rollback/nonpositive or oversized `dt`, unpaired timestamps, and
residual outliers are rejected. Outliers are not clamped into training data.
The speed limit only bounds updates from accepted measurements.

Learning and application have separate eligibility. `ready` means that the
correction belongs to the active epoch. `pairWindowNanos` synchronizes a new
Main/IMU teacher pair **for learning only**; `maxDtNanos` guards a learning
update interval **only**. Rejected pairing, repeated accepted samples, a Main
teacher sequence/timestamp rollback, a residual outlier, or a large `dt` does
not by itself reset `q_corr` or prevent application to a healthy fallback.
Repeated samples advance neither learning nor recovery dwell. Rejection
counters distinguish Main teacher anomalies from IMU application-integrity
anomalies.

Application requires a ready correction, the resolver's assigned fallback
owner, a fresh physical IMU sample with valid quaternion/provenance, the
learned IMU epoch, compatible assignment and space. It does **not** require a
new or synchronized Main teacher on that tick. `maxImuSampleAgeNanos` is the
physical freshness limit for both learning and application. An IMU sequence
rollback, a newer sequence with non-increasing sample timestamp, or a changed
timestamp for the same sequence blocks application; Main-side teacher rollback
only blocks learning. Epoch changes still revoke readiness. The existing
pre-policy filter also makes stale, missing-provenance, invalid, or
sequence/timestamp-rolled-back physical fallback observations unusable to the
resolver, so they cannot leak through as raw fallback rotation.

## Ownership and exactly-once application

The learner receives the raw normalized assigned Main observation and the raw
assigned physical fallback observation separately. It never treats the
resolver's selected fallback rotation as the Main teacher. The existing
`MainFallbackPolicy` and `ConstraintResolver` continue to decide solver source
ownership. During FULL, Main remains authoritative for IK and Direct output;
the learner runs in parallel and its correction is **not** added to Main.
ROTATION_ONLY Main can be a teacher, although the resolver may choose fallback
as solver owner. NONE or stale Main stops learning and retains the correction.
Fresh fallback with a ready correction can supply corrected solver and visible
fallback rotation.
No sample or epoch yields no correction.

Only `MonakaServerIntegration` substitutes the selected HIP rotation, and only
when the resolver already chose the assigned fallback as rotation owner. It
constructs one derived `ResolvedTrackingPose` component and fans that exact
component out to both `ConstraintIkWriteback` and `OutputContinuityController`.
`q_corr` is multiplied once; the continuity controller uses the selected
rotation without multiplying it again. During an existing fallback blend, the
visible pose can interpolate from the last emitted pose before it reaches that
selected rotation. Direct Main remains raw/authoritative.
No correction is applied in the Background IK reader, Direct output tracker,
or SteamVR serializer. The raw `PoseObservation` and resolver result remain
unchanged. `monaka-direct:`,
`monaka-solver:`, `monaka-private:`, `human://`, computed trackers, post-IK
poses and visible output cannot teach the correction. There is no correction
of position, pelvis/root translation, or computed HIP.

## Physical IMU sample freshness

Enabled Phase 1 config requires `maxImuSampleAgeNanos > 0` (**PROVISIONAL**, not
derived from output-continuity timing). The assigned HIP fallback's eligibility
is checked before the existing resolver: `age = now -
provenance.sampleAtNanos`, with `0 <= age <= maxImuSampleAgeNanos`. Missing,
future, or stale physical samples become rotation-unusable. The same check
prevents stale samples from teaching or receiving correction. Server polls,
heartbeats, and `dataTick` do not alter the physical sample timestamp or
extend freshness. Other targets and correction-OFF legacy Slime behavior keep
their existing profiles.

`ready` describes a learned correction for the current epoch; `fresh` describes
whether this particular IMU sample may be used. Temporary staleness retains
`q_corr` and readiness but produces no fallback rotation. A fresh sample in
the same epoch can resume use. When Main is ROTATION_ONLY and fallback is stale,
the existing `MainFallbackPolicy` chooses Main rotation; when Main is NONE and
fallback is stale, rotation is unavailable. No identity quaternion stands in
for missing rotation.

Correction states (`UNINITIALIZED`, `REACQUIRING`, `TRACKING`, `DEGRADED`,
`IMU_ONLY`) and their sample/residual clock are independent of the visible
`OutputContinuityController` states and its clock. Diagnostic readiness,
residual, age, and rejection counters are available from the controller.
Hardware/HIL, physical alignment, VIVE/PICO behavior, and SteamVR interactive
validation: **NOT RUN**.

Phase 2B-5Y adds the [dormant Raw IMU production boundary](position-correction-phase2b5y-raw-imu-production-boundary.md): shared physical acceptance capture, independent fixed mount/reset orientation and separate explicit exact-space binding. Phase 1 q_corr remains excluded; runtime assembly stays disconnected.
