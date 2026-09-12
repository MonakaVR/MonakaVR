package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConstraintResolverTests {
	private val resolver = ConstraintResolver()

	@Test
	fun resolvesPositionAndRotationIndependentlyAcrossSources() {
		val absolute = PoseObservation(
			sourceId = "absolute",
			target = TrackerPosition.HIP,
			observedAtNanos = 100L,
			priority = 100,
			position = Vector3(1f, 2f, 3f),
			rotation = Quaternion.IDENTITY,
			rotationQuality = ObservationQuality.LOST,
		)
		val imu = PoseObservation(
			sourceId = "imu",
			target = TrackerPosition.HIP,
			observedAtNanos = 110L,
			priority = 10,
			rotation = Quaternion.IDENTITY,
		)

		val resolved = resolver.resolve(TrackerPosition.HIP, listOf(absolute, imu))

		assertEquals("absolute", resolved.position?.sourceId)
		assertEquals(Vector3(1f, 2f, 3f), resolved.position?.value)
		assertEquals("imu", resolved.rotation?.sourceId)
	}

	@Test
	fun trackedSecondaryBeatsDegradedPrimaryForThatComponent() {
		val primary = PoseObservation(
			sourceId = "primary",
			target = TrackerPosition.LEFT_FOOT,
			observedAtNanos = 200L,
			priority = 100,
			position = Vector3(1f, 0f, 0f),
			positionQuality = ObservationQuality.DEGRADED,
		)
		val secondary = PoseObservation(
			sourceId = "secondary",
			target = TrackerPosition.LEFT_FOOT,
			observedAtNanos = 190L,
			priority = 10,
			position = Vector3(2f, 0f, 0f),
		)

		val resolved = resolver.resolve(TrackerPosition.LEFT_FOOT, listOf(primary, secondary))

		assertEquals("secondary", resolved.position?.sourceId)
		assertEquals(Vector3(2f, 0f, 0f), resolved.position?.value)
	}

	@Test
	fun priorityThenRecencyBreakTiesWithinSameQuality() {
		val lowPriorityNewer = PoseObservation(
			sourceId = "low-priority",
			target = TrackerPosition.RIGHT_FOOT,
			observedAtNanos = 300L,
			priority = 10,
			position = Vector3(1f, 0f, 0f),
		)
		val highPriorityOlder = PoseObservation(
			sourceId = "high-priority",
			target = TrackerPosition.RIGHT_FOOT,
			observedAtNanos = 250L,
			priority = 20,
			position = Vector3(2f, 0f, 0f),
		)
		val highPriorityNewer = PoseObservation(
			sourceId = "high-priority-newer",
			target = TrackerPosition.RIGHT_FOOT,
			observedAtNanos = 275L,
			priority = 20,
			position = Vector3(3f, 0f, 0f),
		)

		val resolved = resolver.resolve(
			TrackerPosition.RIGHT_FOOT,
			listOf(lowPriorityNewer, highPriorityOlder, highPriorityNewer),
		)

		assertEquals("high-priority-newer", resolved.position?.sourceId)
		assertEquals(Vector3(3f, 0f, 0f), resolved.position?.value)
	}

	@Test
	fun staleLostAndOtherTargetsDoNotProduceConstraints() {
		val stale = PoseObservation(
			sourceId = "stale",
			target = TrackerPosition.HIP,
			observedAtNanos = 100L,
			position = Vector3(1f, 0f, 0f),
			positionQuality = ObservationQuality.STALE,
		)
		val lost = PoseObservation(
			sourceId = "lost",
			target = TrackerPosition.HIP,
			observedAtNanos = 100L,
			rotation = Quaternion.IDENTITY,
			rotationQuality = ObservationQuality.LOST,
		)
		val otherTarget = PoseObservation(
			sourceId = "other",
			target = TrackerPosition.CHEST,
			observedAtNanos = 100L,
			position = Vector3(2f, 0f, 0f),
			rotation = Quaternion.IDENTITY,
		)

		val resolved = resolver.resolve(TrackerPosition.HIP, listOf(stale, lost, otherTarget))

		assertNull(resolved.position)
		assertNull(resolved.rotation)
	}
}
