package dev.monaka.tracking.correction

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.DeclaredTrackerCapabilities
import dev.monaka.tracking.FeedbackExclusion
import dev.monaka.tracking.LearningDecision
import dev.monaka.tracking.RotationCorrectionTuning
import dev.monaka.tracking.TrackingModality
import dev.monaka.tracking.revision.MainFallbackDecision
import dev.monaka.tracking.revision.MainFallbackPolicy
import dev.monaka.tracking.revision.RotationOwner
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Structured identity: display names and concatenated epoch strings are not identity. */
data class GuidanceIdentity(val publisher: String, val source: String, val tracker: String)
data class GuidanceEpoch(
	val session: String,
	val sourceEpoch: String,
	val calibrationEpoch: String,
	val mappingRevision: Long,
	val space: CoordinateSpace,
)
data class GuidanceSource(val identity: GuidanceIdentity, val epoch: GuidanceEpoch)
data class GuidanceFrames(
	val mainTrackerToBody: Quaternion,
	val imuToBody: Quaternion,
	/** Historical diagnostic labels only; ignored for runtime permission. */
	val approvalRevision: String,
	val confirmed: Boolean,
	val approvedMain: GuidanceSource,
	val approvedImu: GuidanceSource,
)

/** Current authority snapshot, supplied even when Main is occluded/absent. Body ID is opaque. */
data class GuidanceBinding(
	val bodyId: String,
	val assignmentRevision: Long,
	val main: GuidanceSource,
	val imu: GuidanceSource,
	val frames: GuidanceFrames,
)

/** Both times are in the caller's monotonic domain, mapped once at acceptance. */
data class GuidanceReceipt(
	val sequence: Long,
	val sampleAtNanos: Long,
	val receivedAtNanos: Long,
	val epoch: GuidanceEpoch,
)
enum class GuidanceOrigin { INDEPENDENT_MAIN_ACCEPTANCE, PHYSICAL_IMU_ACCEPTANCE, POSE_ORIENTATION, OUTPUT }

/** Receipt facts come from independent input acceptance, never a selected solver pose.
 * BodyRotationRuntime connects these values; they are not physical-quality certification.
 */
data class GuidanceEvidence(
	val origin: GuidanceOrigin,
	val identity: GuidanceIdentity,
	val receipt: GuidanceReceipt,
	val orientation: Quaternion,
)
data class GuidanceSample(
	val bodyId: String,
	val identity: GuidanceIdentity,
	val orientation: Quaternion?,
	val receipt: GuidanceReceipt?,
	val evidence: GuidanceEvidence?,
)
data class GuidanceTeacher(
	val sample: GuidanceSample,
	val modality: TrackingModality,
	val position: Vector3?,
	val capabilities: DeclaredTrackerCapabilities?,
	val positionUsable: Boolean,
	val rotationUsable: Boolean,
)
data class GuidanceImu(val sample: GuidanceSample, val usable: Boolean)
data class GuidanceInput(
	val nowNanos: Long,
	val binding: GuidanceBinding?,
	val teacher: GuidanceTeacher?,
	val imu: GuidanceImu?,
	val selectedOwner: RotationOwner,
)

enum class GuidancePhase { UNAVAILABLE, REACQUIRING, TRACKING, DEGRADED, IMU_ONLY }
enum class GuidanceReason {
	NONE,
	UNASSIGNED,
	CLOCK_ROLLBACK,
	BINDING_INVALID,
	FRAME_UNAPPROVED,
	FEEDBACK,
	SAMPLE_MISSING,
	IDENTITY_MISMATCH,
	EPOCH_MISMATCH,
	PROVENANCE_MISSING,
	RECEIPT_MISMATCH,
	ORIGIN_NOT_INDEPENDENT,
	TIME_INVALID,
	STALE,
	ROTATION_INVALID,
	SAMPLE_ROLLBACK,
	SAMPLE_MUTATED,
	CAPABILITIES_UNKNOWN,
	TEACHER_NOT_FULL,
	POSITION_INVALID,
	SAMPLE_UNUSABLE,
	PAIR_WINDOW,
	SAME_PAIR,
	FIRST_PAIR,
	LARGE_DT,
	RESIDUAL_OUTLIER,
	NOT_READY,
	OWNER_NOT_FALLBACK,
}

data class GuidanceCursor(val sample: GuidanceSample)
data class GuidancePair(val mainSequence: Long, val imuSequence: Long, val atNanos: Long)

