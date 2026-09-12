package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SlimeTrackerPoseObservationAdapterTests {
	private fun tracker(
		id: Int,
		target: TrackerPosition?,
		hasPosition: Boolean,
		hasRotation: Boolean,
	): Tracker = Tracker(
		device = null,
		id = id,
		name = "test-$id",
		trackerPosition = target,
		hasPosition = hasPosition,
		hasRotation = hasRotation,
		isInternal = true,
		trackRotDirection = false,
	)

	@Test
	fun mapsTrackedSixDofTrackerWithoutMutatingCapabilities() {
		val tracker = tracker(1, TrackerPosition.HIP, hasPosition = true, hasRotation = true)
		tracker.status = TrackerStatus.OK
		tracker.position = Vector3(1f, 2f, 3f)
		tracker.setRotation(Quaternion.IDENTITY)

		val observation = assertNotNull(
			SlimeTrackerPoseObservationAdapter(sourcePrefix = "legacy", priority = 42)
				.adapt(tracker, observedAtNanos = 123L),
		)

		assertEquals("legacy:1", observation.sourceId)
		assertEquals(TrackerPosition.HIP, observation.target)
		assertEquals(123L, observation.observedAtNanos)
		assertEquals(42, observation.priority)
		assertEquals(Vector3(1f, 2f, 3f), observation.position)
		assertEquals(Quaternion.IDENTITY, observation.rotation)
		assertEquals(ObservationQuality.TRACKED, observation.positionQuality)
		assertEquals(ObservationQuality.TRACKED, observation.rotationQuality)
	}

	@Test
	fun unavailableCapabilitiesStayUnavailable() {
		val tracker = tracker(2, TrackerPosition.LEFT_FOOT, hasPosition = false, hasRotation = true)
		tracker.status = TrackerStatus.OK
		tracker.setRotation(Quaternion.IDENTITY)

		val observation = assertNotNull(SlimeTrackerPoseObservationAdapter().adapt(tracker, 10L))

		assertNull(observation.position)
		assertEquals(ObservationQuality.UNAVAILABLE, observation.positionQuality)
		assertEquals(Quaternion.IDENTITY, observation.rotation)
		assertEquals(ObservationQuality.TRACKED, observation.rotationQuality)
	}

	@Test
	fun mapsLegacyTrackerStatusIntoObservationQuality() {
		val tracker = tracker(3, TrackerPosition.RIGHT_FOOT, hasPosition = true, hasRotation = true)
		tracker.position = Vector3(0f, 0f, 0f)
		tracker.setRotation(Quaternion.IDENTITY)
		val adapter = SlimeTrackerPoseObservationAdapter()

		val expected = listOf(
			TrackerStatus.OK to ObservationQuality.TRACKED,
			TrackerStatus.BUSY to ObservationQuality.DEGRADED,
			TrackerStatus.TIMED_OUT to ObservationQuality.STALE,
			TrackerStatus.OCCLUDED to ObservationQuality.LOST,
			TrackerStatus.ERROR to ObservationQuality.LOST,
			TrackerStatus.DISCONNECTED to ObservationQuality.LOST,
		)

		expected.forEachIndexed { index, (status, quality) ->
			tracker.status = status
			val observation = assertNotNull(adapter.adapt(tracker, index.toLong()))
			assertEquals(quality, observation.positionQuality, "position quality for $status")
			assertEquals(quality, observation.rotationQuality, "rotation quality for $status")
		}
	}

	@Test
	fun unassignedTrackerDoesNotProduceObservation() {
		val tracker = tracker(4, null, hasPosition = true, hasRotation = true)
		tracker.status = TrackerStatus.OK

		assertNull(SlimeTrackerPoseObservationAdapter().adapt(tracker, 0L))
	}

	@Test
	fun resolverCanMixMirroredDegradedPositionWithTrackedRotation() {
		val absolute = tracker(5, TrackerPosition.HIP, hasPosition = true, hasRotation = true)
		absolute.status = TrackerStatus.BUSY
		absolute.position = Vector3(0.2f, 1f, -0.1f)
		absolute.setRotation(Quaternion.IDENTITY)

		val imu = tracker(6, TrackerPosition.HIP, hasPosition = false, hasRotation = true)
		imu.status = TrackerStatus.OK
		imu.setRotation(Quaternion.IDENTITY)

		val absoluteObservation = assertNotNull(
			SlimeTrackerPoseObservationAdapter(sourcePrefix = "absolute", priority = 100)
				.adapt(absolute, 100L),
		)
		val imuObservation = assertNotNull(
			SlimeTrackerPoseObservationAdapter(sourcePrefix = "imu", priority = 10)
				.adapt(imu, 110L),
		)

		val resolved = ConstraintResolver().resolve(
			TrackerPosition.HIP,
			listOf(absoluteObservation, imuObservation),
		)

		assertEquals("absolute:5", resolved.position?.sourceId)
		assertEquals(ObservationQuality.DEGRADED, resolved.position?.quality)
		assertEquals("imu:6", resolved.rotation?.sourceId)
		assertEquals(ObservationQuality.TRACKED, resolved.rotation?.quality)
	}
}
