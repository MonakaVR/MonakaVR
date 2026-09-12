package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstraintPipelineTests {
	@Test
	fun storeKeepsLatestObservationPerSourceAndRejectsOutOfOrderUpdates() {
		val store = ObservationStore()
		val first = PoseObservation(
			sourceId = "tracker:1",
			target = TrackerPosition.HIP,
			observedAtNanos = 100L,
			position = Vector3(1f, 0f, 0f),
		)
		val reassigned = PoseObservation(
			sourceId = "tracker:1",
			target = TrackerPosition.CHEST,
			observedAtNanos = 200L,
			position = Vector3(2f, 0f, 0f),
		)
		val lateOldPacket = PoseObservation(
			sourceId = "tracker:1",
			target = TrackerPosition.LEFT_FOOT,
			observedAtNanos = 150L,
			position = Vector3(3f, 0f, 0f),
		)

		assertTrue(store.put(first))
		assertTrue(store.put(reassigned))
		assertFalse(store.put(lateOldPacket))

		assertEquals(1, store.size)
		assertTrue(store.observationsFor(TrackerPosition.HIP).isEmpty())
		assertTrue(store.observationsFor(TrackerPosition.LEFT_FOOT).isEmpty())
		assertEquals(reassigned, store.observationsFor(TrackerPosition.CHEST).single())
	}

	@Test
	fun pipelineResolvesComponentsAcrossSourcesAndTargets() {
		val pipeline = ConstraintPipeline()
		pipeline.ingestAll(
			listOf(
				PoseObservation(
					sourceId = "absolute:hip",
					target = TrackerPosition.HIP,
					observedAtNanos = 100L,
					priority = 100,
					position = Vector3(0.1f, 1f, 0f),
					positionQuality = ObservationQuality.DEGRADED,
				),
				PoseObservation(
					sourceId = "imu:hip",
					target = TrackerPosition.HIP,
					observedAtNanos = 110L,
					priority = 10,
					rotation = Quaternion.IDENTITY,
				),
				PoseObservation(
					sourceId = "absolute:left-foot",
					target = TrackerPosition.LEFT_FOOT,
					observedAtNanos = 120L,
					position = Vector3(-0.2f, 0f, 0f),
				),
			),
		)

		val hip = pipeline.resolve(TrackerPosition.HIP)
		assertEquals("absolute:hip", hip.position?.sourceId)
		assertEquals("imu:hip", hip.rotation?.sourceId)

		val all = pipeline.resolveAll()
		assertEquals(setOf(TrackerPosition.HIP, TrackerPosition.LEFT_FOOT), all.keys)
		assertEquals("absolute:left-foot", all[TrackerPosition.LEFT_FOOT]?.position?.sourceId)
	}

	@Test
	fun newerLostObservationSuppressesPreviousTrackedComponent() {
		val pipeline = ConstraintPipeline()
		pipeline.ingest(
			PoseObservation(
				sourceId = "absolute:1",
				target = TrackerPosition.RIGHT_FOOT,
				observedAtNanos = 100L,
				position = Vector3(0.2f, 0f, 0f),
			),
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "absolute:1",
				target = TrackerPosition.RIGHT_FOOT,
				observedAtNanos = 110L,
				position = Vector3(0.2f, 0f, 0f),
				positionQuality = ObservationQuality.LOST,
			),
		)

		assertNull(pipeline.resolve(TrackerPosition.RIGHT_FOOT).position)
	}

	@Test
	fun removingPrimarySourceRevealsStoredFallback() {
		val pipeline = ConstraintPipeline()
		pipeline.ingest(
			PoseObservation(
				sourceId = "primary",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				priority = 100,
				rotation = Quaternion.IDENTITY,
			),
		)
		pipeline.ingest(
			PoseObservation(
				sourceId = "fallback",
				target = TrackerPosition.HIP,
				observedAtNanos = 100L,
				priority = 10,
				rotation = Quaternion.IDENTITY,
				rotationQuality = ObservationQuality.DEGRADED,
			),
		)

		assertEquals("primary", pipeline.resolve(TrackerPosition.HIP).rotation?.sourceId)
		assertEquals("primary", pipeline.removeSource("primary")?.sourceId)
		assertEquals("fallback", pipeline.resolve(TrackerPosition.HIP).rotation?.sourceId)
		assertEquals(1, pipeline.observationCount)
	}
}
