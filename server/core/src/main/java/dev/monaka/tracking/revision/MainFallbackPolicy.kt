package dev.monaka.tracking.revision

/**
 * Contract-independent candidate model for the coordinated Main/Fallback revision.
 *
 * ConstraintResolver now invokes this policy after fixed-v2 admission and freshness.
 * CandidateTrackingModality is also the runtime TrackingModality alias; this is
 * the single active decision policy, independent of transport and IK.
 */
enum class CandidateTrackingModality {
	FULL,
	ROTATION_ONLY,
	NONE,
}

enum class RotationOwner {
	MAIN,
	FALLBACK,
	NONE,
}

data class MainFallbackAssignment<T : Any>(
	val main: T,
	val rotationFallback: T? = null,
)

data class MainFallbackDecision(
	val positionFromMain: Boolean,
	val rotationOwner: RotationOwner,
)

/** Pure policy only: no tracker identity, protocol, freshness clock, IK, or vendor dependency. */
object MainFallbackPolicy {
	fun decide(
		mainModality: CandidateTrackingModality,
		mainPositionUsable: Boolean,
		mainRotationUsable: Boolean,
		fallbackRotationUsable: Boolean,
	): MainFallbackDecision {
		val mainFullUsable =
			mainModality == CandidateTrackingModality.FULL &&
				mainPositionUsable &&
				mainRotationUsable

		if (mainFullUsable) {
			return MainFallbackDecision(
				positionFromMain = true,
				rotationOwner = RotationOwner.MAIN,
			)
		}

		// A malformed/incomplete FULL sample does not silently degrade itself to
		// rotation-only. Only an explicitly ROTATION_ONLY sample may contribute
		// Main rotation after the FULL paired-pose path has failed.
		val mainRotationAllowed =
			mainModality == CandidateTrackingModality.ROTATION_ONLY && mainRotationUsable

		return MainFallbackDecision(
			positionFromMain = false,
			rotationOwner = when {
				fallbackRotationUsable -> RotationOwner.FALLBACK
				mainRotationAllowed -> RotationOwner.MAIN
				else -> RotationOwner.NONE
			},
		)
	}
}
