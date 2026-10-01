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
	val maxImuSampleAgeNanos: Long,
) {
	init {
		require(fullStableNanos > 0 && pairWindowNanos > 0 && maxDtNanos > 0 &&
			maxImuSampleAgeNanos > 0 && recoveryPairs > 0)
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
	private var lastObservedMainSequence: Long? = null
	private var lastObservedMainAtNanos: Long? = null
	private var lastObservedImuSequence: Long? = null
	private var lastObservedImuAtNanos: Long? = null
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
		goodRecoveryPairs = 0
		lastObservedMainSequence = null; lastObservedMainAtNanos = null
		lastObservedImuSequence = null; lastObservedImuAtNanos = null
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
		// Application integrity is checked independently of teacher synchronization.
		if (expectedSpace != frames.assertedSpace || (ip?.space != null && ip.space != expectedSpace)) {
			reject("space_mismatch"); return null
		}
		if (imu == null || ip == null || currentImuKey == null) { reject("provenance_missing"); return null }
		if (FeedbackExclusion.isOutput(imu.sourceId)) { reject("feedback_excluded"); return null }
		if (ip.sampleAtNanos > now || now - ip.sampleAtNanos > tuning.maxImuSampleAgeNanos ||
			!imu.rotationQuality.usable) { reject("imu_sample_stale"); return null }
		val imuRotation = imu.correctionRotation
		if (imuRotation == null || !validUnit(imuRotation)) { reject("imu_rotation_invalid"); return null }
		if (lastObservedImuSequence != null && (ip.sequence < lastObservedImuSequence!! ||
			(ip.sequence == lastObservedImuSequence && ip.sampleAtNanos != lastObservedImuAtNanos))) {
			reject("imu_sequence_rollback"); return null
		}
		if (lastObservedImuAtNanos != null && ip.sequence > lastObservedImuSequence!! &&
			ip.sampleAtNanos <= lastObservedImuAtNanos!!) { reject("imu_timestamp_rollback"); return null }
		lastObservedImuSequence = ip.sequence
		lastObservedImuAtNanos = ip.sampleAtNanos

		// Teacher failures must not discard a ready correction for this healthy IMU.
		if (modality != TrackingModality.NONE && main != null && FeedbackExclusion.isOutput(main.sourceId)) {
			reject("feedback_excluded")
		} else if (modality != TrackingModality.NONE && main != null && mp != null &&
			(mp.space == null || mp.space == expectedSpace) && !FeedbackExclusion.isOutput(main.sourceId)) {
			tryLearn(main, mp, imuRotation, ip, now, modality)
		} else if (modality != TrackingModality.NONE) reject("main_teacher_unavailable")
		return correctedFallback(imu, ip, owner, currentImuKey)
	}

	private fun tryLearn(main: PoseObservation, mp: ObservationSampleProvenance, imuRotation: Quaternion,
		ip: ObservationSampleProvenance, now: Long, modality: TrackingModality) {
		val mainRotation = main.correctionRotation
		if (!main.rotationQuality.usable || mainRotation == null || !validUnit(mainRotation)) {
			reject("main_rotation_invalid"); return
		}
		if (mp.sampleAtNanos > now) { reject("pair_time_invalid"); return }
		if (lastObservedMainSequence != null && mp.sequence < lastObservedMainSequence!!) {
			reject("main_sequence_rollback"); return
		}
		if (lastObservedMainSequence != null && mp.sequence == lastObservedMainSequence &&
			mp.sampleAtNanos != lastObservedMainAtNanos) {
			reject("main_timestamp_rollback"); return
		}
		if (lastObservedMainAtNanos != null && mp.sequence > lastObservedMainSequence!! &&
			mp.sampleAtNanos <= lastObservedMainAtNanos!!) {
			reject("main_timestamp_rollback"); return
		}
		lastObservedMainSequence = mp.sequence
		lastObservedMainAtNanos = mp.sampleAtNanos
		if (abs(mp.sampleAtNanos - ip.sampleAtNanos) > tuning.pairWindowNanos) {
			reject("pair_time_invalid"); return
		}
		if (mp.sequence == lastMainSequence || ip.sequence == lastImuSequence) { reject("same_sample"); return }
		// Application-stage watermark already rejected a rollback of the IMU itself.
		lastMainSequence = mp.sequence; lastImuSequence = ip.sequence
		val pairAt = maxOf(mp.sampleAtNanos, ip.sampleAtNanos)
		val prior = lastPairAtNanos
		if (prior == null) {
			lastPairAtNanos = pairAt
			if (modality == TrackingModality.FULL) { firstFullAt = mp.sampleAtNanos; latestFullAt = firstFullAt }
			return
		}
		val dtNanos = pairAt - prior
		if (dtNanos <= 0) { reject("pair_timestamp_rollback"); return }
		if (dtNanos > tuning.maxDtNanos) {
			reject("large_dt"); lastPairAtNanos = pairAt
			firstFullAt = null; latestFullAt = null; goodRecoveryPairs = 0
			return
		}
		val qMain = (mainRotation * frames.mainTrackerToBody).unit()
		val qImu = (imuRotation * frames.fallbackToBody).unit()
		val corrected = (correction * qImu).unit()
		val error = angle(qMain, corrected)
		residualRadians = error
		lastPairAtNanos = pairAt
		if (error > tuning.maxResidualRadians) { reject("residual_outlier"); goodRecoveryPairs = 0; return }
		if (modality == TrackingModality.FULL) {
			if (firstFullAt == null) firstFullAt = mp.sampleAtNanos
			latestFullAt = mp.sampleAtNanos
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
