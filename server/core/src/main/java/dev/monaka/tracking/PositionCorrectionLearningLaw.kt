package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.sqrt

/** Caller supplies every policy value. No production defaults or HIL tuning. */
internal data class PositionCorrectionTuning(
	val trackingTauSeconds: Double,
	val recoveryTauSeconds: Double,
	val maxResidualMeters: Double,
	val maxCorrectionMagnitudeMeters: Double,
	val maxCorrectionRateMetersPerSecond: Double,
	val maxUpdateStepMeters: Double,
	val maxLearningDtNanos: Long,
	val holdNanos: Long,
	val decayTauSeconds: Double,
	val maxDecayRateMetersPerSecond: Double,
	val recoveryResidualMeters: Double,
	val recoveryStableNanos: Long,
	val recoverySamples: Int,
	val zeroEpsilonMeters: Double,
) {
	init {
		require(listOf(trackingTauSeconds, recoveryTauSeconds, maxResidualMeters,
			maxCorrectionMagnitudeMeters, maxCorrectionRateMetersPerSecond, maxUpdateStepMeters,
			decayTauSeconds, maxDecayRateMetersPerSecond).all { it.isFinite() && it > 0 })
		require(maxLearningDtNanos > 0 && holdNanos >= 0)
		require(recoveryResidualMeters.isFinite() && recoveryResidualMeters >= 0 && recoveryResidualMeters <= maxResidualMeters)
		require(recoveryStableNanos >= 0 && recoverySamples > 0)
		require(zeroEpsilonMeters.isFinite() && zeroEpsilonMeters >= 0 && zeroEpsilonMeters <= maxCorrectionMagnitudeMeters)
	}
}

internal enum class PositionCorrectionPhase { UNINITIALIZED, REACQUIRING, TRACKING, HOLDING, DECAYING, EXPIRED }
internal data class PositionCorrectionLearningLineage(
	val teacherEpoch: PositionTeacherEpoch,
	val predictionEpoch: PositionPredictionEpoch,
)

/** Physical comparison identity; caller predictionSequence deliberately absent. */
internal data class PositionCorrectionPhysicalWatermark(
	val teacherSequence: Long,
	val teacherSampleAtNanos: Long,
	val hmdSequence: Long,
	val hmdSampleAtNanos: Long,
	val imuSequence: Long,
	val imuSampleAtNanos: Long,
)

/** Immutable diagnostic/numerical state, NEVER IK/application/output authority.
 * correctionWorld moves the pure prediction toward teacher in this exact world space.
 */
internal data class PositionCorrectionStateSnapshot(
	val phase: PositionCorrectionPhase,
	val correctionWorld: Vector3,
	val coordinateSpace: CoordinateSpace?,
	val lineage: PositionCorrectionLearningLineage?,
	val lastObserved: PositionCorrectionPhysicalWatermark?,
	val lastAccepted: PositionCorrectionPhysicalWatermark?,
	val lastAcceptedPredictionSequence: Long?,
	val lastStateAdvanceAtNanos: Long?,
	val recoveryGoodSamples: Int,
	val recoveryStartTeacherAtNanos: Long?,
)

internal enum class PositionCorrectionLearningDecision { SEEDED, UPDATED, DUPLICATE, REJECTED, HELD, DECAYED, EXPIRED, INVALIDATED }
internal enum class PositionCorrectionLearningReason {
	FIRST_SAMPLE, UPDATED, DUPLICATE_PHYSICAL_PAIR, TEACHER_SEQUENCE_ROLLBACK,
	TEACHER_TIME_ROLLBACK, TEACHER_SAMPLE_IDENTITY_MISMATCH,
	HMD_SEQUENCE_ROLLBACK, HMD_SAMPLE_IDENTITY_MISMATCH, HMD_TIME_ROLLBACK,
	IMU_SEQUENCE_ROLLBACK, IMU_SAMPLE_IDENTITY_MISMATCH, IMU_TIME_ROLLBACK,
	PREDICTION_PHYSICAL_PAIR_NOT_ADVANCED, MEASUREMENT_RESIDUAL_OUTLIER,
	LEARNING_DT_TOO_LARGE, NUMERIC_INVALID, INVALID_SAMPLE,
	HELD_WITHOUT_MEASUREMENT, DECAYED_WITHOUT_MEASUREMENT, CORRECTION_EXPIRED,
	TEACHER_EPOCH_CHANGED, PREDICTION_EPOCH_CHANGED, PREDICTION_CONTEXT_UNAVAILABLE,
	STATE_TIME_ROLLBACK, NO_ACCEPTED_MEASUREMENT,
}
internal data class PositionCorrectionLearningResult(
	val decision: PositionCorrectionLearningDecision,
	val reason: PositionCorrectionLearningReason,
	val state: PositionCorrectionStateSnapshot,
)

