package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.min

/** All values are opt-in, provisional software limits; none is HIL tuned. */
data class RotationCorrectionTuning(
	val fullStableNanos: Long,
	val pairWindowNanos: Long,
	val trackingTauSeconds: Double,
	val recoveryTauSeconds: Double,
	val maxResidualRadians: Double,
	val recoveryResidualRadians: Double,
	val maxRadiansPerSecond: Double,
	val recoveryPairs: Int,
	val maxDtNanos: Long,
) {
	init {
		require(fullStableNanos > 0 && pairWindowNanos > 0 && maxDtNanos > 0 && recoveryPairs > 0)
		require(listOf(trackingTauSeconds, recoveryTauSeconds, maxResidualRadians,
			recoveryResidualRadians, maxRadiansPerSecond).all { it.isFinite() && it > 0 })
	}
}

/** q(W<-B) = q(W<-T) * q(T<-B). IMU's independent orientation already includes Slime mounting/reset. */
data class RotationCorrectionFrames(
	val mainTrackerToBody: Quaternion,
	val fallbackToBody: Quaternion,
	val assertedSpace: CoordinateSpace,
	val operatorConfirmed: Boolean,
) {
	init {
		require(operatorConfirmed) { "Correction requires explicit operator confirmation of both body-frame transforms" }
		require(validUnit(mainTrackerToBody) && validUnit(fallbackToBody))
	}
}

data class RotationCorrectionConfig(val frames: RotationCorrectionFrames, val tuning: RotationCorrectionTuning)

enum class RotationCorrectionState { UNINITIALIZED, REACQUIRING, TRACKING, DEGRADED, IMU_ONLY }

