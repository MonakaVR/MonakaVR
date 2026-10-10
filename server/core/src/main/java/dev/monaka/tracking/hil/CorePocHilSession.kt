package dev.monaka.tracking.hil

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.abs
import kotlin.math.acos

/** Explicit diagnostic configuration. These limits are provisional software settings, not HIL tuning. */
data class CorePocHilConfig(
	val space: CoordinateSpace,
	val mainSourceId: String,
	val imuSourceId: String,
	val hmdSourceId: String,
	val operatorConfirmedSameWorld: Boolean,
	val operatorConfirmedBodyFrames: Boolean,
	val continuity: ContinuityTuning = ContinuityTuning(),
	val correctionEnabled: Boolean = true,
	val positionTrackingTauSeconds: Double = .1,
	val positionRecoveryTauSeconds: Double = .2,
	val positionHoldNanos: Long = 2_000_000_000,
	val positionDecayTauSeconds: Double = 5.0,
	val maxPositionResidualMeters: Double = 5.0,
	val maxPositionCorrectionMeters: Double = 2.0,
	val maxPositionCorrectionRate: Double = 1.0,
	val maxPositionUpdateStep: Double = .05,
	val rotationTrackingTauSeconds: Double = .1,
	val rotationRecoveryTauSeconds: Double = .2,
	val maxAngularCorrectionRate: Double = 2.0,
) {
	init {
		require(listOf(mainSourceId, imuSourceId, hmdSourceId).distinct().size == 3)
		require(listOf(mainSourceId, imuSourceId, hmdSourceId).all { it.isNotBlank() && !FeedbackExclusion.isOutput(it) })
		require(space.convention == "rh_y_up_neg_z_forward")
	}
	companion object {
		fun synthetic() = CorePocHilConfig(CoordinateSpace("poc-synthetic-world", "rh_y_up_neg_z_forward", 0),
			"hil:synthetic:main", "hil:synthetic:imu", "hil:synthetic:hmd", true, true)
	}
}

/** Common Pose diagnostic carrier, not a substitute wire protocol or production ingress admission. */
data class CorePocInputFrame(val timestampNanos: Long, val sequence: Long,
	val main: PoseObservation, val imu: PoseObservation, val hmd: RawHmdPoseInput, val mainEnvelopeJson: String? = null)