/** Dormant, deterministic world translation learner. Only the 6B comparison sample and
 * explicit gap context enter; no clock, runtime objects, Main availability detection or application.
 */
internal class PositionCorrectionLearningLaw(private val tuning: PositionCorrectionTuning) {
	private var correction = DVector.ZERO
	private var phase = PositionCorrectionPhase.UNINITIALIZED
	private var lineage: PositionCorrectionLearningLineage? = null
	private var observed: PositionCorrectionPhysicalWatermark? = null
	private var accepted: PositionCorrectionPhysicalWatermark? = null
	private var acceptedPredictionSequence: Long? = null
	private var advanceAt: Long? = null
	private var recoveryGoodSamples = 0
	private var recoveryStart: Long? = null
	private var stableTracking = false

	fun snapshot() = PositionCorrectionStateSnapshot(phase, correction.snapshot(), lineage?.predictionEpoch?.coordinateSpace,
		lineage, observed, accepted, acceptedPredictionSequence, advanceAt, recoveryGoodSamples, recoveryStart)

	fun observe(sample: PositionErrorSample): PositionCorrectionLearningResult {
		val nextLineage = PositionCorrectionLearningLineage(sample.teacherEpoch, sample.predictionEpoch)
		// Context incompatibility is stale numerical state, never a loss to be decayed.
		val changed = when {
			lineage != null && lineage!!.predictionEpoch != sample.predictionEpoch -> PositionCorrectionLearningReason.PREDICTION_EPOCH_CHANGED
			lineage != null && lineage!!.teacherEpoch != sample.teacherEpoch -> PositionCorrectionLearningReason.TEACHER_EPOCH_CHANGED
			else -> null
		}
		if (changed != null) invalidate()
		if (sample.evaluatedAtNanos < 0 || advanceAt?.let { sample.evaluatedAtNanos < it } == true)
			return result(PositionCorrectionLearningDecision.REJECTED, PositionCorrectionLearningReason.STATE_TIME_ROLLBACK)
		if (!validSample(sample)) {
			if (changed != null) return result(PositionCorrectionLearningDecision.INVALIDATED, changed)
			return reject(sample, PositionCorrectionLearningReason.INVALID_SAMPLE)
		}
		lineage = nextLineage
		val identity = PositionCorrectionPhysicalWatermark(sample.teacherSequence, sample.teacherSampleAtNanos,
			sample.predictionHmdSequence, sample.predictionHmdSampleAtNanos,
			sample.predictionImuSequence, sample.predictionImuSampleAtNanos)
		val previous = observed
		if (previous != null) {
			progressionFailure(previous, identity)?.let { return reject(sample, it) }
			// Update observed only for identity-consistent non-rollback observations, independently of acceptance.
			observed = identity
			if (identity.teacherSequence == previous.teacherSequence)
				return reject(sample, PositionCorrectionLearningReason.DUPLICATE_PHYSICAL_PAIR, duplicate = true)
			if (identity.hmdSequence == previous.hmdSequence && identity.imuSequence == previous.imuSequence)
				return reject(sample, PositionCorrectionLearningReason.PREDICTION_PHYSICAL_PAIR_NOT_ADVANCED, duplicate = true)
		} else observed = identity

		val residual = DVector.from(sample.errorWorld) - correction
		val residualNorm = residual.norm()
		if (!residual.finite() || !residualNorm.isFinite()) return reject(sample, PositionCorrectionLearningReason.NUMERIC_INVALID)
		if (residualNorm > tuning.maxResidualMeters) return reject(sample, PositionCorrectionLearningReason.MEASUREMENT_RESIDUAL_OUTLIER)

		val last = accepted
		if (last == null) {
			seed(sample, identity)
			return result(if (changed == null) PositionCorrectionLearningDecision.SEEDED else PositionCorrectionLearningDecision.INVALIDATED,
				changed ?: PositionCorrectionLearningReason.FIRST_SAMPLE)
		}
		val dt = sample.teacherSampleAtNanos - last.teacherSampleAtNanos
		if (dt <= 0) return reject(sample, PositionCorrectionLearningReason.TEACHER_TIME_ROLLBACK)
		if (dt > tuning.maxLearningDtNanos) {
			seed(sample, identity)
			return result(PositionCorrectionLearningDecision.SEEDED, PositionCorrectionLearningReason.LEARNING_DT_TOO_LARGE)
		}
		// A physical teacher gap beyond hold also requires recovery when no explicit gap tick arrived.
		if (phase == PositionCorrectionPhase.DECAYING || phase == PositionCorrectionPhase.EXPIRED || dt > tuning.holdNanos) {
			stableTracking = false
			resetRecovery()
		}
		phase = if (stableTracking) PositionCorrectionPhase.TRACKING else PositionCorrectionPhase.REACQUIRING
		val seconds = dt / 1e9
		val tau = if (stableTracking) tuning.trackingTauSeconds else tuning.recoveryTauSeconds
		val alpha = -expm1(-seconds / tau)
		val rateLimit = tuning.maxCorrectionRateMetersPerSecond * seconds
		val limit = minOf(tuning.maxUpdateStepMeters, rateLimit)
		if (!alpha.isFinite() || !rateLimit.isFinite() || !limit.isFinite())
			return reject(sample, PositionCorrectionLearningReason.NUMERIC_INVALID)
		val step = (residual * alpha).bounded(limit)
		val candidate = (correction + step).bounded(tuning.maxCorrectionMagnitudeMeters)
		if (!step.finite() || !candidate.finite() || !candidate.norm().isFinite())
			return reject(sample, PositionCorrectionLearningReason.NUMERIC_INVALID)
		correction = candidate
		accept(sample, identity)
		if (!stableTracking) recover(sample.teacherSampleAtNanos, (DVector.from(sample.errorWorld) - correction).norm())
		return result(PositionCorrectionLearningDecision.UPDATED, PositionCorrectionLearningReason.UPDATED)
	}

