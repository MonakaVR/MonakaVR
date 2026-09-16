package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservationSourceProfileTests {
	@Test
	fun rotationOnlyProfileSuppressesPositionAndOwnsPriority() {
		val profile = ObservationSourceProfile.rotationOnly(
			profileId = "imu",
			priority = 10,
			rotationTimeoutNanos = 200L,
		)
		val observation = PoseObservation(
			sourceId = "imu:1",
			target = TrackerPosition.HIP,
			observedAtNanos = 100L,
			priority = 999,
			position = Vector3(1f, 2f, 3f),
			rotation = Quaternion.IDENTITY,
		)

		val normalized = profile.normalize(observation)

		assertEquals(10, normalized.priority)
		assertNull(normalized.position)
		assertEquals(ObservationQuality.UNAVAILABLE, normalized.positionQuality)
		assertEquals(Quaternion.IDENTITY, normalized.rotation)
		assertEquals(ObservationQuality.TRACKED, normalized.rotationQuality)
	}

	@Test
	fun pipelineAppliesFreshnessBeforeExplicitFallback() {
		val absoluteProfile = ObservationSourceProfile.sixDof(
			profileId = "absolute",
			priority = 100,
			positionTimeoutNanos = 50L,
			rotationTimeoutNanos = 50L,
		)
		val imuProfile = ObservationSourceProfile.rotationOnly(
			profileId = "imu",
			priority = 10,
			rotationTimeoutNanos = 200L,
		)
		val pipeline = ConstraintPipeline(resolver = ConstraintResolver { mapOf(TrackerPosition.HIP to MainTrackerAssignment(TrackerReference("absolute:hip"), TrackerReference("imu:hip"))) })

		pipeline.ingest(
			PoseObservation(
				sourceId = "absolute:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				position = Vector3(0.1f, 1f, 0.2f),
				rotation = Quaternion.IDENTITY,
			),
			absoluteProfile,
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "imu:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				rotation = Quaternion.IDENTITY,
			),
			imuProfile,
		)

		val initiallyAbsolute = pipeline.resolve(TrackerPosition.HIP, nowNanos = 120L)
		assertEquals("absolute:hip", initiallyAbsolute.position?.sourceId)
		assertEquals("absolute:hip", initiallyAbsolute.rotation?.sourceId)

		val afterAbsoluteTimeout = pipeline.resolve(TrackerPosition.HIP, nowNanos = 151L)
		assertNull(afterAbsoluteTimeout.position)
		assertEquals("imu:hip", afterAbsoluteTimeout.rotation?.sourceId)
		assertEquals(ObservationQuality.TRACKED, afterAbsoluteTimeout.rotation?.quality)
	}

	@Test
	fun refreshedAbsoluteSourceRecoversWithoutChangingFallbackProfile() {
		val absoluteProfile = ObservationSourceProfile.sixDof(
			profileId = "absolute",
			priority = 100,
			positionTimeoutNanos = 50L,
			rotationTimeoutNanos = 50L,
		)
		val imuProfile = ObservationSourceProfile.rotationOnly(
			profileId = "imu",
			priority = 10,
			rotationTimeoutNanos = 200L,
		)
		val pipeline = ConstraintPipeline(resolver = ConstraintResolver { mapOf(TrackerPosition.HIP to MainTrackerAssignment(TrackerReference("absolute:hip"), TrackerReference("imu:hip"))) })

		pipeline.ingest(
			PoseObservation(
				sourceId = "absolute:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				position = Vector3(0f, 1f, 0f),
				rotation = Quaternion.IDENTITY,
			),
			absoluteProfile,
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "imu:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				rotation = Quaternion.IDENTITY,
			),
			imuProfile,
		)

		assertEquals("imu:hip", pipeline.resolve(TrackerPosition.HIP, 151L).rotation?.sourceId)

		pipeline.ingest(
			PoseObservation(
				sourceId = "absolute:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 160L,
				position = Vector3(0.2f, 1f, 0f),
				rotation = Quaternion.IDENTITY,
			),
			absoluteProfile,
		)

		val recovered = pipeline.resolve(TrackerPosition.HIP, 160L)
		assertEquals("absolute:hip", recovered.position?.sourceId)
		assertEquals(Vector3(0.2f, 1f, 0f), recovered.position?.value)
		assertEquals("absolute:hip", recovered.rotation?.sourceId)
	}
}
