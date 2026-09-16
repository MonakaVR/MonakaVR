package dev.monaka.tracking.revision

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainFallbackPolicyTests {
	@Test
	fun fullUsableMainOwnsBothComponentsEvenWhenFallbackIsUsable() {
		val decision = MainFallbackPolicy.decide(
			mainModality = CandidateTrackingModality.FULL,
			mainPositionUsable = true,
			mainRotationUsable = true,
			fallbackRotationUsable = true,
		)

		assertTrue(decision.positionFromMain)
		assertEquals(RotationOwner.MAIN, decision.rotationOwner)
	}

	@Test
	fun rotationOnlyMainUsesItsOwnRotationWhenNoExternalFallbackIsUsable() {
		val decision = MainFallbackPolicy.decide(
			mainModality = CandidateTrackingModality.ROTATION_ONLY,
			mainPositionUsable = true,
			mainRotationUsable = true,
			fallbackRotationUsable = false,
		)

		assertFalse(decision.positionFromMain)
		assertEquals(RotationOwner.MAIN, decision.rotationOwner)
	}

	@Test
	fun rotationOnlyMainPrefersExplicitUsableFallbackRotation() {
		val decision = MainFallbackPolicy.decide(
			mainModality = CandidateTrackingModality.ROTATION_ONLY,
			mainPositionUsable = false,
			mainRotationUsable = true,
			fallbackRotationUsable = true,
		)

		assertFalse(decision.positionFromMain)
		assertEquals(RotationOwner.FALLBACK, decision.rotationOwner)
	}

	@Test
	fun noneMainCanOnlyUseExternalFallbackRotation() {
		val withFallback = MainFallbackPolicy.decide(
			mainModality = CandidateTrackingModality.NONE,
			mainPositionUsable = true,
			mainRotationUsable = true,
			fallbackRotationUsable = true,
		)
		val withoutFallback = MainFallbackPolicy.decide(
			mainModality = CandidateTrackingModality.NONE,
			mainPositionUsable = true,
			mainRotationUsable = true,
			fallbackRotationUsable = false,
		)

		assertFalse(withFallback.positionFromMain)
		assertEquals(RotationOwner.FALLBACK, withFallback.rotationOwner)
		assertFalse(withoutFallback.positionFromMain)
		assertEquals(RotationOwner.NONE, withoutFallback.rotationOwner)
	}

	@Test
	fun incompleteFullDoesNotLeakPositionAndFallsBackBeforeUsingMainRotation() {
		val fallback = MainFallbackPolicy.decide(
			mainModality = CandidateTrackingModality.FULL,
			mainPositionUsable = false,
			mainRotationUsable = true,
			fallbackRotationUsable = true,
		)
		val mainRotation = MainFallbackPolicy.decide(
			mainModality = CandidateTrackingModality.FULL,
			mainPositionUsable = false,
			mainRotationUsable = true,
			fallbackRotationUsable = false,
		)

		assertFalse(fallback.positionFromMain)
		assertEquals(RotationOwner.FALLBACK, fallback.rotationOwner)
		assertFalse(mainRotation.positionFromMain)
		assertEquals(RotationOwner.MAIN, mainRotation.rotationOwner)
	}

	@Test
	fun assignmentKeepsMainAndFallbackRelationshipExplicit() {
		val assignment = MainFallbackAssignment(
			main = "publisher-a/source-a/tracker-main",
			rotationFallback = "slime/tracker-7",
		)

		assertEquals("publisher-a/source-a/tracker-main", assignment.main)
		assertEquals("slime/tracker-7", assignment.rotationFallback)
	}
}