	fun advanceWithoutMeasurement(nowNanos: Long, currentPredictionEpoch: PositionPredictionEpoch?): PositionCorrectionLearningResult {
		if (nowNanos < 0) return result(PositionCorrectionLearningDecision.REJECTED, PositionCorrectionLearningReason.STATE_TIME_ROLLBACK)
		if (currentPredictionEpoch == null) {
			invalidate()
			return result(PositionCorrectionLearningDecision.INVALIDATED, PositionCorrectionLearningReason.PREDICTION_CONTEXT_UNAVAILABLE)
		}
		if (lineage != null && currentPredictionEpoch != lineage!!.predictionEpoch) {
			invalidate()
			return result(PositionCorrectionLearningDecision.INVALIDATED, PositionCorrectionLearningReason.PREDICTION_EPOCH_CHANGED)
		}
		if (advanceAt?.let { nowNanos < it } == true)
			return result(PositionCorrectionLearningDecision.REJECTED, PositionCorrectionLearningReason.STATE_TIME_ROLLBACK)
		return gap(nowNanos)
	}

	private fun gap(now: Long): PositionCorrectionLearningResult {
		val last = accepted
		val previousAdvance = advanceAt
		advanceAt = now
		if (last == null) return result(PositionCorrectionLearningDecision.HELD, PositionCorrectionLearningReason.NO_ACCEPTED_MEASUREMENT)
		val age = now - last.teacherSampleAtNanos
		if (age <= tuning.holdNanos) {
			phase = PositionCorrectionPhase.HOLDING
			return result(PositionCorrectionLearningDecision.HELD, PositionCorrectionLearningReason.HELD_WITHOUT_MEASUREMENT)
		}
		stableTracking = false
		resetRecovery()
		// age > hold proves teacherAt + hold <= now, so this addition cannot overflow.
		val boundary = last.teacherSampleAtNanos + tuning.holdNanos
		val dt = now - maxOf(previousAdvance ?: boundary, boundary)
		val seconds = dt / 1e9
		val alpha = -expm1(-seconds / tuning.decayTauSeconds)
		val rateLimit = tuning.maxDecayRateMetersPerSecond * seconds
		if (!alpha.isFinite() || !rateLimit.isFinite())
			return result(PositionCorrectionLearningDecision.REJECTED, PositionCorrectionLearningReason.NUMERIC_INVALID)
		val magnitude = correction.norm()
		val reduction = minOf(magnitude * alpha, rateLimit)
		// Scalar reduction along the vector prevents overshoot and preserves direction.
		if (magnitude > 0) correction = correction * ((magnitude - reduction).coerceAtLeast(0.0) / magnitude)
		if (correction.norm() <= tuning.zeroEpsilonMeters) {
			correction = DVector.ZERO
			phase = PositionCorrectionPhase.EXPIRED
			return result(PositionCorrectionLearningDecision.EXPIRED, PositionCorrectionLearningReason.CORRECTION_EXPIRED)
		}
		phase = PositionCorrectionPhase.DECAYING
		return result(PositionCorrectionLearningDecision.DECAYED, PositionCorrectionLearningReason.DECAYED_WITHOUT_MEASUREMENT)
	}

