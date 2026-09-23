package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Native skeleton-world sample, obtained only after the current HumanPoseManager update. */
data class SkeletonWorldPose(val target: TrackerPosition, val position: Vector3, val rotation: Quaternion, val evaluatedAt: Long)

/** Future calibrated transform belongs here, not in resolver selection or MTP decoding. */
fun interface BackgroundIkAlignment {
	fun toMonakaWorld(pose: SkeletonWorldPose, requestedSpace: CoordinateSpace): OutputPose?

	companion object {
		val UNVERIFIED = BackgroundIkAlignment { _, _ -> null }
		/** Explicit operator assertion; not automatic calibration or an HIL PASS. */
		fun confirmedSameSpace(space: CoordinateSpace) = BackgroundIkAlignment { pose, requested ->
			if (space != requested) null else OutputPose(
				pose.target, space,
				ResolvedComponent(pose.position, "monaka-solver:${pose.target.name}", ObservationQuality.TRACKED, pose.evaluatedAt),
				ResolvedComponent(pose.rotation, "monaka-solver:${pose.target.name}", ObservationQuality.TRACKED, pose.evaluatedAt),
				OutputPositionSource.BACKGROUND_IK,
			)
		}
	}
}

data class BackgroundIkResult(val pose: OutputPose?, val reason: String)

/** Does not solve, calibrate, hold, or create a second skeleton. */
class BackgroundIkPoseReader(private val skeleton: HumanSkeleton, private val alignment: BackgroundIkAlignment) {
	var framesRead: Long = 0
		private set
	fun read(target: TrackerPosition, space: CoordinateSpace, now: Long): BackgroundIkResult {
		framesRead++
		if (skeleton.getPauseTracking()) return BackgroundIkResult(null, "paused")
		if (!skeleton.ikSolver.enabled) return BackgroundIkResult(null, "solver_disabled")
		val anchor = skeleton.headTracker
		if (anchor == null || !anchor.hasPosition || anchor.status != TrackerStatus.OK ||
			anchor.sampleModality in setOf(TrackingModality.ROTATION_ONLY, TrackingModality.NONE) || !finite(anchor.position)
		) return BackgroundIkResult(null, "no_valid_root_anchor")
		val role = target.trackerRole ?: return BackgroundIkResult(null, "no_computed_role")
		val computed = try { skeleton.getComputedTracker(role) } catch (_: IllegalArgumentException) {
			return BackgroundIkResult(null, "no_computed_role")
		}
		val p = computed.position; val q = computed.getRotation()
		if (!finite(p) || !listOf(q.w, q.x, q.y, q.z).all { it.isFinite() } || !q.lenSq().isFinite() || q.lenSq() <= 0f)
			return BackgroundIkResult(null, "nonfinite_or_invalid_solver_pose")
		val aligned = alignment.toMonakaWorld(SkeletonWorldPose(target, p, q, now), space)
		return if (aligned == null) BackgroundIkResult(null, "alignment_unverified") else BackgroundIkResult(aligned, "current_solver_frame")
	}
	private fun finite(v: Vector3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
}
