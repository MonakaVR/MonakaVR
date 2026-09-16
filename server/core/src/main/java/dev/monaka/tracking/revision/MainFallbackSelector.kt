package dev.monaka.tracking.revision

/**
 * Effective per-tracker state after transport/session/freshness handling.
 *
 * This candidate deliberately carries no pose values, clocks, priorities or
 * vendor state. It only provides the already-decided component usability that
 * the pure Main/Fallback policy needs.
 */
data class CandidateTrackerState(
	val identity: PersistentTrackerIdentity,
	val modality: CandidateTrackingModality,
	val positionUsable: Boolean,
	val rotationUsable: Boolean,
) {
	fun fallbackRotationUsable(): Boolean =
		when (modality) {
			CandidateTrackingModality.FULL -> positionUsable && rotationUsable
			CandidateTrackingModality.ROTATION_ONLY -> rotationUsable
			CandidateTrackingModality.NONE -> false
		}
}

data class MainFallbackSelection(
	val main: CandidateTrackerState?,
	val fallback: CandidateTrackerState?,
	val decision: MainFallbackDecision,
)

/**
 * Identity-aware but runtime-unwired selector.
 *
 * The input map is intentionally keyed by the full persistent tracker identity.
 * No observation outside the explicitly configured Main/Fallback identities can
 * participate, regardless of trackerId reuse or any external quality ordering.
 */
object MainFallbackSelector {
	fun select(
		assignment: MainFallbackAssignment<PersistentTrackerIdentity>,
		states: Map<PersistentTrackerIdentity, CandidateTrackerState>,
	): MainFallbackSelection {
		val main = states[assignment.main]
		val fallback = assignment.rotationFallback?.let(states::get)

		val decision = MainFallbackPolicy.decide(
			mainModality = main?.modality ?: CandidateTrackingModality.NONE,
			mainPositionUsable = main?.positionUsable == true,
			mainRotationUsable = main?.rotationUsable == true,
			fallbackRotationUsable = fallback?.fallbackRotationUsable() == true,
		)

		return MainFallbackSelection(
			main = main,
			fallback = fallback,
			decision = decision,
		)
	}
}
