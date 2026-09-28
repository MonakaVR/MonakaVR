package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

enum class ContinuityState { MAIN_DIRECT, FALLBACK_ACTIVE, REACQUIRING, UNAVAILABLE }

/** Provisional software defaults; none of these values is calibrated on hardware. */
data class ContinuityTuning(
	val stableFullDwellMs: Long = 150,
	val reacquireDurationMs: Long = 300,
	val fallbackBlendMs: Long = 150,
) {
	init {
		require(stableFullDwellMs in 1..10_000 && reacquireDurationMs in 1..10_000 && fallbackBlendMs in 1..10_000)
	}
	val stableFullDwellNs get() = stableFullDwellMs * 1_000_000
	val reacquireDurationNs get() = reacquireDurationMs * 1_000_000
	val fallbackBlendNs get() = fallbackBlendMs * 1_000_000
}

data class OutputTransition(
	val target: TrackerPosition,
	val state: ContinuityState,
	val main: MainSampleState,
	val positionOwner: String?,
	val rotationOwner: String?,
	val outputPositionSource: OutputPositionSource,
	val outputRotationOwner: String?,
	val backgroundAvailable: Boolean,
	val reason: String,
)

/** One controller per stable virtual tracker. All time comes from the server's clock. */
class OutputContinuityController(
	private val target: TrackerPosition,
	private val policy: ContinuityPolicy,
	private val tuning: ContinuityTuning = ContinuityTuning(),
	private val onTransition: (OutputTransition) -> Unit = {},
) {
	var state = ContinuityState.UNAVAILABLE
		private set
	var transitionStartedAt = 0L
		private set
	var lastOutput: OutputPose? = null
		private set
	var mainPose: ResolvedTrackingPose? = null
		private set
	var fallbackPose: OutputPose? = null
		private set
	var blendProgress: Float? = null
		private set
	var positionResidual: Float? = null
		private set
	var rotationResidual: Float? = null
		private set
	private var previousEvent: OutputTransition? = null
	private var lossSeen = false
	private var lastTime = -1L
	private var fullSince: Long? = null
	private var firstFullSampleAt = -1L
	private var latestFullSampleAt = -1L
	private var fallbackStart: OutputPose? = null
	private var fallbackStartedAt = 0L
	private var reacquireStart: OutputPose? = null

	private fun fraction(now: Long, start: Long, duration: Long) =
		((now - start).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
	private fun interpolate(a: Vector3, b: Vector3, t: Float) = a * (1f - t) + b * t
	private fun interpolate(a: Quaternion, b: Quaternion, t: Float) = a.unit().interpR(b.unit(), t).unit()
	private fun blend(start: OutputPose, end: OutputPose, t: Float, source: OutputPositionSource, now: Long): OutputPose {
		val position = ResolvedComponent(interpolate(start.position!!.value, end.position!!.value, t),
			if (source == OutputPositionSource.CONVERGENCE) "monaka-solver:convergence:${target.name}" else end.position.sourceId,
			end.position.quality, now)
		val rotation = ResolvedComponent(interpolate(start.rotation!!.value, end.rotation!!.value, t),
			if (source == OutputPositionSource.CONVERGENCE) "monaka-solver:convergence:${target.name}" else end.rotation.sourceId,
			end.rotation.quality, now)
		return OutputPose(target, end.space, position, rotation, source)
	}

	fun update(main: ResolvedTrackingPose, background: BackgroundIkResult, now: Long, paused: Boolean = false): OutputPose {
		require(main.target == target && now >= 0 && now >= lastTime)
		val previousTick = lastTime
		lastTime = now
		mainPose = main
		// A prior solver frame or unaligned pose is never eligible, even for reacquisition.
		val fallback = background.pose?.takeIf {
			!paused && it.target == target && it.space == main.space && it.positionValid && it.rotationValid &&
				it.positionSource == OutputPositionSource.BACKGROUND_IK &&
				it.position!!.observedAtNanos == now && it.rotation!!.observedAtNanos == now
		}
		fallbackPose = fallback
		positionResidual = if (main.position != null && fallback?.position != null) (main.position.value - fallback.position.value).len() else null
		rotationResidual = if (main.rotation != null && fallback?.rotation != null) {
			val a = main.rotation.value; val b = fallback.rotation.value
			2f * kotlin.math.acos(kotlin.math.abs(a.w*b.w + a.x*b.x + a.y*b.y + a.z*b.z).coerceIn(0f, 1f))
		} else null
		val full = main.positionValid && main.rotationValid
		var next: ContinuityState
		var reason: String
		var output = main.directOutput()
		blendProgress = null
		when {
			paused -> {
				next = ContinuityState.UNAVAILABLE; reason = "paused"
				lossSeen = false; fullSince = null; firstFullSampleAt = -1L; latestFullSampleAt = -1L
				fallbackStart = null; reacquireStart = null
				output = OutputPose(target, main.space)
			}
			policy == ContinuityPolicy.NONE -> {
				next = if (full) ContinuityState.MAIN_DIRECT else if (main.rotationValid) ContinuityState.FALLBACK_ACTIVE else ContinuityState.UNAVAILABLE
				reason = "resolved_components_only"
			}
			full && !lossSeen -> { next = ContinuityState.MAIN_DIRECT; reason = "main_full" }
			full -> {
				// Both selected components must have been observed for this FULL sample.
				val sampleAt = minOf(main.position!!.observedAtNanos, main.rotation!!.observedAtNanos)
				if (fullSince == null) {
					fullSince = now; firstFullSampleAt = sampleAt; latestFullSampleAt = sampleAt
				}
				latestFullSampleAt = maxOf(latestFullSampleAt, sampleAt)
				val previous = lastOutput?.takeIf { it.positionValid && it.rotationValid && it.space == main.space } ?: fallback
				if (previous == null) {
					next = ContinuityState.UNAVAILABLE; reason = "reacquisition_background_unavailable:${background.reason}"
					output = OutputPose(target, main.space)
					reacquireStart = null; fullSince = null; firstFullSampleAt = -1L; latestFullSampleAt = -1L
				} else if (reacquireStart == null && latestFullSampleAt - firstFullSampleAt < tuning.stableFullDwellNs) {
					next = ContinuityState.FALLBACK_ACTIVE
					reason = if (fallback == null) "main_full_dwell_background_missing" else "main_full_dwell"
					// Eligibility stays with the resolver. Smoothly follow its current rotation,
					// and approach current IK position only while that frame is valid.
					val progress = if (now == fullSince) 0f else fraction(now, previousTick, tuning.stableFullDwellNs)
					val position = fallback?.position?.let {
						ResolvedComponent(interpolate(previous.position!!.value, it.value, progress), it.sourceId, it.quality, now)
					} ?: previous.position
					val selectedRotation = main.rotation!!
					val rotation = ResolvedComponent(interpolate(previous.rotation!!.value, selectedRotation.value, progress),
						selectedRotation.sourceId, selectedRotation.quality, now)
					output = OutputPose(target, main.space, position, rotation,
						if (fallback == null) previous.positionSource else OutputPositionSource.BACKGROUND_IK)
				} else {
					if (reacquireStart == null) {
						reacquireStart = previous
						transitionStartedAt = now
					}
					val progress = fraction(now, transitionStartedAt, tuning.reacquireDurationNs)
					blendProgress = progress
					if (progress == 1f) {
						next = ContinuityState.MAIN_DIRECT; reason = "convergence_complete"
						output = main.directOutput(); lossSeen = false; fullSince = null; firstFullSampleAt = -1L
						latestFullSampleAt = -1L; reacquireStart = null
					} else {
						next = ContinuityState.REACQUIRING
						reason = if (fallback == null) "main_full_stable_background_missing" else "main_full_stable"
						output = blend(reacquireStart!!, main.directOutput(), progress, OutputPositionSource.CONVERGENCE, now)
					}
				}
			}
			else -> {
				fullSince = null; firstFullSampleAt = -1L; latestFullSampleAt = -1L
				reacquireStart = null
				val position = if (main.rotationValid) fallback?.position else null
				lossSeen = lossSeen || lastOutput?.positionValid == true || position != null
				val selected = OutputPose(target, main.space, position, main.rotation,
					if (position == null) OutputPositionSource.NONE else OutputPositionSource.BACKGROUND_IK)
				if (position != null && state != ContinuityState.FALLBACK_ACTIVE) {
					fallbackStart = lastOutput?.takeIf { it.positionValid && it.rotationValid && it.space == main.space }
					fallbackStartedAt = now
				}
				output = if (position != null && fallbackStart != null)
					blend(fallbackStart!!, selected, fraction(now, fallbackStartedAt, tuning.fallbackBlendNs), OutputPositionSource.BACKGROUND_IK, now)
				else selected
				if (position == null || now - fallbackStartedAt >= tuning.fallbackBlendNs) fallbackStart = null
				next = if (main.rotationValid) ContinuityState.FALLBACK_ACTIVE else ContinuityState.UNAVAILABLE
				reason = if (position != null) "main_loss_current_background" else "main_loss_no_pose_fallback:${background.reason}"
			}
		}
		if (next != state) transitionStartedAt = now
		state = next; lastOutput = output
		val event = OutputTransition(target, state, main.main, main.position?.sourceId, main.rotation?.sourceId,
			output.positionSource, output.rotation?.sourceId, fallback != null, reason)
		if (event != previousEvent) { previousEvent = event; onTransition(event) }
		return output
	}
}
