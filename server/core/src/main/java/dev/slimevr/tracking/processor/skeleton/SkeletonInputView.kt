package dev.slimevr.tracking.processor.skeleton

import dev.slimevr.tracking.trackers.Tracker

/** FK needs rotational inputs; IK may also consume position-only constraints. */
data class SkeletonInputView(val rotations: List<Tracker>, val constraints: List<Tracker>)
