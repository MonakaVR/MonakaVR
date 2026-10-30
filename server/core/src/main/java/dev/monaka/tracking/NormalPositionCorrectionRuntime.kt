package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Values observed at the actual solver commit, not source capabilities or a measured pose. */
data class NormalPositionCorrectionDiagnostics(
	val hmdAvailable: Boolean,
	val geometry: String,
	val prediction: Vector3?,
	val measuredTeacher: Vector3?,
	val solverPosition: Vector3?,
	val solverPositionReference: String,
	val learning: String,
	val continuity: String,
	val correctionWorld: Vector3,
	val appliedPosition: Vector3?,
)

/** Normal server composition of the existing position algorithm. Numeric defaults
 * are the existing CorePocHilSession values, never a physical-quality approval.
 * Optional saved geometry is applied only to its explicitly bound source/body model.
 */
internal class NormalPositionCorrectionRuntime(
	private val saved: PositionCorrectionConfig?,
	private val bodySource: () -> HipBodyModelSnapshotResult,
	private val writeback: ConstraintIkWriteback,
	private val reacquireDurationNanos: Long,
) {
	private var orchestrator: PositionCorrectionRuntimeOrchestrator? = null
	private var context: List<Any?>? = null
	private var lastSpace: dev.monaka.protocol.v2.CoordinateSpace? = null
	private var lastMainContext: dev.monaka.tracking.mtp.MtpSourceContextSnapshot? = null
	var diagnostics: NormalPositionCorrectionDiagnostics? = null
		private set

	fun process(
		tick: MonakaResolvedTickSnapshot,
		sources: MonakaSourceTickSnapshot,
		resolved: Map<TrackerPosition, EffectiveConstraint>,
		hmd: RawHmdPoseInput?,
	): PositionCorrectionRuntimeTickResult? {
		val hip = tick.assignment.targets[TrackerPosition.HIP] ?: return null
		val qRef = hip.rotationFallbackTracker ?: return null
		val mainRef = hip.mainTracker
		val mainContext = mainRef.mtp?.let(sources.mtpContexts::get)
		if (mainContext != null) lastMainContext = mainContext
		val continuityContext = lastMainContext
		val body = (bodySource() as? HipBodyModelSnapshotResult.Available)?.snapshot
		val q = sources.observationsBySource[qRef.observationId]?.takeIf {
			qRef.mtp == null &&
				sources.sourceOwners[it.sourceId] == "slime" &&
				it.rotationQuality.usable &&
				it.provenance != null &&
				it.correctionRotation != null
		}
		val space = hmd?.space ?: lastSpace ?: return null
		lastSpace = space
		val rawImu = q?.let {
			val p = requireNotNull(it.provenance)
			if (p.sampleAtNanos > tick.nowNanos || tick.nowNanos - p.sampleAtNanos > (saved?.predictorPolicy?.maxImuSampleAgeNanos ?: RotationCorrectionTuning.normalRuntimeDefaults().maxImuSampleAgeNanos)) {
				null
			} else {
				runCatching { RawImuOrientationInput(RawSourceIdentity(it.sourceId, RawSourceKind.RAW_IMU), requireNotNull(it.correctionRotation), space, p.copy(space = space)) }.getOrNull()
			}
		}
		val mountSaved = saved?.mainMountCalibration?.takeIf { it.sourceId == mainRef.observationId }
		val mount = mountSaved?.toSnapshot() ?: PositionCorrectionMainMountCalibrationConfig(
			"consumer-identity-mount",
			"saved-identity",
			mainRef.observationId,
			Vector3.NULL,
		).toSnapshot()
		val fixedSaved = saved?.fixedCalibration?.takeIf { it.hmdSourceId == hmd?.source?.sourceId && it.bodyModelId == body?.identity?.modelId }
		val fixed = if (hmd != null && body != null) {
			fixedSaved?.toSnapshot() ?: PositionCorrectionFixedCalibrationConfig(
				"consumer-identity-head-anchor",
				"saved-identity",
				hmd.source.sourceId,
				body.identity.modelId,
				Vector3.NULL,
				Quaternion.IDENTITY,
			).toSnapshot()
		} else {
			null
		}
		val geometry = when {
			mountSaved != null && fixedSaved != null -> "saved_geometry"
			saved != null && (mountSaved == null || fixedSaved == null) -> "uncalibrated_identity_other_source_geometry_ignored"
			else -> "uncalibrated_identity"
		}
		val nextContext = if (hmd != null && rawImu != null && body != null && fixed != null) {
			listOf(
				space, tick.assignment, hmd.provenance.sourceEpoch, hmd.provenance.calibrationEpoch,
				rawImu.provenance.sourceEpoch, rawImu.provenance.calibrationEpoch, body.identity,
				fixed.identity, mount.identity, continuityContext?.sourceEpoch, continuityContext?.calibrationEpoch,
				continuityContext?.mappingRevision, continuityContext?.coordinateSpace,
			)
		} else {
			null
		}
		if (nextContext != null && nextContext != context) {
			context = nextContext
			orchestrator = PositionCorrectionRuntimeOrchestrator(
				saved?.predictorPolicy?.toRuntime() ?: MainDecoupledHipPredictorPolicy(20_000_000, 100_000_000, 100_000_000),
				saved?.pairingPolicy?.toRuntime() ?: PositionTemporalPairingPolicy(20_000_000, 100_000_000, 100_000_000, 0),
				saved?.learningTuning?.toRuntime() ?: PositionCorrectionTuning(.1, .2, 5.0, 2.0, 1.0, .05, 1_000_000_000, 2_000_000_000, 5.0, 1.0, .2, 0, 2, .0001),
				saved?.reacquisitionTuning?.toRuntime() ?: PositionCorrectionReacquisitionTuning(reacquireDurationNanos),
				writeback,
			)
		}
		val active = orchestrator ?: return null
		val rawMain = sources.observationsBySource[mainRef.observationId]?.takeIf {
			sources.sourceOwners[it.sourceId] == "mtp" && mainContext != null && it.provenance?.space?.convention == space.convention
		}?.let {
			val p = requireNotNull(it.provenance)
			// Saved coordinate interpretation is stable. A changed source description
			// still changes transient teacher lineage and cannot reuse old pair history.
			it.copy(provenance = p.copy(space = space, calibrationEpoch = "${p.calibrationEpoch}|${p.space}"))
		}
		val teacher = rawMain?.let { MainTrackerMountToHipCenter.normalize(it, RawSourceIdentity(it.sourceId, RawSourceKind.RAW_BACKEND), mount) }
			?.let { it as? MainHipCenterTeacherResult.Available }?.teacher
		val teacherValue = teacher?.let {
			val p = it.provenance
			PositionCorrectionTeacherTickValue(
				it,
				PositionTeacherEpoch(
					it.sourceId,
					p.sourceEpoch,
					p.calibrationEpoch,
					p.mappingRevision,
					space,
					tick.assignment.generation,
					PositionBodyReference.HIP_CENTER,
					mount.identity,
				),
			)
		}
		val predictors = if (hmd != null && rawImu != null && body != null && fixed != null) PositionCorrectionPredictorSources(hmd, rawImu, body, fixed) else null
		val result = active.process(
			PositionCorrectionRuntimeTick(
				tick.tickSequence,
				tick.nowNanos,
				tick.resolvedAtNanos,
				space,
				tick.assignment,
				resolved,
				predictors,
				teacherValue,
			),
		)
		if (result is PositionCorrectionRuntimeTickResult.Processed) {
			val selected = result.solverConstraints[TrackerPosition.HIP]
			diagnostics = NormalPositionCorrectionDiagnostics(
				hmd != null, geometry, result.prediction?.position, teacher?.position,
				selected?.position?.value, selected?.positionReference.toString(), result.learning.decision.toString(), result.continuity.phase.toString(),
				result.learning.state.correctionWorld, (result.application as? PositionCorrectionApplicationResult.Ready)?.candidate?.correctedPosition,
			)
		}
		return result
	}
}
