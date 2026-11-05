# Q-only Full Reset root cause

Base: `85a9ead351abec6f56de37e1f854bbe96801ae33`. Offline investigation; physical alignment is not certified.

The two source investigations compare the existing normal Tracker path with the actual public MVIP -> resolver -> private input -> FK path. Their detailed references are in `NORMAL_FULL_RESET_PATH.md` and `MONAKA_Q_ONLY_RESET_PATH.md`.

## Confirmed divergence

Normal rotation Tracker Full Reset updates `TrackerResetsHandler` offsets, rather than replacing the raw sensor quaternion. `HumanSkeleton.resetTrackersFull` selects reset-capable inputs. Subsequent `Tracker.getRotation()` applies those offsets before FK consumes the rotation.

The current `ConstraintIkWriteback` instead creates every private proxy with `allowReset=false` and `allowMounting=false`. Consequently the assigned non-head proxy is excluded from the Full Reset loop, and its rotation getter independently bypasses the reset transform. It continues receiving resolved raw Q on subsequent ticks. The existing actual-consumed diagnostic records this getter result, not a hypothetical reset-adjusted value.

A second lifecycle defect would remain after merely changing flags: the active proxy is removed on total component loss, and recovery creates a new Tracker with a new reset handler. Assignment generation alone refreshes topology without necessarily replacing the object; genuine identity/capability changes can replace it. Persistent consumer interpretation must therefore be distinguished from transient sample availability.

## Non-causes and limits

- The GUI request is Full and reaches the existing skeleton operation. An event alone is not functional acceptance.
- Q-only position exclusion and component validity are already implemented; do not redo that work.
- A consumer reset is not supposed to mutate admitted MVIP Q or command a producer.
- Existing local Slime observations already contain `Tracker.getRotation()` output; resetting their private proxy again would double-apply interpretation.
- SixDoF Main/fallback rotation and derived-position precompensation require their existing semantics. A temporary rotation-only modality is not an explicit Q-only assignment.
- RR-00F input declarations, PICO ownership, Bridge mapping and physical alignment are outside this fix.

## Selected correction

Reuse the existing Tracker reset handler for explicitly assigned MVIP Q-only inputs only, with identity initial consumer mounting and normal reset/mount capabilities. Retain the same private Tracker for the unchanged body/Q-source relationship across transient loss and topology refresh; exclude it from active inputs while unavailable. Do not export a second quaternion-offset system. Advance existing yaw-reset smoothing from the accepted solver tick, since private inputs are not registered raw trackers.

Rejected alternatives: unconditional `allowReset=true`, resetting already-adjusted Slime or SixDoF proxies, producer/Bridge/wire changes, arbitrary heading multiplication, GUI-only changes, and retained stale Q during loss.

## Dynamic proof requirement

Before editing production, demonstrate a failing public-input/real-skeleton test against this base: normal Tracker resets toward the existing head-yaw reference while the Q-only private input does not. Then require actual consumed Q and computed/bone parity, position isolation, repeated reset, normal post-reset motion and lifecycle recovery. Record actual results in `FULL_RESET_SOFTWARE_ACCEPTANCE.md`; this document does not predeclare test PASS.

The implementation owner reproduced this failure before production edits using `MvipQOnlyResetTests.publicQOnlyFullResetMatchesOrdinaryTrackerAndHeadYaw`. The ordinary Tracker first passed the analytical head-yaw assertion. The actual Q-only FK read then failed: expected quaternion `(w,x,y,z)=(0.95384705,0,0.3002932,0)`, observed unchanged raw quaternion `(0.8183324,0.20895459,0.51877105,-0.13246402)`, absolute dot `0.93634737`. The one behavioral test failed as expected, with exit 1. `Build/diagnostics/full-reset-fix/red.xml` and `red-elevated.log` retain the evidence. The earlier restricted Gradle daemon-connection failure was environmental, not behavioral proof.