/** Explicit local HIL owner. No VRServer, receiver, SteamVR bridge, device or OS lifecycle. */
class CorePocHilSession(val config: CorePocHilConfig, hilMode: Boolean = false) : AutoCloseable {
	val gate = HilValidityGate(hilMode, config.mainSourceId)
	private val hip = TrackerPosition.HIP
	private val assignments = TrackerBodyAssignments().also {
		it.configure(hip, TrackerReference(config.mainSourceId), TrackerReference(config.imuSourceId), OutputMode.HYBRID)
	}
	private val pipeline = ConstraintPipeline(resolver = ConstraintResolver { assignments.snapshot().targets },
		eligibility = ::eligible, pinnedEligibility = { value, now, _ -> eligible(value, now) })
	private val head = tracker("hil-head", TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
	private val physicalImu = tracker("hil-imu", hip, false)
	private val poseManager = HumanPoseManager(listOf(head, physicalImu)).also {
		it.setLegTweaksEnabled(false)
		it.skeleton.ikSolver.enabled = true
		it.update()
	}
	private val writeback = ConstraintIkWriteback(poseManager.skeleton)
	private val body = (poseManager.currentHipBodyModelSnapshot() as HipBodyModelSnapshotResult.Available).snapshot
	private val fixed = (PredictorFixedCalibrationSnapshot.create("hil-fixed", "hil-fit:1", config.hmdSourceId,
		body.identity.modelId, Vector3.NULL, Quaternion.IDENTITY) as PredictorFixedCalibrationSnapshotResult.Available).snapshot
	private val mount = (MainTrackerMountCalibrationSnapshot.create("hil-main-body-center", "hil-mount:1",
		config.mainSourceId, Vector3.NULL) as MainTrackerMountCalibrationSnapshotResult.Available).snapshot
	private val predictorPolicy = MainDecoupledHipPredictorPolicy(20_000_000, 100_000_000, 100_000_000)
	private val predictor = PureMainDecoupledHipPredictor(predictorPolicy)
	private val position = PositionCorrectionRuntimeOrchestrator(predictorPolicy,
		PositionTemporalPairingPolicy(20_000_000, 100_000_000, 100_000_000, 0),
		PositionCorrectionTuning(config.positionTrackingTauSeconds, config.positionRecoveryTauSeconds,
			config.maxPositionResidualMeters, config.maxPositionCorrectionMeters, config.maxPositionCorrectionRate,
			config.maxPositionUpdateStep, 1_000_000_000, config.positionHoldNanos, config.positionDecayTauSeconds,
			config.maxPositionCorrectionRate, .2, 0, 2, .0001),
		PositionCorrectionReacquisitionTuning(config.continuity.reacquireDurationNs), writeback)
	private val rotation = HipRotationCorrection(RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY,
		config.space, config.operatorConfirmedBodyFrames), RotationCorrectionTuning(150_000_000, 20_000_000,
		config.rotationTrackingTauSeconds, config.rotationRecoveryTauSeconds, Math.PI, .2,
		config.maxAngularCorrectionRate, 2, 1_000_000_000, 100_000_000))
	private val background = BackgroundIkPoseReader(poseManager.skeleton, BackgroundIkAlignment.confirmedSameSpace(config.space))
	private val continuity = OutputContinuityController(hip, ContinuityPolicy.BACKGROUND_IK, config.continuity)
	private val mapper = ObjectMapper()
	private var capture: BufferedWriter? = null
	private var previous: Map<String, Any?>? = null
	private var lastInput: CorePocInputFrame? = null
	private var transitionAt = 0L
	private var closed = false
	var latest: Map<String, Any?>? = null
		private set
	val events = mutableListOf<Map<String, Any?>>()

	init { require(config.operatorConfirmedSameWorld) { "Local HIL requires explicit common-world assertion" } }

	private fun eligible(raw: PoseObservation, now: Long): PoseObservation = gate.apply(
		ObservationFreshnessPolicy(if (raw.sourceId == config.mainSourceId) 500_000_000 else 100_000_000,
			if (raw.sourceId == config.mainSourceId) 500_000_000 else 100_000_000).apply(raw, now))

	fun captureStart(path: Path) {
		check(!closed && capture == null)
		path.parent?.let(Files::createDirectories)
		capture = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
		emit(linkedMapOf("type" to "capture_start", "schema" to SCHEMA, "stateMachineVersion" to STATE_VERSION,
			"timestampNanos" to lastInput?.timestampNanos, "config" to configRecord(), "hilMode" to gate.hilMode,
			"gateAvailable" to gate.available))
	}
	fun captureStop() {
		if (capture == null) return
		emit(linkedMapOf("type" to "capture_stop", "schema" to SCHEMA, "timestampNanos" to lastInput?.timestampNanos))
		capture!!.close(); capture = null
	}
	fun mark(note: String) {
		check(!closed)
		emit(linkedMapOf("type" to "mark", "schema" to SCHEMA, "timestampNanos" to lastInput?.timestampNanos, "note" to note))
	}
	fun status(): Map<String, Any?> = linkedMapOf("schema" to SCHEMA, "stateMachineVersion" to STATE_VERSION,
		"hilMode" to gate.hilMode, "gateAvailable" to gate.available, "capturing" to (capture != null),
		"closed" to closed, "frame" to latest, "config" to configRecord())