/** Explicit per-body state, returned by value. Calling advance never mutates its arguments. */
data class BodyRotationState(
	val binding: GuidanceBinding? = null,
	val lastNowNanos: Long? = null,
	val phase: GuidancePhase = GuidancePhase.UNAVAILABLE,
	val ready: Boolean = false,
	val correction: Quaternion = Quaternion.IDENTITY,
	val mainCursor: GuidanceCursor? = null,
	val imuCursor: GuidanceCursor? = null,
	val lastPair: GuidancePair? = null,
	val anchorAtNanos: Long? = null,
	val firstFullAtNanos: Long? = null,
	val goodRecoveryPairs: Int = 0,
	val lastLearnedAtNanos: Long? = null,
	val residualRadians: Double? = null,
)
data class GuidanceRotation(val orientation: Quaternion, val owner: GuidanceIdentity, val sampleAtNanos: Long)
data class BodyRotationResult(
	val state: BodyRotationState,
	val learning: LearningDecision,
	val learningReason: GuidanceReason,
	val teacherReason: GuidanceReason,
	val imuReason: GuidanceReason,
	val applicationReason: GuidanceReason,
	val ownership: MainFallbackDecision,
	val correctedImu: GuidanceRotation?,
	val fallbackCandidate: GuidanceRotation?,
)

