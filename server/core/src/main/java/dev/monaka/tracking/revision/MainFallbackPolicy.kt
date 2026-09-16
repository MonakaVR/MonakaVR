package dev.monaka.tracking.revision

/**
 * Contract-independent candidate model for the coordinated Main/Fallback revision.
 *
 * This package is intentionally not wired into MonakaRuntime yet. It exists so the
 * policy can be reviewed and tested before protocol-v2 ingestion and runtime
 * integration are switched over.
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

		val mainRotationAllowed =
			mainModality != CandidateTrackingModality.NONE && mainRotationUsable

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