	/** All controls end at gate availability; this method alone consumes source samples. */
	fun accept(input: CorePocInputFrame): Map<String, Any?> {
		check(!closed)
		validate(input)
		val now = input.timestampNanos
		val assignment = assignments.snapshot()
		head.position = input.hmd.position; head.setRotation(input.hmd.orientation)
		head.status = if (now - input.hmd.provenance.sampleAtNanos <= predictorPolicy.maxHmdSampleAgeNanos) TrackerStatus.OK else TrackerStatus.TIMED_OUT
		physicalImu.setRotation(input.imu.rotation!!)
		check(pipeline.ingest(input.main)); check(pipeline.ingest(input.imu))
		val resolved = pipeline.resolveAll(now, assignment)
		val observations = pipeline.observations(now, assignment).associateBy { it.sourceId }
		val main = observations.getValue(config.mainSourceId)
		val imu = observations.getValue(config.imuSourceId)
		val base = resolved.getValue(hip)
		val correctedRotation = if (config.correctionEnabled) rotation.update(main, imu, base.rotation?.sourceId,
			now, assignment.generation, config.space)?.rotation else null
		val effective = base.copy(rotation = correctedRotation ?: base.rotation)
		val rawImu = RawImuOrientationInput(RawSourceIdentity(config.imuSourceId, RawSourceKind.RAW_IMU),
			input.imu.correctionRotation ?: input.imu.rotation!!, config.space, input.imu.provenance!!)
		val independent = predictor.predict(MainDecoupledHipInput(input.hmd, rawImu, body, fixed, config.space,
			assignment.generation, now, input.sequence))
		val teacher = if (config.correctionEnabled) (MainTrackerMountToHipCenter.normalize(main,
			RawSourceIdentity(config.mainSourceId, RawSourceKind.RAW_BACKEND), mount) as? MainHipCenterTeacherResult.Available)?.teacher else null
		val teacherValue = teacher?.let { PositionCorrectionTeacherTickValue(it, PositionTeacherEpoch(it.sourceId,
			it.provenance.sourceEpoch, it.provenance.calibrationEpoch, it.provenance.mappingRevision, config.space,
			assignment.generation, PositionBodyReference.HIP_CENTER, mount.identity, it.provenance.commonWorldEpoch)) }
		val processed = if (config.correctionEnabled) position.process(PositionCorrectionRuntimeTick(input.sequence,
			now, now, config.space, assignment, resolved + (hip to effective),
			PositionCorrectionPredictorSources(input.hmd, rawImu, body, fixed), teacherValue)) as PositionCorrectionRuntimeTickResult.Processed else null
		if (processed == null) {
			// Explicit A/B counterfactual: the visible Main stays primary, but no Main
			// constraint or learned correction reaches the independent background IK.
			writeback.apply(mapOf(hip to EffectiveConstraint(hip, rotation = imu.rotation?.let {
				ResolvedComponent(it, imu.sourceId, imu.rotationQuality, imu.observedAtNanos)
			})), assignment)
		}
		check(processed == null || processed.writeback.values.all { it == ConstraintIkWriteback.ApplyResult.Applied })
		poseManager.update()
		val solved = background.read(hip, config.space, now)
		val selected = ResolvedTrackingPose.from(effective, config.space, main)
		val output = continuity.update(selected, solved, now)
		val phase = continuity.fusionPhase.name
		if (previous?.get("state") != phase) transitionAt = now
		val translation = processed?.learning?.state?.correctionWorld ?: Vector3.NULL
		val rawPosition = independent.position
		val correctedPosition = rawPosition?.plus(translation)
		val correctedQuaternion = if (config.correctionEnabled && rotation.ready)
			(rotation.correction * rawImu.orientation).unit() else rawImu.orientation
		val priorPose = continuityPose(previous?.get("finalOutputPose"))
		val outputPosition = output.position?.value
		val outputRotation = output.rotation?.value
		val dt = lastInput?.let { (now - it.timestampNanos) / 1e9 }
		val positionStep = distance(priorPose?.first, outputPosition)
		val angularStep = angular(priorPose?.second, outputRotation)
		val frame = linkedMapOf<String, Any?>(
			"type" to "frame", "schema" to SCHEMA, "stateMachineVersion" to STATE_VERSION,
			"timestampNanos" to now, "sequence" to input.sequence,
			"sourceId" to input.main.sourceId, "sourceProvenance" to provenance(input.main.provenance!!),
			"sourceNativeEnvelope" to input.mainEnvelopeJson?.let(mapper::readTree),
			"imuProvenance" to provenance(input.imu.provenance!!), "hmdProvenance" to provenance(input.hmd.provenance),
			"assignmentGeneration" to assignment.generation, "world" to spaceRecord(),
			"raw6dofValid" to (input.main.positionQuality.usable && input.main.rotationQuality.usable),
			"sixDofValid" to (main.positionQuality.usable && main.rotationQuality.usable && main.modality == TrackingModality.FULL),
			"gateAvailable" to gate.available, "raw6dofPose" to pose(input.main.position, input.main.rotation),
			"rawImuOrientationXyzw" to quat(rawImu.orientation),
			"rawIkPose" to pose(rawPosition, rawImu.orientation),
			"rawIkDefinition" to "independent Main-decoupled central-chain prediction; not a second solver",
			"correctedIkPose" to pose(correctedPosition, correctedQuaternion),
			"solvedIkPose" to pose(solved.pose?.position?.value, solved.pose?.rotation?.value),
			"solverFallbackPosition" to vector(processed?.solverConstraints?.get(hip)?.position?.value),
			"solverSelectionPhase" to processed?.continuity?.phase?.name,
			"correctionOffsetMeters" to vector(translation), "correctionQuaternionXyzw" to quat(rotation.correction),
			"positionCorrectionState" to (processed?.learning?.state?.phase?.name ?: "DISABLED"),
			"positionCorrectionReason" to processed?.learning?.reason?.name,
			"rotationCorrectionState" to (if (config.correctionEnabled) rotation.state.name else "DISABLED"),
			"rotationCorrectionReady" to (config.correctionEnabled && rotation.ready),
			"rotationCorrectionReason" to rotation.learningReason,
			"state" to phase, "nativeContinuityState" to continuity.state.name,
			"transitionReason" to continuity.lastTransition?.reason, "transitionTimestampNanos" to transitionAt,
			"dwellElapsedNanos" to continuity.recoveryDwellElapsedNanos,
			"dwellThresholdNanos" to continuity.recoveryDwellThresholdNanos,
			"hysteresisState" to continuity.hysteresisState, "blendProgress" to continuity.blendProgress,
			"finalOutputPose" to pose(outputPosition, outputRotation), "outputPath" to output.positionSource.name,
			"positionOwner" to effective.position?.sourceId, "rotationOwner" to effective.rotation?.sourceId,
			"rawPositionResidualMeters" to distance(input.main.position, rawPosition),
			"positionResidualMeters" to distance(input.main.position, correctedPosition),
			"angularResidualRadians" to angular(input.main.rotation, correctedQuaternion),
			"rawAngularResidualRadians" to angular(input.main.rotation, rawImu.orientation),
			"solvedPositionResidualMeters" to distance(input.main.position, solved.pose?.position?.value),
			"finalPositionResidualMeters" to distance(input.main.position, outputPosition),
			"finalAngularResidualRadians" to angular(input.main.rotation, outputRotation),
			"positionStepMeters" to positionStep, "angularStepRadians" to angularStep,
			"outputVelocityMetersPerSecond" to if (dt != null && dt > 0) positionStep?.div(dt) else null,
			"outputAngularVelocityRadiansPerSecond" to if (dt != null && dt > 0) angularStep?.div(dt) else null,
		)
		emit(frame)
		recordEvents(previous, frame)
		previous = frame; latest = frame; lastInput = input
		return frame
	}