/** Device-independent pure transition. No tick clock, tracker, socket, singleton, IO or output writer. */
object BodyRotationGuidance {
	fun advance(previous: BodyRotationState, input: GuidanceInput, tuning: RotationCorrectionTuning): BodyRotationResult {
		if (input.nowNanos < 0 || previous.lastNowNanos?.let { input.nowNanos < it } == true) {
			return unavailable(previous, GuidanceReason.CLOCK_ROLLBACK)
		}
		val suppliedBinding = input.binding ?: return unavailable(BodyRotationState(lastNowNanos = input.nowNanos), GuidanceReason.UNASSIGNED)
		val binding = suppliedBinding.copy(frames = suppliedBinding.frames.copy(
			approvalRevision = "", confirmed = false, approvedMain = suppliedBinding.main, approvedImu = suppliedBinding.imu,
		))
		// Any authority/frame change revokes readiness even when no sample is present.
		var s = if (binding != previous.binding) BodyRotationState(binding = binding) else previous
		s = s.copy(lastNowNanos = input.nowNanos)
		val bindingReason = validateBinding(binding)
		if (bindingReason != GuidanceReason.NONE) return unavailable(s.copy(phase = GuidancePhase.UNAVAILABLE), bindingReason)
		// An observed epoch change also revokes readiness while the caller refreshes authority.
		if (listOf(input.teacher?.sample to binding.main, input.imu?.sample to binding.imu).any { (sample, source) ->
				sample?.identity == source.identity && sample.receipt?.epoch?.let { it != source.epoch } == true
			}
		) {
			s = BodyRotationState(binding = binding, lastNowNanos = input.nowNanos)
		}
		val imu = input.imu
		var ir = validateSample(
			imu?.sample,
			binding.imu,
			binding.bodyId,
			input.nowNanos,
			tuning.maxImuSampleAgeNanos,
			GuidanceOrigin.PHYSICAL_IMU_ACCEPTANCE,
			s.imuCursor,
		)
		if (ir == GuidanceReason.NONE && imu?.usable != true) ir = GuidanceReason.SAMPLE_UNUSABLE
		val teacher = input.teacher
		var tr = validateSample(
			teacher?.sample,
			binding.main,
			binding.bodyId,
			input.nowNanos,
			tuning.maxImuSampleAgeNanos,
			GuidanceOrigin.INDEPENDENT_MAIN_ACCEPTANCE,
			s.mainCursor,
		)
		if (tr == GuidanceReason.NONE && teacher?.rotationUsable != true) tr = GuidanceReason.SAMPLE_UNUSABLE
		val mainRotationUsable = tr == GuidanceReason.NONE
		val mainPositionUsable = mainRotationUsable && teacher?.positionUsable == true && finite(teacher.position)
		// This remains the existing policy. The learner does not create another ownership rule.
		val ownership = MainFallbackPolicy.decide(
			teacher?.modality ?: TrackingModality.NONE,
			mainPositionUsable,
			mainRotationUsable,
			ir == GuidanceReason.NONE,
		)
		if (tr == GuidanceReason.NONE) {
			tr = when {
				teacher?.capabilities?.supportsSixDof != true -> GuidanceReason.CAPABILITIES_UNKNOWN
				teacher.modality != TrackingModality.FULL -> GuidanceReason.TEACHER_NOT_FULL
				!mainPositionUsable -> GuidanceReason.POSITION_INVALID
				else -> GuidanceReason.NONE
			}
		}
		if (ir == GuidanceReason.NONE) s = s.copy(imuCursor = GuidanceCursor(imu!!.sample))
		if (mainRotationUsable) s = s.copy(mainCursor = GuidanceCursor(teacher!!.sample))
		val full = tr == GuidanceReason.NONE
		val phase = when {
			full && previous.phase == GuidancePhase.TRACKING && s.ready -> GuidancePhase.TRACKING
			full -> GuidancePhase.REACQUIRING
			teacher?.modality == TrackingModality.ROTATION_ONLY && mainRotationUsable -> GuidancePhase.DEGRADED
			ir == GuidanceReason.NONE -> GuidancePhase.IMU_ONLY
			else -> GuidancePhase.UNAVAILABLE
		}
		s = s.copy(phase = phase)
		var learning = LearningDecision.NOT_ATTEMPTED
		var lr = if (!full) tr else ir
		if (!full || ir != GuidanceReason.NONE) {
			// Loss interrupts dwell, but retains the epoch's learned correction and readiness.
			s = resetDwell(s)
			if (teacher != null && teacher.modality == TrackingModality.FULL) learning = LearningDecision.REJECTED
		} else {
			val mp = teacher!!.sample.receipt!!
			val ip = imu!!.sample.receipt!!
			val pairAt = maxOf(mp.sampleAtNanos, ip.sampleAtNanos)
			val last = s.lastPair
			lr = when {
				kotlin.math.abs(mp.sampleAtNanos - ip.sampleAtNanos) > tuning.pairWindowNanos -> GuidanceReason.PAIR_WINDOW
				last != null && (mp.sequence <= last.mainSequence || ip.sequence <= last.imuSequence) -> GuidanceReason.SAME_PAIR
				else -> GuidanceReason.NONE
			}
			if (lr != GuidanceReason.NONE) {
				learning = LearningDecision.REJECTED
				if (lr != GuidanceReason.SAME_PAIR) s = resetDwell(s)
			} else {
				val qm = RotationGuidanceMath.body(teacher.sample.orientation!!, binding.frames.mainTrackerToBody)
				val qi = RotationGuidanceMath.body(imu.sample.orientation!!, binding.frames.imuToBody)
				val error = RotationGuidanceMath.angle(qm, RotationGuidanceMath.body(s.correction, qi))
				s = s.copy(lastPair = GuidancePair(mp.sequence, ip.sequence, pairAt), residualRadians = error)
				val anchor = s.anchorAtNanos
				when {
					error > tuning.maxResidualRadians -> {
						s = resetDwell(s)
						learning = LearningDecision.REJECTED
						lr = GuidanceReason.RESIDUAL_OUTLIER
					}

					anchor == null -> {
						s = s.copy(anchorAtNanos = pairAt, firstFullAtNanos = pairAt)
						lr = GuidanceReason.FIRST_PAIR
					}

					pairAt - anchor > tuning.maxDtNanos -> {
						s = resetDwell(s).copy(anchorAtNanos = pairAt, firstFullAtNanos = pairAt)
						learning = LearningDecision.REJECTED
						lr = GuidanceReason.LARGE_DT
					}

					else -> {
						// Both per-source watermarks advanced strictly: dt cannot be <= 0.
						val tau = if (s.phase == GuidancePhase.TRACKING) tuning.trackingTauSeconds else tuning.recoveryTauSeconds
						val correction = RotationGuidanceMath.learn(
							s.correction,
							RotationGuidanceMath.target(qm, qi),
							(pairAt - anchor) / 1e9,
							tau,
							tuning.maxRadiansPerSecond,
						)
						val remaining = RotationGuidanceMath.angle(qm, RotationGuidanceMath.body(correction, qi))
						val stable = pairAt - s.firstFullAtNanos!! >= tuning.fullStableNanos && remaining <= tuning.recoveryResidualRadians
						val good = if (stable) minOf(s.goodRecoveryPairs.toLong() + 1, tuning.recoveryPairs.toLong()).toInt() else 0
						val recovered = good >= tuning.recoveryPairs
						s = s.copy(
							correction = correction,
							anchorAtNanos = pairAt,
							lastLearnedAtNanos = pairAt,
							goodRecoveryPairs = good,
							ready = s.ready || recovered,
							phase = if (recovered || s.phase == GuidancePhase.TRACKING) GuidancePhase.TRACKING else GuidancePhase.REACQUIRING,
						)
						learning = LearningDecision.ACCEPTED
					}
				}
			}
		}
		val corrected = if (s.ready && ir == GuidanceReason.NONE) {
			GuidanceRotation(
				RotationGuidanceMath.body(s.correction, RotationGuidanceMath.body(imu!!.sample.orientation!!, binding.frames.imuToBody)),
				binding.imu.identity,
				imu.sample.receipt!!.sampleAtNanos,
			)
		} else {
			null
		}
		val ar = when {
			ir != GuidanceReason.NONE -> ir
			!s.ready -> GuidanceReason.NOT_READY
			input.selectedOwner != RotationOwner.FALLBACK || ownership.rotationOwner != RotationOwner.FALLBACK -> GuidanceReason.OWNER_NOT_FALLBACK
			else -> GuidanceReason.NONE
		}
		return BodyRotationResult(
			s, learning, lr, tr, ir, ar, ownership, corrected,
			corrected.takeIf { ar == GuidanceReason.NONE },
		)
	}

