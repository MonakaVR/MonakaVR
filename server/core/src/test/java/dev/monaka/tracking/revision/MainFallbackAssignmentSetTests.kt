package dev.monaka.tracking.revision

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainFallbackAssignmentSetTests {
	@Test
	fun legacyAssignmentsMigrateToMainOnlyWithoutGuessingFallback() {
		val migrated = MainFallbackAssignmentSet.fromLegacyMainOnly(
			mapOf(
				"HIP" to "tracker-main",
				"LEFT_FOOT" to "tracker-left",
			),
		)

		assertEquals("tracker-main", migrated.entries.getValue("HIP").main)
		assertNull(migrated.entries.getValue("HIP").rotationFallback)
		assertEquals("tracker-left", migrated.entries.getValue("LEFT_FOOT").main)
		assertNull(migrated.entries.getValue("LEFT_FOOT").rotationFallback)
	}

	@Test
	fun explicitFallbackRemainsExplicit() {
		val assignments = MainFallbackAssignmentSet.empty<String, String>()
			.assign(
				target = "HIP",
				main = "publisher/source/main",
				rotationFallback = "slime/fallback",
			)

		assertEquals("publisher/source/main", assignments.entries.getValue("HIP").main)
		assertEquals("slime/fallback", assignments.entries.getValue("HIP").rotationFallback)
	}

	@Test
	fun updatingOneTargetDoesNotMutateOtherTargetsOrPriorSnapshot() {
		val before = MainFallbackAssignmentSet.fromLegacyMainOnly(
			mapOf(
				"HIP" to "hip-main",
				"LEFT_FOOT" to "left-main",
			),
		)
		val after = before.assign(
			target = "HIP",
			main = "hip-main-v2",
			rotationFallback = "hip-fallback",
		)

		assertEquals("hip-main", before.entries.getValue("HIP").main)
		assertNull(before.entries.getValue("HIP").rotationFallback)
		assertEquals("hip-main-v2", after.entries.getValue("HIP").main)
		assertEquals("hip-fallback", after.entries.getValue("HIP").rotationFallback)
		assertEquals(before.entries.getValue("LEFT_FOOT"), after.entries.getValue("LEFT_FOOT"))
	}

	@Test
	fun unassignRemovesOnlyRequestedTarget() {
		val assignments = MainFallbackAssignmentSet.fromLegacyMainOnly(
			mapOf(
				"HIP" to "hip-main",
				"RIGHT_FOOT" to "right-main",
			),
		)
		val result = assignments.unassign("HIP")

		assertNull(result.entries["HIP"])
		assertEquals("right-main", result.entries.getValue("RIGHT_FOOT").main)
	}
}
