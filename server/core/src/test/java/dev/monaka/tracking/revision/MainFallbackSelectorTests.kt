package dev.monaka.tracking.revision

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainFallbackSelectorTests {
	private val mainId = PersistentTrackerIdentity("bridge-a", "source-a", "tracker-main")
	private val fallbackId = PersistentTrackerIdentity("bridge-a", "source-b", "tracker-fallback")
	private val assignment = MainFallbackAssignment(main = mainId, rotationFallback = fallbackId)

	@Test
	fun fullMainWinsEvenWhenFallbackIsUsable() {
		val result = MainFallbackSelector.select(
			assignment,
			mapOf(
				mainId to CandidateTrackerState(mainId, CandidateTrackingModality.FULL, true, true),
				fallbackId to CandidateTrackerState(fallbackId, CandidateTrackingModality.ROTATION_ONLY, false, true),
			),
		)

		assertTrue(result.decision.positionFromMain)
		assertEquals(RotationOwner.MAIN, result.decision.rotationOwner)
	}

	@Test
	fun explicitFallbackWinsWhenMainIsRotationOnly() {
		val result = MainFallbackSelector.select(
			assignment,
			mapOf(
				mainId to CandidateTrackerState(mainId, CandidateTrackingModality.ROTATION_ONLY, false, true),
				fallbackId to CandidateTrackerState(fallbackId, CandidateTrackingModality.ROTATION_ONLY, false, true),
			),
		)

		assertFalse(result.decision.positionFromMain)
		assertEquals(RotationOwner.FALLBACK, result.decision.rotationOwner)
	}

	@Test
	fun missingMainCanStillUseExplicitFallbackRotation() {
		val result = MainFallbackSelector.select(
			assignment,
			mapOf(
				fallbackId to CandidateTrackerState(fallbackId, CandidateTrackingModality.ROTATION_ONLY, false, true),
			),
		)

		assertNull(result.main)
		assertEquals(RotationOwner.FALLBACK, result.decision.rotationOwner)
	}

	@Test
	fun sameTrackerNameFromDifferentSourceCannotImpersonateConfiguredMain() {
		val collidingName = PersistentTrackerIdentity("bridge-a", "source-c", "tracker-main")
		val result = MainFallbackSelector.select(
			MainFallbackAssignment(main = mainId),
			mapOf(
				collidingName to CandidateTrackerState(collidingName, CandidateTrackingModality.FULL, true, true),
			),
		)

		assertNull(result.main)
		assertFalse(result.decision.positionFromMain)
		assertEquals(RotationOwner.NONE, result.decision.rotationOwner)
	}

	@Test
	fun unrelatedUsableObservationNeverBecomesFallback() {
		val unrelated = PersistentTrackerIdentity("bridge-x", "source-x", "tracker-x")
		val result = MainFallbackSelector.select(
			MainFallbackAssignment(main = mainId),
			mapOf(
				mainId to CandidateTrackerState(mainId, CandidateTrackingModality.NONE, false, false),
				unrelated to CandidateTrackerState(unrelated, CandidateTrackingModality.ROTATION_ONLY, false, true),
			),
		)

		assertNull(result.fallback)
		assertEquals(RotationOwner.NONE, result.decision.rotationOwner)
	}

	@Test
	fun malformedFullFallbackDoesNotLeakRotation() {
		val result = MainFallbackSelector.select(
			assignment,
			mapOf(
				mainId to CandidateTrackerState(mainId, CandidateTrackingModality.NONE, false, false),
				fallbackId to CandidateTrackerState(fallbackId, CandidateTrackingModality.FULL, false, true),
			),
		)

		assertEquals(RotationOwner.NONE, result.decision.rotationOwner)
	}
}
