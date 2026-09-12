package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ObservationBackendTests {
	private class FakeBackend(
		override val backendId: String,
		override val profileId: String,
		private val observations: (Long) -> List<PoseObservation>,
	) : ObservationBackend {
		override fun poll(observedAtNanos: Long): List<PoseObservation> = observations(observedAtNanos)
	}

	private fun tracker(
		id: Int,
		target: TrackerPosition?,
	): Tracker = Tracker(
		device = null,
		id = id,
		name = "test-$id",
		trackerPosition = target,
		hasPosition = true,
		hasRotation = true,
		isInternal = true,
		trackRotDirection = false,
	)

	@Test
	fun runnerPollsMultipleBackendsThroughTheirRegisteredProfiles() {
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
		val absolute = FakeBackend("pico-backend", "absolute") { now ->
			listOf(
				PoseObservation(
					sourceId = "pico:hip",
					target = TrackerPosition.HIP,
					observedAtNanos = now,
					position = Vector3(0.1f, 1f, 0.2f),
					rotation = Quaternion.IDENTITY,
				),
			)
		}
		val imu = FakeBackend("imu-backend", "imu") { now ->
			listOf(
				PoseObservation(
					sourceId = "imu:hip",
					target = TrackerPosition.HIP,
					observedAtNanos = now,
					rotation = Quaternion.IDENTITY,
				),
			)
		}
		val runner = ObservationBackendRunner(pipeline, listOf(absolute, imu))

		assertEquals(2, runner.pollAll(observedAtNanos = 100L))

		val fresh = pipeline.resolve(TrackerPosition.HIP, nowNanos = 120L)
		assertEquals("pico:hip", fresh.position?.sourceId)
		assertEquals("pico:hip", fresh.rotation?.sourceId)

		val fallback = pipeline.resolve(TrackerPosition.HIP, nowNanos = 151L)
		assertNull(fallback.position)
		assertEquals("imu:hip", fallback.rotation?.sourceId)
	}

	@Test
	fun backendIdsAreUniqueAndCanBeReplacedExplicitly() {
		val pipeline = ConstraintPipeline(
			profileRegistry = ObservationSourceProfileRegistry(
				listOf(
					ObservationSourceProfile.rotationOnly("imu", 10, 100L),
				),
			),
		)
		val first = FakeBackend("imu-backend", "imu") { emptyList() }
		val replacement = FakeBackend("imu-backend", "imu") { emptyList() }
		val runner = ObservationBackendRunner(pipeline, listOf(first))

		assertFailsWith<IllegalArgumentException> { runner.register(replacement) }
		assertEquals(first, runner.replace(replacement))
		assertEquals(replacement, runner.snapshot()["imu-backend"])
	}

	@Test
	fun slimeTrackerBackendMirrorsCurrentAssignedTrackers() {
		val assigned = tracker(1, TrackerPosition.HIP).apply {
			status = TrackerStatus.OK
			position = Vector3(0.2f, 1f, -0.1f)
			setRotation(Quaternion.IDENTITY)
		}
		val unassigned = tracker(2, null).apply {
			status = TrackerStatus.OK
		}
		var currentTrackers: List<Tracker> = listOf(assigned, unassigned)
		val backend = SlimeTrackerObservationBackend(
			backendId = "legacy",
			profileId = "legacy-sixdof",
			trackersProvider = { currentTrackers },
		)

		val firstPoll = backend.poll(100L)
		assertEquals(1, firstPoll.size)
		assertEquals("legacy:1", firstPoll.single().sourceId)
		assertEquals(TrackerPosition.HIP, firstPoll.single().target)

		val foot = tracker(3, TrackerPosition.LEFT_FOOT).apply {
			status = TrackerStatus.OK
			position = Vector3(-0.1f, 0f, 0.2f)
			setRotation(Quaternion.IDENTITY)
		}
		currentTrackers = listOf(assigned, foot)

		val secondPoll = backend.poll(200L)
		assertEquals(setOf("legacy:1", "legacy:3"), secondPoll.map { it.sourceId }.toSet())
		assertEquals(setOf(TrackerPosition.HIP, TrackerPosition.LEFT_FOOT), secondPoll.map { it.target }.toSet())
	}
}
