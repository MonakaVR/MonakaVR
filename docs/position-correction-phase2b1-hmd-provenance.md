# Phase 2B-1: trusted HMD accepted-position receipt provenance

Phase 2B-1 establishes Layer 1 only: a historical record of a position payload accepted at the SteamVR protobuf ingress. It does not implement a predictor, PositionPrediction runtime, body-model snapshot, position learner, alignment, tracker-mount calibration, solver injection or output correction. Layer 2 (`RawHmdPoseInput`) remains blocked by unverified space/frame identity and missing message-level position/orientation pairing. Hardware/HIL: **NOT RUN**.

## Ingress and trust

`WindowsNamedPipeBridge` / `UnixSocketBridge` parse protobuf messages into `ProtobufBridge`'s queue. `dataRead()` processes them on the VRServer thread. `SteamVRBridge.createNewTracker()` supplies `DeviceOrigin.STEAMVR`, remote tracker ID 0, `isHmd=true`, and position/rotation capabilities for the external primary HMD. Existing external SteamVR trackers also carry `isComputed=true`; this flag is not sufficient evidence of internal solver output.

Only this trusted creation boundary registers the HMD with `TrustedRawHmdPositionSource`. Registration additionally rejects internal trackers, output objects, non-SteamVR devices, nonzero remote IDs, missing HMD/capability flags, and `monaka-direct:`, `monaka-solver:`, `monaka-private:`, `human://` namespaces through `FeedbackExclusion`. HEAD role alone, or an otherwise similar tracker without explicit creation-boundary registration, cannot acquire accepted-position provenance. Registration does not modify the tracker.

The production position-writer audit also found Protobuf `trackerOverrideUpdate`, WebSocket ingress/copies, UDP pose ingress, VMC/VRC OSC ingress, pose-frame playback, `ConstraintIkWriteback`, `DirectConstraintOutput`, `HumanSkeleton` computed trackers and `LegTweaks`. These are storage writes, other origins or derived outputs; none is added to this trusted HMD recorder. The snapshot does not follow generic HEAD selection or a copied/overridden tracker value.

## Wire audit and timestamp meaning

The committed `ProtobufMessages.Position` descriptor has exactly `tracker_id`, optional XYZ, quaternion XYZW, `data_source`, and optional velocity XYZ. There is no sample sequence, acquisition/pose/monotonic timestamp, session ID, tracking-universe ID or recenter/frame generation. TrackerAdded supplies ID/serial/name/role/manufacturer; it does not fill these gaps.

The locally prepared driver source at `build/direct-driver-source/src/VRDriver.cpp` obtains OpenVR raw poses and may apply a current-universe translation/yaw before sending XYZ/quaternion. Its `src/bridge/ProtobufMessages.proto` agrees with the server Position fields. Those locally inspected sender paths do not transmit the universe or transform identity, or acquisition time. This is source inspection, not evidence that the user's installed driver or physical HMD was exercised.

`receivedAtSystemNanos` is **local accepted ingress receipt time**, captured with `System.nanoTime()` in `positionReceived()` when the existing `hasX()` position assignment succeeds. Queue arrival may precede that acceptance; this value includes any delay before server-thread processing. It is not HMD physical acquisition time. No clock value is clamped or relabeled as a remote sensor time.

## Accepted sample and atomic snapshot

The recorder owns an independent per-registered-object sequence. Each accepted position payload advances it once, including identical numeric XYZ. No X means no position acceptance: rotation/velocity-only messages, status, battery, heartbeat, dataTick, polling, server ticks and solver updates cannot advance it. The exact existing `hasX()` gate is preserved, including protobuf defaults when Y/Z are omitted and cases where modality is not FULL; the record asserts acceptance, not usable tracking validity.

The ordinary `Tracker.position` setter and every other writer are unchanged. Instead of adding global position sample semantics, the bridge publishes `HmdAcceptedPositionSample` containing a copied position, sequence, receipt time, trusted identity and source epoch as one volatile immutable tuple. Registration and acceptance writes are server-thread confined. Snapshot reads never combine current mutable `Tracker.position` with old metadata, so concurrent readers cannot see position N+1/provenance N. A later ordinary storage write cannot mutate an earlier snapshot. No lock or solver/poll clock is added.

The snapshot is historical: old numeric storage and an old acceptance record are not proof of a currently fresh sample, connected device or usable pose. A future adapter must enforce status/modality, numeric validity and accepted-sample age. It must map receipt-clock age into its runtime clock once, reject impossible/negative age and rollback, and never extend freshness with poll/dataTick. This phase has no runtime clock mapping or freshness consumer.

## Epoch coverage and limits

Source epoch is a UUID for the registered Tracker object lifetime. Re-registering the same object and ordinary samples/status/dataTick leave it unchanged. Recreating the object starts a new epoch and clears the recorder's latest sample; sequence restarts within that new epoch.

Transport reconnect callbacks exist, but the queued messages are not tagged with a connection identity, TrackerAdded for an existing remote ID returns without recreation, and reconnect/disconnect callbacks are not consistently dispatched on the same thread on all platforms. This phase therefore does **not** claim session-level reconnect isolation or advance generation on inferred status changes. A reused object retains its object-lifetime epoch through reconnect. The existing IMU `correctionSourceEpoch` and Slime orientation-reset `correctionCalibrationEpoch()` are not reused as HMD position session/world-frame proof. Correlating connection lifetime with queued accepted messages remains a separate ingress-contract task.

## Phase 2A conversion remains fail closed

The accepted DTO is neither PoseObservation nor PositionPrediction and contains no guessed CoordinateSpace or calibration epoch. Its `rawPoseInputEligible` is false with `hmd_space_unverified`, `hmd_frame_epoch_unavailable`, and `hmd_pose_pairing_unavailable`. There is no RawHmdPoseInput converter/factory. Position and orientation acceptance sequences remain separate; this historical position snapshot does not promise a coherent orientation from the same upstream message. No Phase 2A constructor/eligibility requirement is weakened.

The immutable HIP body-model snapshot contract and offline effective-value capture are supplied by [Phase 2B-2](position-correction-phase2b2-body-model.md); live atomic capture remains unsupported. Remaining prerequisites are trusted connection/reconnect lineage, exact HMD space/frame evidence and position/orientation pairing, Main tracker mount-to-HIP_CENTER calibration, temporal pairing, a predictor algorithm and runtime integration, and a reviewed position-correction law. Numerical coordinate resemblance is not space proof, and Slime mounting/yaw reset is not an HMD world-frame reset.

## Software verification boundary

`TrustedHmdPositionProvenanceTests` drives serialized Position messages through the production protobuf queue/receive handler with a deterministic receipt clock. Fixtures populate the existing private remote registry without adding a mutable production test seam. Coverage includes new/identical payload acceptance, missing-X messages, receipt timestamps, no false advancement through ticks/poll/solver/status/battery/velocity, explicit ingress trust, external computed HMD acceptance, object recreation, stale numeric storage, immutable value/metadata snapshots, legacy numeric semantics and fail-closed Phase 2A readiness. The wire descriptor test fixes the absence of timestamp/sequence/frame fields.

Core/Desktop regression, Rotation Correction, Hybrid stress, diagnostics, Position Prediction contracts, shadowJar and MTP process E2E remain the software gates. MainFallbackPolicy, Rotation Correction, OutputContinuity, Direct serialization and visible SteamVR pose behavior are unchanged. Hardware/HIL and SteamVR interactive validation: **NOT RUN**.
