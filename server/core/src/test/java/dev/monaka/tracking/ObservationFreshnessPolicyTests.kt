package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservationFreshnessPolicyTests {
	@Test
	fun expiresPositionAndRotationIndependentlyAfterTheirTimeouts() {
		val policy = ObservationFreshnessPolicy(
			positionTimeoutNanos = 50L,
			rotationTimeoutNanos = 200L,
		)
		val observation = PoseObservation(
			sourceId = "absolute",
			target = TrackerPosition.HIP,
			observedAtNanos = 100L,
			position = Vector3(1f, 2f, 3f),
			rotation = Quaternion.IDENTITY,
		)

		val atBoundary = policy.apply(observation, nowNanos = 150L)
		assertEquals(ObservationQuality.TRACKED, atBoundary.positionQuality)
		assertEquals(ObservationQuality.TRACKED, atBoundary.rotationQuality)

		val positionExpired = policy.apply(observation, nowNanos = 151L)
		assertEquals(ObservationQuality.STALE, positionExpired.positionQuality)
		assertEquals(ObservationQuality.TRACKED, positionExpired.rotationQuality)
		assertEquals(Vector3(1f, 2f, 3f), positionExpired.position)

		val bothExpired = policy.apply(observation, nowNanos = 301L)
		assertEquals(ObservationQuality.STALE, bothExpired.positionQuality)
		assertEquals(ObservationQuality.STALE, bothExpired.rotationQuality)
	}

	@Test
	fun explicitUnusableQualityIsNotRewrittenByAge() {
		val policy = ObservationFreshnessPolicy(positionTimeoutNanos = 1L, rotationTimeoutNanos = 1L)
		val observation = PoseObservation(
			sourceId = "lost-source",
			target = TrackerPosition.LEFT_FOOT,
			observedAtNanos = 10L,
			position = Vector3(1f, 0f, 0f),
			rotation = Quaternion.IDENTITY,
			positionQuality = ObservationQuality.LOST,
			rotationQuality = ObservationQuality.UNAVAILABLE,
		)

		val aged = policy.apply(observation, nowNanos = 1_000L)

		assertEquals(ObservationQuality.LOST, aged.positionQuality)
		assertEquals(ObservationQuality.UNAVAILABLE, aged.rotationQuality)
	}

	@Test
	fun futureTimestampIsTreatedAsZeroAge() {
		val policy = ObservationFreshnessPolicy(positionTimeoutNanos = 0L, rotationTimeoutNanos = 0L)
		val observation = PoseObservation(
			sourceId = "future",
			target = TrackerPosition.RIGHT_FOOT,
			observedAtNanos = 200L,
			position = Vector3(0f, 0f, 0f),
		)

		val aged = policy.apply(observation, nowNanos = 100L)

		assertEquals(ObservationQuality.TRACKED, aged.positionQuality)
	}

	@Test
	fun pipelineDropsStaleAbsolutePositionWhileKeepingFreshImuRotation() {
		val pipeline = ConstraintPipeline(
			freshnessPolicy = ObservationFreshnessPolicy(
				positionTimeoutNanos = 50L,
				rotationTimeoutNanos = 50L,
			),
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "pico",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				priority = 100,
				position = Vector3(0.1f, 1f, 0.2f),
			),
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "imu",
				target = TrackerPosition.HIP,
				observedAtNanos = 180L,
				priority = 10,
				rotation = Quaternion.IDENTITY,
			),
		)

		val fallback = pipeline.resolve(TrackerPosition.HIP, nowNanos = 190L)

		assertNull(fallback.position)
		assertEquals("imu", fallback.rotation?.sourceId)
		assertEquals(ObservationQuality.TRACKED, fallback.rotation?.quality)

		pipeline.ingest(
			PoseObservation(
				sourceId = "pico",
				target = TrackerPosition.HIP,
				observedAtNanos = 190L,
				priority = 100,
				position = Vector3(0.2f, 1f, 0.2f),
			),
		)

		val recovered = pipeline.resolve(TrackerPosition.HIP, nowNanos = 190L)
		assertEquals("pico", recovered.position?.sourceId)
		assertEquals(Vector3(0.2f, 1f, 0.2f), recovered.position?.value)
		assertEquals("imu", recovered.rotation?.sourceId)
	}
}
