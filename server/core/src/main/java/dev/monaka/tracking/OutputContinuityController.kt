package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Vector3

enum class ContinuityState { MAIN_DIRECT, FALLBACK_ACTIVE, REACQUIRING, UNAVAILABLE }

data class ReacquisitionContext(
	val startedAt: Long,
	val now: Long,
	val lastOutput: OutputPose?,
	val background: OutputPose,
	val main: ResolvedTrackingPose,
	val positionResidual: Float,
	val rotationResidual: Float,
)

/** Only convergence, never eligibility or source selection. No tuned algorithm is implied. */
fun interface ReacquisitionStrategy {
	fun advance(context: ReacquisitionContext): ConvergenceStep?
	companion object {
		// Production foundation deliberately waits; it must not snap to reacquired Main.
		val NOT_CONFIGURED = ReacquisitionStrategy { null }
	}
}
data class ConvergenceStep(val position: Vector3, val progress: Float, val complete: Boolean)

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
	private val reacquisition: ReacquisitionStrategy = ReacquisitionStrategy.NOT_CONFIGURED,
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

	fun update(main: ResolvedTrackingPose, background: BackgroundIkResult, now: Long, paused: Boolean = false): OutputPose {
		require(main.target == target && now >= 0 && now >= lastTime)
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
				next = ContinuityState.UNAVAILABLE; reason = "paused"; lossSeen = lossSeen || lastOutput?.positionValid == true
				output = OutputPose(target, main.space)
			}
			policy == ContinuityPolicy.NONE -> {
				next = if (full) ContinuityState.MAIN_DIRECT else if (main.rotationValid) ContinuityState.FALLBACK_ACTIVE else ContinuityState.UNAVAILABLE
				reason = "resolved_components_only"
			}
			full && !lossSeen -> { next = ContinuityState.MAIN_DIRECT; reason = "main_full" }
			full -> {
				next = ContinuityState.REACQUIRING; reason = "reacquisition_policy_pending"
				if (state != next) transitionStartedAt = now
				output = OutputPose(target, main.space, fallback?.position, main.rotation,
					if (fallback == null) OutputPositionSource.NONE else OutputPositionSource.BACKGROUND_IK)
				if (fallback != null) {
					val step = reacquisition.advance(ReacquisitionContext(transitionStartedAt, now, lastOutput, fallback, main, positionResidual!!, rotationResidual!!))
					if (step != null) {
						require(step.progress.isFinite() && step.progress in 0f..1f && listOf(step.position.x, step.position.y, step.position.z).all { it.isFinite() })
						require(!step.complete || (step.progress == 1f && step.position == main.position!!.value))
						blendProgress = step.progress
						output = output.copy(position = ResolvedComponent(step.position, "monaka-solver:convergence:${target.name}", ObservationQuality.TRACKED, now), positionSource = OutputPositionSource.CONVERGENCE)
						if (step.complete) { next = ContinuityState.MAIN_DIRECT; lossSeen = false; output = main.directOutput(); reason = "convergence_complete" }
					}
				} else reason = "reacquisition_background_unavailable:${background.reason}"
			}
			else -> {
				val position = if (main.rotationValid) fallback?.position else null
				lossSeen = lossSeen || lastOutput?.positionValid == true || position != null
				output = OutputPose(target, main.space, position, main.rotation,
					if (position == null) OutputPositionSource.NONE else OutputPositionSource.BACKGROUND_IK)
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
