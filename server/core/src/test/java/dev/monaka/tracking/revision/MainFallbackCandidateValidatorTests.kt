package dev.monaka.tracking.revision

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainFallbackCandidateValidatorTests {
	private val main = PersistentTrackerIdentity("bridge", "source-a", "main")
	private val fallback = PersistentTrackerIdentity("bridge", "source-b", "fallback")

	@Test
	fun acceptsCanonicalFullRotationOnlyAndNoneStates() {
		val states = mapOf(
			main to CandidateTrackerState(
				identity = main,
				modality = CandidateTrackingModality.FULL,
				positionUsable = true,
				rotationUsable = true,
			),
			fallback to CandidateTrackerState(
				identity = fallback,
				modality = CandidateTrackingModality.ROTATION_ONLY,
				positionUsable = false,
				rotationUsable = true,
			),
			PersistentTrackerIdentity("bridge", "source-c", "idle") to CandidateTrackerState(
				identity = PersistentTrackerIdentity("bridge", "source-c", "idle"),
				modality = CandidateTrackingModality.NONE,
				positionUsable = false,
				rotationUsable = false,
			),
		)

		val issues = MainFallbackCandidateValidator.validate(
			assignment = MainFallbackAssignment(main = main, rotationFallback = fallback),
			states = states,
		)

		assertTrue(issues.isEmpty())
	}

	@Test
	fun rejectsSelfFallbackInsteadOfTreatingMainAsExternalFallback() {
		val issues = MainFallbackCandidateValidator.validateAssignment(
			MainFallbackAssignment(main = main, rotationFallback = main),
		)

		assertEquals(listOf(CandidateValidationCode.SELF_FALLBACK), issues.map { it.code })
		assertEquals(main, issues.single().identity)
	}

	@Test
	fun rejectsMalformedModalityComponentCombinations() {
		val malformed = listOf(
			CandidateTrackerState(main, CandidateTrackingModality.FULL, positionUsable = false, rotationUsable = true),
			CandidateTrackerState(main, CandidateTrackingModality.ROTATION_ONLY, positionUsable = true, rotationUsable = true),
			CandidateTrackerState(main, CandidateTrackingModality.ROTATION_ONLY, positionUsable = false, rotationUsable = false),
			CandidateTrackerState(main, CandidateTrackingModality.NONE, positionUsable = false, rotationUsable = true),
		)

		val codes = malformed.flatMap(MainFallbackCandidateValidator::validateState).map { it.code }

		assertEquals(
			listOf(
				CandidateValidationCode.FULL_REQUIRES_BOTH_COMPONENTS,
				CandidateValidationCode.ROTATION_ONLY_REQUIRES_ORIENTATION_ONLY,
				CandidateValidationCode.ROTATION_ONLY_REQUIRES_ORIENTATION_ONLY,
				CandidateValidationCode.NONE_REQUIRES_NO_COMPONENTS,
			),
			codes,
		)
	}

	@Test
	fun rejectsStateMapKeyValueIdentityMismatch() {
		val other = PersistentTrackerIdentity("bridge", "source-a", "other")
		val states = mapOf(
			main to CandidateTrackerState(
				identity = other,
				modality = CandidateTrackingModality.NONE,
				positionUsable = false,
				rotationUsable = false,
			),
		)

		val issues = MainFallbackCandidateValidator.validateStateMap(states)

		assertEquals(listOf(CandidateValidationCode.STATE_IDENTITY_MISMATCH), issues.map { it.code })
		assertEquals(main, issues.single().identity)
	}

	@Test
	fun missingAssignedStateAndUnrelatedExtraStateAreNotConfigurationErrors() {
		val unrelated = PersistentTrackerIdentity("bridge", "source-z", "unrelated")
		val states = mapOf(
			unrelated to CandidateTrackerState(
				identity = unrelated,
				modality = CandidateTrackingModality.NONE,
				positionUsable = false,
				rotationUsable = false,
			),
		)

		val issues = MainFallbackCandidateValidator.validate(
			assignment = MainFallbackAssignment(main = main, rotationFallback = fallback),
			states = states,
		)

		assertTrue(issues.isEmpty())
	}
}
