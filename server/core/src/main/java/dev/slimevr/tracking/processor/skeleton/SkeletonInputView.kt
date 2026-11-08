package dev.slimevr.tracking.processor.skeleton

import dev.slimevr.tracking.trackers.Tracker
import io.github.axisangles.ktmath.Quaternion

/** Consumer-owned anatomical reset event; never an input wire or producer command. */
enum class ConstraintResetKind { FULL, MOUNTING }

/** FK needs rotational inputs; IK may also consume position-only constraints. */
data class SkeletonInputView(
	val rotations: List<Tracker>,
	val constraints: List<Tracker>,
	val retainedCalibrationNames: Set<String> = constraints.map { it.name }.toSet(),
	val onReset: ((ConstraintResetKind, Quaternion, List<Int>) -> Unit)? = null,
)