	private fun reject(sample: PositionErrorSample, reason: PositionCorrectionLearningReason, duplicate: Boolean = false): PositionCorrectionLearningResult {
		resetRecovery()
		// Rejected observations cannot refresh acceptance, hold age or the teacher learning clock.
		gap(sample.evaluatedAtNanos)
		return result(if (duplicate) PositionCorrectionLearningDecision.DUPLICATE else PositionCorrectionLearningDecision.REJECTED, reason)
	}

	private fun seed(sample: PositionErrorSample, identity: PositionCorrectionPhysicalWatermark) {
		stableTracking = false
		phase = PositionCorrectionPhase.REACQUIRING
		resetRecovery()
		accept(sample, identity)
		if ((DVector.from(sample.errorWorld) - correction).norm() <= tuning.recoveryResidualMeters) {
			recoveryStart = sample.teacherSampleAtNanos
			recoveryGoodSamples = 1
		}
		// Even a one-sample/zero-window policy does not promote a seed to TRACKING.
	}

	private fun accept(sample: PositionErrorSample, identity: PositionCorrectionPhysicalWatermark) {
		accepted = identity
		acceptedPredictionSequence = sample.predictionSequence // Diagnostics only.
		advanceAt = sample.evaluatedAtNanos
	}

	private fun recover(teacherAt: Long, remaining: Double) {
		if (remaining > tuning.recoveryResidualMeters) { resetRecovery(); return }
		if (recoveryStart == null) recoveryStart = teacherAt
		if (recoveryGoodSamples < Int.MAX_VALUE) recoveryGoodSamples++
		if (recoveryGoodSamples >= tuning.recoverySamples && teacherAt - recoveryStart!! >= tuning.recoveryStableNanos) {
			stableTracking = true
			phase = PositionCorrectionPhase.TRACKING
		}
	}

	private fun resetRecovery() { recoveryGoodSamples = 0; recoveryStart = null }
	private fun invalidate() {
		correction = DVector.ZERO
		phase = PositionCorrectionPhase.UNINITIALIZED
		lineage = null
		observed = null
		accepted = null
		acceptedPredictionSequence = null
		advanceAt = null
		stableTracking = false
		resetRecovery()
	}
	private fun result(decision: PositionCorrectionLearningDecision, reason: PositionCorrectionLearningReason) =
		PositionCorrectionLearningResult(decision, reason, snapshot())