	private fun resetDwell(s: BodyRotationState) = s.copy(anchorAtNanos = null, firstFullAtNanos = null, goodRecoveryPairs = 0)
	private fun unavailable(s: BodyRotationState, reason: GuidanceReason) = BodyRotationResult(
		s,
		LearningDecision.REJECTED, reason, reason, reason, reason,
		MainFallbackPolicy.decide(TrackingModality.NONE, false, false, false), null, null,
	)
	private fun finite(p: Vector3?) = p != null && listOf(p.x, p.y, p.z).all { it.isFinite() }
	private fun validIdentity(id: GuidanceIdentity) = listOf(id.publisher, id.source, id.tracker).all { it.isNotBlank() }
	private fun feedback(id: GuidanceIdentity) = listOf(id.publisher, id.source, id.tracker).any(FeedbackExclusion::isOutput)
	private fun validEpoch(e: GuidanceEpoch) = e.session.isNotBlank() &&
		e.sourceEpoch.isNotBlank() &&
		e.calibrationEpoch.isNotBlank() &&
		e.mappingRevision >= 0 &&
		e.space.id.isNotBlank() &&
		e.space.revision >= 0 &&
		e.space.convention == "rh_y_up_neg_z_forward"
	private fun validateBinding(b: GuidanceBinding): GuidanceReason = when {
		feedback(b.main.identity) || feedback(b.imu.identity) -> GuidanceReason.FEEDBACK

		b.bodyId.isBlank() ||
			b.assignmentRevision < 0 ||
			b.main.identity == b.imu.identity ||
			!validIdentity(b.main.identity) ||
			!validIdentity(b.imu.identity) ||
			!validEpoch(b.main.epoch) ||
			!validEpoch(b.imu.epoch) ||
			b.main.epoch.space.convention != b.imu.epoch.space.convention -> GuidanceReason.BINDING_INVALID

		// These are saved geometric transforms. Historical approval labels and
		// transport epochs cannot revoke their ordinary runtime use.
		RotationGuidanceMath.normalize(b.frames.mainTrackerToBody) == null ||
			RotationGuidanceMath.normalize(b.frames.imuToBody) == null -> GuidanceReason.FRAME_UNAPPROVED

		else -> GuidanceReason.NONE
	}
	private fun validateSample(
		sample: GuidanceSample?,
		expected: GuidanceSource,
		body: String,
		now: Long,
		maxAge: Long,
		origin: GuidanceOrigin,
		cursor: GuidanceCursor?,
	): GuidanceReason {
		if (sample == null) return GuidanceReason.SAMPLE_MISSING
		if (feedback(sample.identity)) return GuidanceReason.FEEDBACK
		if (sample.identity != expected.identity || sample.bodyId != body) return GuidanceReason.IDENTITY_MISMATCH
		val p = sample.receipt ?: return GuidanceReason.PROVENANCE_MISSING
		if (p.epoch != expected.epoch) return GuidanceReason.EPOCH_MISMATCH
		val evidence = sample.evidence ?: return GuidanceReason.PROVENANCE_MISSING
		if (evidence.origin != origin) return GuidanceReason.ORIGIN_NOT_INDEPENDENT
		if (evidence.identity != sample.identity || evidence.receipt != p) return GuidanceReason.RECEIPT_MISMATCH
		if (p.sequence < 0 || p.sampleAtNanos < 0 || p.receivedAtNanos < p.sampleAtNanos || p.receivedAtNanos > now) {
			return GuidanceReason.TIME_INVALID
		}
		if (now - p.sampleAtNanos > maxAge) return GuidanceReason.STALE
		val q = sample.orientation?.let(RotationGuidanceMath::normalize) ?: return GuidanceReason.ROTATION_INVALID
		val eq = RotationGuidanceMath.normalize(evidence.orientation) ?: return GuidanceReason.ROTATION_INVALID
		if (RotationGuidanceMath.angle(q, eq) > 1e-6) return GuidanceReason.RECEIPT_MISMATCH
		val old = cursor?.sample
		if (old != null) {
			val op = old.receipt!!
			if (p.sequence < op.sequence ||
				(
					p.sequence > op.sequence &&
						(p.sampleAtNanos <= op.sampleAtNanos || p.receivedAtNanos < op.receivedAtNanos)
					)
			) {
				return GuidanceReason.SAMPLE_ROLLBACK
			}
			if (p.sequence == op.sequence && (p != op || RotationGuidanceMath.angle(q, old.orientation!!) > 1e-6)) {
				return GuidanceReason.SAMPLE_MUTATED
			}
		}
		return GuidanceReason.NONE
	}
}
