package dev.monaka.tracking.revision

/** Candidate-only validation codes for the runtime-unwired Main/Fallback model. */
enum class CandidateValidationCode {
	SELF_FALLBACK,
	STATE_IDENTITY_MISMATCH,
	FULL_REQUIRES_BOTH_COMPONENTS,
	ROTATION_ONLY_REQUIRES_ORIENTATION_ONLY,
	NONE_REQUIRES_NO_COMPONENTS,
}

data class CandidateValidationIssue(
	val code: CandidateValidationCode,
	val identity: PersistentTrackerIdentity? = null,
)

/**
 * Contract-independent validation around the candidate assignment/state model.
 *
 * This validator does not inspect protocol packets, timestamps, freshness,
 * tracking-state enums, body roles or vendor state. Missing assigned states are
 * intentionally allowed because a configured tracker may simply be disconnected.
 */
object MainFallbackCandidateValidator {
	fun validateAssignment(
		assignment: MainFallbackAssignment<PersistentTrackerIdentity>,
	): List<CandidateValidationIssue> =
		buildList {
			if (assignment.rotationFallback == assignment.main) {
				add(
					CandidateValidationIssue(
						code = CandidateValidationCode.SELF_FALLBACK,
						identity = assignment.main,
					),
				)
			}
		}

	fun validateState(state: CandidateTrackerState): List<CandidateValidationIssue> =
		buildList {
			when (state.modality) {
				CandidateTrackingModality.FULL -> {
					if (!state.positionUsable || !state.rotationUsable) {
						add(
							CandidateValidationIssue(
								code = CandidateValidationCode.FULL_REQUIRES_BOTH_COMPONENTS,
								identity = state.identity,
							),
						)
					}
				}

				CandidateTrackingModality.ROTATION_ONLY -> {
					if (state.positionUsable || !state.rotationUsable) {
						add(
							CandidateValidationIssue(
								code = CandidateValidationCode.ROTATION_ONLY_REQUIRES_ORIENTATION_ONLY,
								identity = state.identity,
							),
						)
					}
				}

				CandidateTrackingModality.NONE -> {
					if (state.positionUsable || state.rotationUsable) {
						add(
							CandidateValidationIssue(
								code = CandidateValidationCode.NONE_REQUIRES_NO_COMPONENTS,
								identity = state.identity,
							),
						)
					}
				}
			}
		}

	fun validateStateMap(
		states: Map<PersistentTrackerIdentity, CandidateTrackerState>,
	): List<CandidateValidationIssue> =
		buildList {
			for ((key, state) in states) {
				if (key != state.identity) {
					add(
						CandidateValidationIssue(
							code = CandidateValidationCode.STATE_IDENTITY_MISMATCH,
							identity = key,
						),
					)
				}
				addAll(validateState(state))
			}
		}

	fun validate(
		assignment: MainFallbackAssignment<PersistentTrackerIdentity>,
		states: Map<PersistentTrackerIdentity, CandidateTrackerState>,
	): List<CandidateValidationIssue> =
		validateAssignment(assignment) + validateStateMap(states)
}
