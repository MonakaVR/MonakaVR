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

## Ownership and exactly-once application

The learner receives the raw normalized assigned Main observation and the raw
assigned physical fallback observation separately. It never treats the
resolver's selected fallback rotation as the Main teacher. The existing
`MainFallbackPolicy` and `ConstraintResolver` continue to decide solver source
ownership. During FULL, Main remains authoritative for IK and Direct output;
the learner runs in parallel and its correction is **not** added to Main.
ROTATION_ONLY Main can be a teacher, although the resolver may choose fallback
as solver owner. NONE or stale Main stops learning and retains the correction.
Fresh fallback with a ready correction can supply corrected solver rotation.
No sample or epoch yields no correction.

Only `MonakaServerIntegration` may substitute a solver-only HIP rotation in
the `EffectiveConstraint` copy sent to `ConstraintIkWriteback`, and only when
the resolver already selected the assigned fallback as rotation owner. It
does not mutate `PoseObservation`, the resolver's result, background IK pose,
Direct tracker, output continuity, or SteamVR output. `monaka-direct:`,
`monaka-solver:`, `monaka-private:`, `human://`, computed trackers, post-IK
poses and visible output cannot teach the correction. There is no correction
of position, pelvis/root translation, or computed HIP.

Correction states (`UNINITIALIZED`, `REACQUIRING`, `TRACKING`, `DEGRADED`,
`IMU_ONLY`) and their sample/residual clock are independent of the visible
`OutputContinuityController` states and its clock. Diagnostic readiness,
residual, age, and rejection counters are available from the controller.
Hardware/HIL, physical alignment, VIVE/PICO behavior, and SteamVR interactive
validation: **NOT RUN**.