	private fun progressionFailure(a: PositionCorrectionPhysicalWatermark, b: PositionCorrectionPhysicalWatermark): PositionCorrectionLearningReason? = when {
		b.teacherSequence < a.teacherSequence -> PositionCorrectionLearningReason.TEACHER_SEQUENCE_ROLLBACK
		b.hmdSequence < a.hmdSequence -> PositionCorrectionLearningReason.HMD_SEQUENCE_ROLLBACK
		b.imuSequence < a.imuSequence -> PositionCorrectionLearningReason.IMU_SEQUENCE_ROLLBACK
		b.teacherSequence == a.teacherSequence && b.teacherSampleAtNanos != a.teacherSampleAtNanos -> PositionCorrectionLearningReason.TEACHER_SAMPLE_IDENTITY_MISMATCH
		b.hmdSequence == a.hmdSequence && b.hmdSampleAtNanos != a.hmdSampleAtNanos -> PositionCorrectionLearningReason.HMD_SAMPLE_IDENTITY_MISMATCH
		b.imuSequence == a.imuSequence && b.imuSampleAtNanos != a.imuSampleAtNanos -> PositionCorrectionLearningReason.IMU_SAMPLE_IDENTITY_MISMATCH
		b.teacherSequence > a.teacherSequence && b.teacherSampleAtNanos <= a.teacherSampleAtNanos -> PositionCorrectionLearningReason.TEACHER_TIME_ROLLBACK
		b.hmdSampleAtNanos < a.hmdSampleAtNanos -> PositionCorrectionLearningReason.HMD_TIME_ROLLBACK
		b.imuSampleAtNanos < a.imuSampleAtNanos -> PositionCorrectionLearningReason.IMU_TIME_ROLLBACK
		else -> null
	}

	private fun validSample(s: PositionErrorSample): Boolean =
		s.target == dev.slimevr.tracking.trackers.TrackerPosition.HIP && s.bodyReference == PositionBodyReference.HIP_CENTER &&
		s.coordinateSpace == s.teacherEpoch.coordinateSpace && s.coordinateSpace == s.predictionEpoch.coordinateSpace &&
		s.assignmentGeneration == s.teacherEpoch.assignmentGeneration && s.assignmentGeneration == s.predictionEpoch.assignmentGeneration &&
		s.teacherSequence >= 0 && s.predictionHmdSequence >= 0 && s.predictionImuSequence >= 0 &&
		s.teacherSampleAtNanos in 0..s.evaluatedAtNanos && s.predictionHmdSampleAtNanos in 0..s.evaluatedAtNanos &&
		s.predictionImuSampleAtNanos in 0..s.evaluatedAtNanos &&
		s.predictionInputEarliestAtNanos == minOf(s.predictionHmdSampleAtNanos, s.predictionImuSampleAtNanos) &&
		s.predictionInputLatestAtNanos == maxOf(s.predictionHmdSampleAtNanos, s.predictionImuSampleAtNanos)

	/** Double scaled norm avoids Float squares/subtraction overflow. */
	private data class DVector(val x: Double, val y: Double, val z: Double) {
		operator fun plus(v: DVector) = DVector(x + v.x, y + v.y, z + v.z)
		operator fun minus(v: DVector) = DVector(x - v.x, y - v.y, z - v.z)
		operator fun times(scale: Double) = DVector(x * scale, y * scale, z * scale)
		fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
		fun norm(): Double {
			val scale = maxOf(abs(x), abs(y), abs(z))
			if (scale == 0.0) return 0.0
			return scale * sqrt((x / scale) * (x / scale) + (y / scale) * (y / scale) + (z / scale) * (z / scale))
		}
		fun bounded(limit: Double): DVector { val n = norm(); return if (n > limit) this * (limit / n) else this }
		// Directed Float conversion cannot expand the state sphere or produce Inf at the public snapshot boundary.
		fun snapshot() = Vector3(component(x), component(y), component(z))
		companion object {
			val ZERO = DVector(0.0, 0.0, 0.0)
			fun from(v: Vector3) = DVector(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
			private fun component(v: Double): Float {
				val f = abs(v).toFloat()
				val bounded = if (f.toDouble() > abs(v)) java.lang.Math.nextDown(f) else f
				return if (v < 0) -bounded else bounded
			}
		}
	}
}
