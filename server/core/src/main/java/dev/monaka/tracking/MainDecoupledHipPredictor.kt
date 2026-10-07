package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs

/** Explicit local monotonic time limits. Inclusive; zero is exact-only. No runtime/HIL defaults. */
data class MainDecoupledHipPredictorPolicy(
	val maxInputSkewNanos: Long,
	val maxHmdSampleAgeNanos: Long,
	val maxImuSampleAgeNanos: Long,
) {
	init {
		require(maxInputSkewNanos >= 0 && maxHmdSampleAgeNanos >= 0 && maxImuSampleAgeNanos >= 0)
	}
}

/**
 * Stateless, dormant Slime central-chain baseline with HMD + HIP rotation tracker only.
 * HEAD/NECK use calibrated HEAD-anchor reference axes, before legacy rotationOffset;
 * UPPER_CHEST/CHEST/WAIST/HIP use independent IMU body axes. Rotating the signed
 * configured vectors directly is equivalent to Bone length + geometry rotationOffset.
 * HIP_CENTER is the central HIP tail, without virtual tracker placement offsets.
 * No Main, skeleton, solver, correction, clock, output or runtime service is accessed.
 */
class PureMainDecoupledHipPredictor(
	private val policy: MainDecoupledHipPredictorPolicy,
) : MainDecoupledHipPredictor {
	override fun predict(input: MainDecoupledHipInput): PositionPrediction {
		fun unavailable() = PositionPrediction.unavailable(TrackerPosition.HIP, input.space)
		val hmdAt = input.rawHmd.provenance.sampleAtNanos
		val imuAt = input.rawImu.provenance.sampleAtNanos
		val now = input.nowNanos
		// Input constructors also guard future samples; retain the algorithm's explicit fail-closed gates.
		if (hmdAt > now || imuAt > now) return unavailable()
		// 0 <= samples <= now <= MAX makes ordered subtraction exact, including Long boundaries.
		if (now - hmdAt > policy.maxHmdSampleAgeNanos || now - imuAt > policy.maxImuSampleAgeNanos)
			return unavailable()
		val earliest = minOf(hmdAt, imuAt)
		val latest = maxOf(hmdAt, imuAt)
		if (latest - earliest > policy.maxInputSkewNanos) return unavailable()

		val position = hipPosition(input) ?: return unavailable()
		return PositionPrediction.available(
			TrackerPosition.HIP, position, input.space, PositionBodyReference.HIP_CENTER,
			PositionPredictionProvenance(input.predictionSequence, now, earliest, latest, input.epoch(),
				input.rawHmd.provenance.sequence, hmdAt, input.rawImu.provenance.sequence, imuAt),
			setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
				PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION),
		)
	}

	private fun hipPosition(input: MainDecoupledHipInput): Vector3? {
		val qWH = safeUnit(input.rawHmd.orientation) ?: return null
		val qWB = safeUnit(input.rawImu.orientation) ?: return null
		val fixed = input.fixedCalibration
		val qWA = safeUnit(qWH * fixed.hmdToHeadAnchorOrientation) ?: return null
		val anchorOffset = qWH.sandwich(fixed.hmdToHeadAnchorLocalOffset)
		if (!finite(anchorOffset)) return null
		var position = input.rawHmd.position + anchorOffset
		if (!finite(position)) return null
		val body = input.bodyModel
		val chain = listOf(
			qWA to Vector3(0f, 0f, body.headShift),
			qWA to Vector3(0f, -body.neckLength, 0f),
			qWB to Vector3(0f, -body.upperChestLength, 0f),
			qWB to Vector3(0f, -body.chestLength, 0f),
			qWB to Vector3(0f, -body.waistLength, 0f),
			qWB to Vector3(0f, -body.hipLength, 0f),
		)
		for ((orientation, vector) in chain) {
			val rotated = orientation.sandwich(vector)
			if (!finite(rotated)) return null
			position += rotated
			if (!finite(position)) return null
		}
		return position
	}
}

/** Same maximum-component scaling audited in 5V/5Z; no change to their canonicalization semantics. */
private fun safeUnit(q: Quaternion): Quaternion? {
	val components = listOf(q.w, q.x, q.y, q.z)
	if (!components.all(Float::isFinite)) return null
	val scale = components.maxOf { abs(it) }
	if (scale == 0f) return null
	val unit = Quaternion(q.w / scale, q.x / scale, q.y / scale, q.z / scale).unit()
	return unit.takeIf {
		listOf(it.w, it.x, it.y, it.z).all(Float::isFinite) && it.lenSq().isFinite() && it.lenSq() > 0f
	}
}

private fun finite(v: Vector3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