	private fun recordEvents(before: Map<String, Any?>?, after: Map<String, Any?>) {
		fun event(name: String) {
			val value = linkedMapOf("type" to "event", "schema" to SCHEMA, "event" to name,
				"timestampNanos" to after["timestampNanos"], "sequence" to after["sequence"],
				"stateBefore" to before?.get("state"), "stateAfter" to after["state"],
				"reason" to after["transitionReason"], "sixDofValid" to after["sixDofValid"],
				"positionResidualMeters" to after["positionResidualMeters"], "angularResidualRadians" to after["angularResidualRadians"],
				"positionStepMeters" to after["positionStepMeters"], "angularStepRadians" to after["angularStepRadians"])
			events += value; emit(value)
			if (events.size > 2048) events.removeAt(0) // Bounded read-only recent history; capture keeps the complete stream.
		}
		if (before?.get("sixDofValid") != after["sixDofValid"]) event(if (after["sixDofValid"] == true) "6dof_valid" else "6dof_lost")
		if (before?.get("state") != after["state"]) {
			if (before?.get("state") == "RECOVERY_DWELL" && after["state"] != "RECOVERY_BLEND") event("dwell_reset")
			if (before?.get("state") == "RECOVERY_BLEND" && after["state"] != "FULL_6DOF") event("recovery_blend_cancel")
			when (after["state"]) {
				"FALLBACK_IK" -> event("fallback_entered")
				"RECOVERY_DWELL" -> { event("recovery_candidate"); event("dwell_start") }
				"RECOVERY_BLEND" -> event("recovery_blend_start")
				"FULL_6DOF" -> event("full_restored")
			}
		}
		if (before?.get("correctionOffsetMeters") != after["correctionOffsetMeters"] ||
			before?.get("correctionQuaternionXyzw") != after["correctionQuaternionXyzw"]) event("correction_updated")
	}
	private fun validate(input: CorePocInputFrame) {
		require(input.timestampNanos >= 0 && input.sequence >= 0)
		require(lastInput?.let { input.timestampNanos > it.timestampNanos && input.sequence > it.sequence } != false)
		require(input.main.sourceId == config.mainSourceId && input.imu.sourceId == config.imuSourceId && input.hmd.source.sourceId == config.hmdSourceId)
		require(input.main.target == hip && input.imu.target == hip && input.imu.rotationQuality.usable)
		for (p in listOf(input.main.provenance, input.imu.provenance, input.hmd.provenance)) {
			require(p != null && p.space == config.space && p.sampleAtNanos <= input.timestampNanos)
		}
		require(input.hmd.space == config.space)
		require(input.main.observedAtNanos == input.main.provenance!!.sampleAtNanos &&
			input.imu.observedAtNanos == input.imu.provenance!!.sampleAtNanos)
		require(input.imu.provenance!!.commonWorldEpoch == input.hmd.provenance.commonWorldEpoch &&
			input.main.provenance!!.commonWorldEpoch == input.hmd.provenance.commonWorldEpoch)
	}
	private fun emit(value: Map<String, Any?>) { capture?.let { it.write(mapper.writeValueAsString(value)); it.newLine(); it.flush() } }
	fun configRecord(): Map<String, Any?> = linkedMapOf("space" to spaceRecord(), "mainSourceId" to config.mainSourceId,
		"imuSourceId" to config.imuSourceId, "hmdSourceId" to config.hmdSourceId, "correctionEnabled" to config.correctionEnabled,
		"correctionDisabledMode" to "software counterfactual: IMU-only IK constraints, visible Main remains primary",
		"dwellMs" to config.continuity.stableFullDwellMs, "blendMs" to config.continuity.reacquireDurationMs,
		"fallbackBlendMs" to config.continuity.fallbackBlendMs, "positionTrackingTauSeconds" to config.positionTrackingTauSeconds,
		"positionRecoveryTauSeconds" to config.positionRecoveryTauSeconds, "positionHoldNanos" to config.positionHoldNanos,
		"positionDecayTauSeconds" to config.positionDecayTauSeconds, "maxPositionResidualMeters" to config.maxPositionResidualMeters,
		"maxPositionCorrectionMeters" to config.maxPositionCorrectionMeters, "maxPositionCorrectionRate" to config.maxPositionCorrectionRate,
		"maxPositionUpdateStep" to config.maxPositionUpdateStep, "rotationTrackingTauSeconds" to config.rotationTrackingTauSeconds,
		"rotationRecoveryTauSeconds" to config.rotationRecoveryTauSeconds, "maxAngularCorrectionRate" to config.maxAngularCorrectionRate,
		"fixedPolicies" to linkedMapOf("mainFreshnessNanos" to 500_000_000, "imuFreshnessNanos" to 100_000_000,
			"hmdFreshnessNanos" to 100_000_000, "pairWindowNanos" to 20_000_000, "maxLearningDtNanos" to 1_000_000_000,
			"positionRecoveryResidualMeters" to .2, "positionRecoveryStableNanos" to 0, "positionRecoverySamples" to 2,
			"positionZeroEpsilonMeters" to .0001, "rotationFullStableNanos" to 150_000_000,
			"rotationMaxResidualRadians" to Math.PI, "rotationRecoveryResidualRadians" to .2, "rotationRecoveryPairs" to 2),
		"bodyModelIdentity" to linkedMapOf("id" to body.identity.modelId, "epoch" to body.identity.epoch),
		"fixedCalibrationIdentity" to linkedMapOf("id" to fixed.identity.calibrationId, "epoch" to fixed.identity.epoch),
		"mainMountIdentity" to linkedMapOf("id" to mount.identity.calibrationId, "epoch" to mount.identity.epoch),
		"calibration" to "explicit common-world and HIP body-center / identity body-frame assertion; no production admission")
	private fun spaceRecord() = linkedMapOf("id" to config.space.id, "revision" to config.space.revision, "convention" to config.space.convention)
	override fun close() { if (closed) return; captureStop(); writeback.close(); pipeline.clear(); closed = true }
	companion object {
		const val SCHEMA = "monaka-core-poc-capture-v1"
		const val STATE_VERSION = "existing-continuity-physical-dwell-v1+hil-correction-6g-v1"
		private fun tracker(name: String, role: TrackerPosition, hmd: Boolean) = Tracker(null, -500 - role.ordinal,
			name, trackerPosition = role, hasPosition = hmd, hasRotation = true, isHmd = hmd,
			allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false).also { it.status = TrackerStatus.OK }
		fun vector(v: Vector3?) = v?.let { listOf(it.x, it.y, it.z) }
		fun quat(q: Quaternion?) = q?.let { listOf(it.x, it.y, it.z, it.w) }
		fun pose(p: Vector3?, q: Quaternion?) = linkedMapOf("positionMeters" to vector(p), "quaternionXyzw" to quat(q))
		fun distance(a: Vector3?, b: Vector3?): Double? = if (a == null || b == null) null else (a - b).len().toDouble()
		fun angular(a: Quaternion?, b: Quaternion?): Double? = if (a == null || b == null) null else
			2 * acos(abs(a.unit().dot(b.unit()).toDouble()).coerceIn(0.0, 1.0))
		private fun provenance(p: ObservationSampleProvenance) = linkedMapOf("sequence" to p.sequence,
			"sampleAtNanos" to p.sampleAtNanos, "sourceEpoch" to p.sourceEpoch, "calibrationEpoch" to p.calibrationEpoch,
			"mappingRevision" to p.mappingRevision, "commonWorldEpoch" to p.commonWorldEpoch)
		@Suppress("UNCHECKED_CAST") private fun continuityPose(value: Any?): Pair<Vector3?, Quaternion?>? {
			val p = value as? Map<String, Any?> ?: return null
			val v = p["positionMeters"] as? List<Number>; val q = p["quaternionXyzw"] as? List<Number>
			return (v?.let { Vector3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }) to
				(q?.let { Quaternion(it[3].toFloat(), it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) })
		}
	}
}
