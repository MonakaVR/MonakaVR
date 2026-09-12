package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ObservationSourceProfileRegistryTests {
	@Test
	fun registerLookupReplaceAndRemoveProfiles() {
		val absolute = ObservationSourceProfile.sixDof(
			profileId = "absolute",
			priority = 100,
			positionTimeoutNanos = 50L,
			rotationTimeoutNanos = 50L,
		)
		val registry = ObservationSourceProfileRegistry(listOf(absolute))

		assertEquals(absolute, registry["absolute"])
		assertEquals(1, registry.size)
		assertFailsWith<IllegalArgumentException> { registry.register(absolute) }

		val replacement = ObservationSourceProfile.sixDof(
			profileId = "absolute",
			priority = 200,
			positionTimeoutNanos = 25L,
			rotationTimeoutNanos = 25L,
		)
		assertEquals(absolute, registry.replace(replacement))
		assertEquals(replacement, registry.require("absolute"))
		assertEquals(replacement, registry.snapshot()["absolute"])

		assertEquals(replacement, registry.remove("absolute"))
		assertNull(registry["absolute"])
		assertEquals(0, registry.size)
	}

	@Test
	fun unknownProfileIsRejectedBeforeObservationIsStored() {
		val pipeline = ConstraintPipeline(profileRegistry = ObservationSourceProfileRegistry())
		val observation = PoseObservation(
			sourceId = "unknown:hip",
			target = TrackerPosition.HIP,
			observedAtNanos = 100L,
			position = Vector3(0f, 1f, 0f),
		)

		assertFailsWith<IllegalArgumentException> {
			pipeline.ingest(observation, profileId = "missing")
		}
		assertEquals(0, pipeline.observationCount)
	}

	@Test
	fun pipelineResolvesRegisteredProfilesAndFallsBackAfterAbsoluteTimeout() {
		val registry = ObservationSourceProfileRegistry(
			listOf(
				ObservationSourceProfile.sixDof(
					profileId = "absolute",
					priority = 100,
					positionTimeoutNanos = 50L,
					rotationTimeoutNanos = 50L,
				),
				ObservationSourceProfile.rotationOnly(
					profileId = "imu",
					priority = 10,
					rotationTimeoutNanos = 200L,
				),
			),
		)
		val pipeline = ConstraintPipeline(profileRegistry = registry)

		pipeline.ingest(
			PoseObservation(
				sourceId = "absolute:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				position = Vector3(0.1f, 1f, 0.2f),
				rotation = Quaternion.IDENTITY,
			),
			profileId = "absolute",
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "imu:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				position = Vector3(9f, 9f, 9f),
				rotation = Quaternion.IDENTITY,
			),
			profileId = "imu",
		)

		val fresh = pipeline.resolve(TrackerPosition.HIP, nowNanos = 120L)
		assertEquals("absolute:hip", fresh.position?.sourceId)
		assertEquals("absolute:hip", fresh.rotation?.sourceId)

		val fallback = pipeline.resolve(TrackerPosition.HIP, nowNanos = 151L)
		assertNull(fallback.position)
		assertEquals("imu:hip", fallback.rotation?.sourceId)

		val imuObservation = pipeline.observations().single { it.sourceId == "imu:hip" }
		assertNull(imuObservation.position)
		assertEquals(ObservationQuality.UNAVAILABLE, imuObservation.positionQuality)
	}

	@Test
	fun registryReplacementAppliesToSubsequentIngests() {
		val registry = ObservationSourceProfileRegistry(
			listOf(
				ObservationSourceProfile.rotationOnly(
					profileId = "fallback",
					priority = 10,
					rotationTimeoutNanos = 100L,
				),
			),
		)
		val pipeline = ConstraintPipeline(profileRegistry = registry)

		registry.replace(
			ObservationSourceProfile.sixDof(
				profileId = "fallback",
				priority = 20,
				positionTimeoutNanos = 100L,
				rotationTimeoutNanos = 100L,
			),
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "fallback:hip",
				target = TrackerPosition.HIP,
				observedAtNanos = 10L,
				position = Vector3(1f, 2f, 3f),
				rotation = Quaternion.IDENTITY,
			),
			profileId = "fallback",
		)

		val stored = pipeline.observations().single()
		assertEquals(20, stored.priority)
		assertEquals(Vector3(1f, 2f, 3f), stored.position)
		assertEquals(ObservationQuality.TRACKED, stored.positionQuality)
	}
}
