package dev.monaka.tracking.pico

import dev.monaka.tracking.ConstraintPipeline
import dev.monaka.tracking.ObservationBackendRunner
import dev.monaka.tracking.ObservationQuality
import dev.monaka.tracking.ObservationSourceProfile
import dev.monaka.tracking.ObservationSourceProfileRegistry
import dev.monaka.tracking.ObservationSourceSetMode
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PicoOtObservationBackendTests {
	@Test
	fun mapsRawSnapshotIntoAssignedComponentWiseObservation() {
		val snapshot = PicoOtSnapshot(
			listOf(
				PicoOtTrackerSample(
					trackerId = "pico-001",
					position = Vector3(0.1f, 1f, 0.2f),
					rotation = Quaternion.IDENTITY,
					positionState = PicoOtComponentTrackingState.DEGRADED,
					rotationState = PicoOtComponentTrackingState.TRACKED,
					batteryPercent = 73,
				),
				PicoOtTrackerSample(
					trackerId = "unassigned",
					position = Vector3(9f, 9f, 9f),
					rotation = Quaternion.IDENTITY,
					batteryPercent = 44,
				),
			),
		)
		val backend = PicoOtObservationBackend(
			backendId = "pico-ot",
			profileId = "pico-sixdof",
			dataSource = PicoOtDataSource { snapshot },
			targetResolver = { id -> if (id == "pico-001") TrackerPosition.LEFT_FOOT else null },
		)

		val observation = backend.poll(123L).single()

		assertEquals(ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT, backend.sourceSetMode)
		assertEquals("pico-ot:pico-001", observation.sourceId)
		assertEquals(TrackerPosition.LEFT_FOOT, observation.target)
		assertEquals(123L, observation.observedAtNanos)
		assertEquals(Vector3(0.1f, 1f, 0.2f), observation.position)
		assertEquals(Quaternion.IDENTITY, observation.rotation)
		assertEquals(ObservationQuality.DEGRADED, observation.positionQuality)
		assertEquals(ObservationQuality.TRACKED, observation.rotationQuality)
		assertEquals(2, backend.lastSnapshot?.trackers?.size)
		assertEquals(73, backend.lastSnapshot?.trackers?.first()?.batteryPercent)
	}

	@Test
	fun componentLossRemainsExplicitWithoutDiscardingLastPoseValue() {
		val backend = PicoOtObservationBackend(
			backendId = "pico-ot",
			profileId = "pico-sixdof",
			dataSource = PicoOtDataSource {
				PicoOtSnapshot(
					listOf(
						PicoOtTrackerSample(
							trackerId = "hip",
							position = Vector3(0.2f, 1f, 0f),
							rotation = Quaternion.IDENTITY,
							positionState = PicoOtComponentTrackingState.LOST,
							rotationState = PicoOtComponentTrackingState.TRACKED,
						),
					),
				)
			},
			targetResolver = { TrackerPosition.HIP },
		)

		val observation = backend.poll(10L).single()

		assertEquals(Vector3(0.2f, 1f, 0f), observation.position)
		assertEquals(ObservationQuality.LOST, observation.positionQuality)
		assertEquals(Quaternion.IDENTITY, observation.rotation)
		assertEquals(ObservationQuality.TRACKED, observation.rotationQuality)
	}

	@Test
	fun authoritativeSnapshotRemovesDisappearedTrackerImmediately() {
		var current = PicoOtSnapshot(
			listOf(
				PicoOtTrackerSample(
					trackerId = "hip",
					position = Vector3(0f, 1f, 0f),
					rotation = Quaternion.IDENTITY,
				),
			),
		)
		val registry = ObservationSourceProfileRegistry(
			listOf(
				ObservationSourceProfile.sixDof(
					profileId = "pico-sixdof",
					priority = 100,
					positionTimeoutNanos = Long.MAX_VALUE,
					rotationTimeoutNanos = Long.MAX_VALUE,
				),
			),
		)
		val pipeline = ConstraintPipeline(profileRegistry = registry)
		val backend = PicoOtObservationBackend(
			backendId = "pico-ot",
			profileId = "pico-sixdof",
			dataSource = PicoOtDataSource { current },
			targetResolver = { TrackerPosition.HIP },
		)
		val runner = ObservationBackendRunner(pipeline, listOf(backend))

		assertEquals(1, runner.poll("pico-ot", 100L))
		assertNotNull(pipeline.resolve(TrackerPosition.HIP, 100L).position)
		assertEquals(setOf("pico-ot:hip"), runner.ownedSources("pico-ot"))

		current = PicoOtSnapshot(emptyList())
		assertEquals(0, runner.poll("pico-ot", 101L))

		assertEquals(0, pipeline.observationCount)
		assertNull(pipeline.resolve(TrackerPosition.HIP, 101L).position)
		assertEquals(emptySet(), runner.ownedSources("pico-ot"))
	}

	@Test
	fun removingBodyAssignmentAlsoReleasesPreviousConstraint() {
		var assigned: TrackerPosition? = TrackerPosition.RIGHT_FOOT
		val registry = ObservationSourceProfileRegistry(
			listOf(
				ObservationSourceProfile.sixDof("pico-sixdof", 100, 1_000L, 1_000L),
			),
		)
		val pipeline = ConstraintPipeline(profileRegistry = registry)
		val backend = PicoOtObservationBackend(
			backendId = "pico-ot",
			profileId = "pico-sixdof",
			dataSource = PicoOtDataSource {
				PicoOtSnapshot(
					listOf(
						PicoOtTrackerSample(
							trackerId = "foot",
							position = Vector3(0.1f, 0f, 0.2f),
							rotation = Quaternion.IDENTITY,
						),
					),
				)
			},
			targetResolver = { assigned },
		)
		val runner = ObservationBackendRunner(pipeline, listOf(backend))

		runner.poll("pico-ot", 1L)
		assertNotNull(pipeline.resolve(TrackerPosition.RIGHT_FOOT, 1L).position)

		assigned = null
		runner.poll("pico-ot", 2L)

		assertNull(pipeline.resolve(TrackerPosition.RIGHT_FOOT, 2L).position)
		assertEquals(0, pipeline.observationCount)
	}

	@Test
	fun rawContractRejectsInvalidBatteryAndDuplicateTrackerIds() {
		assertFailsWith<IllegalArgumentException> {
			PicoOtTrackerSample(trackerId = "bad-battery", batteryPercent = 101)
		}
		val tracker = PicoOtTrackerSample(trackerId = "duplicate")
		assertFailsWith<IllegalArgumentException> {
			PicoOtSnapshot(listOf(tracker, tracker))
		}
	}
}
