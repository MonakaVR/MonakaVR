package dev.monaka.tracking.pico

import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PicoMotionTrackerBridgeBodyAssignmentsTests {
	@Test
	fun parsesPersistentSerialAssignmentsFromDesignationOrEnumName() {
		val parsed = PicoMotionTrackerBridgeBodyAssignments.parse(
			"PICO-L=body:left_foot; PICO-R=RIGHT_FOOT ; PICO-HIP=body:hip",
		)

		assertEquals(TrackerPosition.LEFT_FOOT, parsed["PICO-L"])
		assertEquals(TrackerPosition.RIGHT_FOOT, parsed["PICO-R"])
		assertEquals(TrackerPosition.HIP, parsed["PICO-HIP"])
	}

	@Test
	fun assignmentUpdatesAreVisibleWithoutChangingPhysicalIdentity() {
		val assignments = PicoMotionTrackerBridgeBodyAssignments(
			mapOf("SERIAL-001" to TrackerPosition.LEFT_FOOT),
		)

		assertEquals(TrackerPosition.LEFT_FOOT, assignments.resolve("SERIAL-001"))
		assignments.assign("SERIAL-001", TrackerPosition.RIGHT_FOOT)
		assertEquals(TrackerPosition.RIGHT_FOOT, assignments.resolve("SERIAL-001"))
		assertEquals(TrackerPosition.RIGHT_FOOT, assignments.unassign("SERIAL-001"))
		assertNull(assignments.resolve("SERIAL-001"))
	}

	@Test
	fun rejectsMalformedUnknownAndDuplicateSerialAssignments() {
		assertFailsWith<IllegalArgumentException> {
			PicoMotionTrackerBridgeBodyAssignments.parse("missing-separator")
		}
		assertFailsWith<IllegalArgumentException> {
			PicoMotionTrackerBridgeBodyAssignments.parse("PICO=body:not_real")
		}
		assertFailsWith<IllegalArgumentException> {
			PicoMotionTrackerBridgeBodyAssignments.parse("PICO=body:hip;PICO=body:left_foot")
		}
	}
}