/** Server-thread-only learner. Resolver ownership is passed in; it is never inferred here. */
class HipRotationCorrection(
	private val frames: RotationCorrectionFrames,
	private val tuning: RotationCorrectionTuning,
) {
	var state = RotationCorrectionState.UNINITIALIZED
		private set
	var ready = false
		private set
	var correction = Quaternion.IDENTITY
		private set
	var residualRadians: Double? = null
		private set
	var lastPairAtNanos: Long? = null
		private set
	var lastLearnedAtNanos: Long? = null
		private set
	fun correctionAgeNanos(now: Long): Long? = lastLearnedAtNanos?.let { (now - it).coerceAtLeast(0) }
	var lastMainSequence: Long? = null
		private set
	var lastImuSequence: Long? = null
		private set
	private var lastMainAtNanos: Long? = null
	private var lastImuAtNanos: Long? = null
	val rejections = linkedMapOf<String, Long>()
	private var epoch: String? = null
	private var imuEpochKey: String? = null
	private var activeAssignmentGeneration: Long? = null
	private var firstFullAt: Long? = null
	private var latestFullAt: Long? = null
	private var goodRecoveryPairs = 0

	private fun reject(reason: String) { rejections[reason] = (rejections[reason] ?: 0) + 1 }
	private fun key(source: String, p: ObservationSampleProvenance) =
		"$source|${p.sourceEpoch}|${p.calibrationEpoch}|${p.mappingRevision}|${p.space}"
	private fun invalidate() {
		ready = false; state = RotationCorrectionState.UNINITIALIZED
		correction = Quaternion.IDENTITY; residualRadians = null
		lastLearnedAtNanos = null
		firstFullAt = null; latestFullAt = null; lastPairAtNanos = null
		lastMainSequence = null; lastImuSequence = null
		lastMainAtNanos = null; lastImuAtNanos = null; goodRecoveryPairs = 0
	}

	fun update(main: PoseObservation?, imu: PoseObservation?, owner: String?, now: Long,
		assignmentGeneration: Long, expectedSpace: CoordinateSpace): EffectiveConstraint? {
		require(now >= 0)
		val mp = main?.provenance
		val ip = imu?.provenance
		val newEpoch = if (main != null && imu != null && mp != null && ip != null)
			"${key(main.sourceId, mp)}|${key(imu.sourceId, ip)}|$assignmentGeneration|$expectedSpace"
		else null
		val currentImuKey = if (imu != null && ip != null) key(imu.sourceId, ip) else null
		if (ready && (activeAssignmentGeneration != assignmentGeneration || expectedSpace != frames.assertedSpace ||
			(currentImuKey != null && currentImuKey != imuEpochKey))) {
			invalidate(); epoch = null; imuEpochKey = null
		}
		if (newEpoch != null && newEpoch != epoch) {
			invalidate(); epoch = newEpoch; imuEpochKey = currentImuKey
			activeAssignmentGeneration = assignmentGeneration
		}
		val mainRotationUsable = main?.rotation != null && main.rotationQuality.usable
		val modality = when {
			main?.modality == TrackingModality.FULL && mainRotationUsable && main.position != null && main.positionQuality.usable -> TrackingModality.FULL
			main?.modality == TrackingModality.ROTATION_ONLY && mainRotationUsable -> TrackingModality.ROTATION_ONLY
			else -> TrackingModality.NONE
		}
		state = when (modality) {
			TrackingModality.NONE -> RotationCorrectionState.IMU_ONLY
			TrackingModality.ROTATION_ONLY -> RotationCorrectionState.DEGRADED
			TrackingModality.FULL -> if (ready && state == RotationCorrectionState.TRACKING)
				RotationCorrectionState.TRACKING else RotationCorrectionState.REACQUIRING
		}
		if (modality != TrackingModality.FULL) { firstFullAt = null; latestFullAt = null; goodRecoveryPairs = 0 }
		if (expectedSpace != frames.assertedSpace ||
			(mp?.space != null && mp.space != expectedSpace) ||
			(ip?.space != null && ip.space != expectedSpace)) { reject("space_mismatch"); return null }
		if (newEpoch == null) {
			if (currentImuKey == null || imu == null || ip == null) { reject("provenance_missing"); return null }
			return correctedFallback(imu, ip, owner, currentImuKey)
		}
		val mainSample = requireNotNull(main)
		val imuSample = requireNotNull(imu)
		if (FeedbackExclusion.isOutput(mainSample.sourceId) || FeedbackExclusion.isOutput(imuSample.sourceId)) {
			reject("feedback_excluded"); return null
		}
		val mainProvenance = requireNotNull(mp)
		val imuProvenance = requireNotNull(ip)
		if (modality == TrackingModality.NONE) return correctedFallback(imuSample, imuProvenance, owner, currentImuKey)
		val mq = mainSample.correctionRotation
		val iq = imuSample.correctionRotation
		if (!mainSample.rotationQuality.usable || !imuSample.rotationQuality.usable || mq == null || iq == null ||
			!validUnit(mq) || !validUnit(iq)) { reject("rotation_invalid"); return null }
		if (mainProvenance.sampleAtNanos > now || imuProvenance.sampleAtNanos > now ||
			abs(mainProvenance.sampleAtNanos - imuProvenance.sampleAtNanos) > tuning.pairWindowNanos) {
			reject("pair_time_invalid"); return null
		}
		val pairAt = maxOf(mainProvenance.sampleAtNanos, imuProvenance.sampleAtNanos)
		val newPair = mainProvenance.sequence != lastMainSequence && imuProvenance.sequence != lastImuSequence
		if (newPair) {
			if ((lastMainSequence != null && mainProvenance.sequence <= lastMainSequence!!) ||
				(lastImuSequence != null && imuProvenance.sequence <= lastImuSequence!!)) { reject("sequence_rollback"); return null }
			if ((lastMainAtNanos != null && mainProvenance.sampleAtNanos <= lastMainAtNanos!!) ||
				(lastImuAtNanos != null && imuProvenance.sampleAtNanos <= lastImuAtNanos!!)) {
				reject("timestamp_rollback"); return null
			}
			lastMainSequence = mainProvenance.sequence; lastImuSequence = imuProvenance.sequence
			lastMainAtNanos = mainProvenance.sampleAtNanos; lastImuAtNanos = imuProvenance.sampleAtNanos
			val prior = lastPairAtNanos
			if (prior == null) {
				lastPairAtNanos = pairAt
				if (modality == TrackingModality.FULL) { firstFullAt = mainProvenance.sampleAtNanos; latestFullAt = firstFullAt }
			} else {
				val dtNanos = pairAt - prior
				if (dtNanos <= 0) reject("timestamp_rollback")
				else if (dtNanos > tuning.maxDtNanos) {
					reject("large_dt"); lastPairAtNanos = pairAt
					firstFullAt = null; latestFullAt = null; goodRecoveryPairs = 0
				}
				else {
					val qMain = (mq * frames.mainTrackerToBody).unit()
					val qImu = (iq * frames.fallbackToBody).unit()
					val corrected = (correction * qImu).unit()
					val error = angle(qMain, corrected)
					residualRadians = error
					lastPairAtNanos = pairAt
					if (error > tuning.maxResidualRadians) { reject("residual_outlier"); goodRecoveryPairs = 0 }
					else {
						if (modality == TrackingModality.FULL) {
							if (firstFullAt == null) firstFullAt = mainProvenance.sampleAtNanos
							latestFullAt = mainProvenance.sampleAtNanos
						}
						val target = (qMain * qImu.inv()).unit()
						val dt = dtNanos / 1_000_000_000.0
						val tau = if (state == RotationCorrectionState.TRACKING) tuning.trackingTauSeconds else tuning.recoveryTauSeconds
						val alpha = 1 - exp(-dt / tau)
						val step = angle(correction, target)
						val beta = min(alpha, tuning.maxRadiansPerSecond * dt / maxOf(step, 1e-9))
						correction = correction.interpR(if (correction.dot(target) < 0f) -target else target, beta.toFloat()).unit()
						lastLearnedAtNanos = pairAt
						if (state == RotationCorrectionState.REACQUIRING && firstFullAt != null && latestFullAt!! - firstFullAt!! >= tuning.fullStableNanos) {
							val remaining = angle(qMain, (correction * qImu).unit())
							goodRecoveryPairs = if (remaining <= tuning.recoveryResidualRadians) goodRecoveryPairs + 1 else 0
							if (goodRecoveryPairs >= tuning.recoveryPairs) { ready = true; state = RotationCorrectionState.TRACKING }
						}
					}
				}
			}
		} else reject("same_sample")
		if (epoch != newEpoch) return null
		return correctedFallback(imuSample, imuProvenance, owner, currentImuKey)
	}

	private fun correctedFallback(imu: PoseObservation, provenance: ObservationSampleProvenance,
		owner: String?, currentImuKey: String?): EffectiveConstraint? {
		val q = imu.correctionRotation
		if (!ready || currentImuKey != imuEpochKey || owner != imu.sourceId ||
			!imu.rotationQuality.usable || q == null || !validUnit(q)) return null
		val result = (correction * (q * frames.fallbackToBody).unit()).unit()
		return EffectiveConstraint(TrackerPosition.HIP, rotation = ResolvedComponent(result, owner,
			imu.rotationQuality, provenance.sampleAtNanos))
	}
}

private fun validUnit(q: Quaternion): Boolean {
	val norm = q.lenSq()
	return listOf(q.w, q.x, q.y, q.z).all { it.isFinite() } && norm.isFinite() && norm > 1e-10f
}
private fun angle(a: Quaternion, b: Quaternion): Double =
	2 * acos(abs(a.unit().dot(b.unit()).toDouble()).coerceIn(0.0, 1.0))
