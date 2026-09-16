package dev.monaka.tracking.revision

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LegacyAssignmentMigrationTests {
	@Test
	fun unambiguousLegacyAssignmentsBecomeMainOnly() {
		val result = LegacyAssignmentMigration.migrate(
			listOf(
				"tracker-hip" to "HIP",
				"tracker-left-foot" to "LEFT_FOOT",
			),
		)

		val success = assertIs<LegacyAssignmentMigrationResult.Success<String, String>>(result)
		assertEquals("tracker-hip", success.assignments.entries.getValue("HIP").main)
		assertNull(success.assignments.entries.getValue("HIP").rotationFallback)
		assertEquals("tracker-left-foot", success.assignments.entries.getValue("LEFT_FOOT").main)
		assertNull(success.assignments.entries.getValue("LEFT_FOOT").rotationFallback)
	}

	@Test
	fun multipleDistinctLegacyTrackersForOneTargetFailClosed() {
		val result = LegacyAssignmentMigration.migrate(
			listOf(
				"tracker-a" to "HIP",
				"tracker-b" to "HIP",
				"tracker-foot" to "LEFT_FOOT",
			),
		)

		val ambiguous = assertIs<LegacyAssignmentMigrationResult.Ambiguous<String, String>>(result)
		assertEquals(setOf("HIP"), ambiguous.targets)
	}

	@Test
	fun allAmbiguousTargetsAreReportedTogether() {
		val result = LegacyAssignmentMigration.migrate(
			listOf(
				"hip-a" to "HIP",
				"hip-b" to "HIP",
				"foot-a" to "LEFT_FOOT",
				"foot-b" to "LEFT_FOOT",
			),
		)

		val ambiguous = assertIs<LegacyAssignmentMigrationResult.Ambiguous<String, String>>(result)
		assertEquals(setOf("HIP", "LEFT_FOOT"), ambiguous.targets)
	}

	@Test
	fun exactDuplicateLegacyPairsDoNotCreateFalseAmbiguity() {
		val result = LegacyAssignmentMigration.migrate(
			listOf(
				"tracker-hip" to "HIP",
				"tracker-hip" to "HIP",
			),
		)

		val success = assertIs<LegacyAssignmentMigrationResult.Success<String, String>>(result)
		assertEquals(1, success.assignments.entries.size)
		assertEquals("tracker-hip", success.assignments.entries.getValue("HIP").main)
		assertNull(success.assignments.entries.getValue("HIP").rotationFallback)
	}

	@Test
	fun emptyLegacyInputMigratesToEmptyAssignmentSet() {
		val result = LegacyAssignmentMigration.migrate<String, String>(emptyList())

		val success = assertIs<LegacyAssignmentMigrationResult.Success<String, String>>(result)
		assertEquals(emptyMap(), success.assignments.entries)
	}
}
