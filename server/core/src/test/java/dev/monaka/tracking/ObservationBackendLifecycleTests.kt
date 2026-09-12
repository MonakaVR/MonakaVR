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

class ObservationBackendLifecycleTests {
	private class FakeBackend(
		override val backendId: String,
		override val profileId: String,
		override val sourceSetMode: ObservationSourceSetMode = ObservationSourceSetMode.INCREMENTAL,
		private val observations: () -> List<PoseObservation>,
	) : ObservationBackend {
		override fun poll(observedAtNanos: Long): List<PoseObservation> =
			observations().map { it.copy(observedAtNanos = observedAtNanos) }
	}

	private fun registry(): ObservationSourceProfileRegistry = ObservationSourceProfileRegistry(
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

	private fun hipObservation(
		sourceId: String,
		position: Vector3? = null,
		rotation: Quaternion? = null,
	): PoseObservation = PoseObservation(
		sourceId = sourceId,
		target = TrackerPosition.HIP,
		observedAtNanos = 0L,
		position = position,
		rotation = rotation,
	)

	@Test
	fun authoritativeSnapshotRemovesDisappearedSourcesImmediately() {
		val pipeline = ConstraintPipeline(profileRegistry = registry())
		var absoluteObservations = listOf(
			hipObservation(
				sourceId = "pico:hip",
				position = Vector3(0.1f, 1f, 0.2f),
				rotation = Quaternion.IDENTITY,
			),
		)
		val absolute = FakeBackend(
			backendId = "pico-backend",
			profileId = "absolute",
			sourceSetMode = ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT,
			observations = { absoluteObservations },
		)
		val imu = FakeBackend("imu-backend", "imu") {
			listOf(hipObservation("imu:hip", rotation = Quaternion.IDENTITY))
		}
		val runner = ObservationBackendRunner(pipeline, listOf(absolute, imu))

		runner.pollAll(100L)
		assertEquals("pico:hip", pipeline.resolve(TrackerPosition.HIP, 100L).rotation?.sourceId)
		assertEquals(setOf("pico:hip"), runner.ownedSources("pico-backend"))

		absoluteObservations = emptyList()
		runner.poll("pico-backend", 110L)

		val fallback = pipeline.resolve(TrackerPosition.HIP, 110L)
		assertNull(fallback.position)
		assertEquals("imu:hip", fallback.rotation?.sourceId)
		assertEquals(emptySet(), runner.ownedSources("pico-backend"))
		assertEquals(setOf("imu:hip"), pipeline.observations().map { it.sourceId }.toSet())
	}

	@Test
	fun incrementalBackendKeepsOmittedSourcesUntilFreshnessExpires() {
		val pipeline = ConstraintPipeline(profileRegistry = registry())
		var observations = listOf(
			hipObservation(
				sourceId = "event:hip",
				position = Vector3(0f, 1f, 0f),
				rotation = Quaternion.IDENTITY,
			),
		)
		val backend = FakeBackend("event-backend", "absolute", observations = { observations })
		val runner = ObservationBackendRunner(pipeline, listOf(backend))

		runner.poll("event-backend", 100L)
		observations = emptyList()
		runner.poll("event-backend", 120L)

		assertEquals("event:hip", pipeline.resolve(TrackerPosition.HIP, 120L).position?.sourceId)
		assertEquals(setOf("event:hip"), runner.ownedSources("event-backend"))
		assertNull(pipeline.resolve(TrackerPosition.HIP, 151L).position)
	}

	@Test
	fun removingBackendPurgesOwnedSourcesAndAllowsCleanReconnect() {
		val pipeline = ConstraintPipeline(profileRegistry = registry())
		val observation = hipObservation(
			sourceId = "pico:hip",
			position = Vector3(0f, 1f, 0f),
			rotation = Quaternion.IDENTITY,
		)
		val first = FakeBackend("pico-backend", "absolute") { listOf(observation) }
		val runner = ObservationBackendRunner(pipeline, listOf(first))

		runner.poll("pico-backend", 100L)
		assertEquals(1, pipeline.observationCount)

		assertEquals(first, runner.remove("pico-backend"))
		assertEquals(0, pipeline.observationCount)
		assertNull(pipeline.resolve(TrackerPosition.HIP, 100L).position)

		val reconnected = FakeBackend("pico-backend", "absolute") { listOf(observation) }
		runner.register(reconnected)
		runner.poll("pico-backend", 200L)

		assertEquals(1, pipeline.observationCount)
		assertEquals("pico:hip", pipeline.resolve(TrackerPosition.HIP, 200L).position?.sourceId)
	}

	@Test
	fun replacingBackendPurgesSourcesFromPreviousInstance() {
		val pipeline = ConstraintPipeline(profileRegistry = registry())
		val first = FakeBackend("pico-backend", "absolute") {
			listOf(
				hipObservation(
					"pico:old",
					position = Vector3(0f, 1f, 0f),
					rotation = Quaternion.IDENTITY,
				),
			)
		}
		val replacement = FakeBackend("pico-backend", "absolute") { emptyList() }
		val runner = ObservationBackendRunner(pipeline, listOf(first))

		runner.poll("pico-backend", 100L)
		assertEquals(1, pipeline.observationCount)

		assertEquals(first, runner.replace(replacement))
		assertEquals(0, pipeline.observationCount)
		assertEquals(emptySet(), runner.ownedSources("pico-backend"))
	}

	@Test
	fun sourceIdCannotBeOwnedByTwoBackends() {
		val pipeline = ConstraintPipeline(profileRegistry = registry())
		val shared = hipObservation("shared:hip", rotation = Quaternion.IDENTITY)
		val first = FakeBackend("first", "imu") { listOf(shared) }
		val second = FakeBackend("second", "imu") { listOf(shared) }
		val runner = ObservationBackendRunner(pipeline, listOf(first, second))

		runner.poll("first", 100L)
		assertFailsWith<IllegalArgumentException> {
			runner.poll("second", 100L)
		}
		assertEquals(setOf("shared:hip"), runner.ownedSources("first"))
		assertEquals(emptySet(), runner.ownedSources("second"))
	}

	@Test
	fun slimeTrackerBackendUsesAuthoritativeSnapshots() {
		val tracker = Tracker(
			device = null,
			id = 1,
			name = "legacy-1",
			trackerPosition = TrackerPosition.HIP,
			hasPosition = true,
			hasRotation = true,
			isInternal = true,
			trackRotDirection = false,
		).apply {
			status = TrackerStatus.OK
			position = Vector3(0f, 1f, 0f)
			setRotation(Quaternion.IDENTITY)
		}
		var trackers: List<Tracker> = listOf(tracker)
		val backend = SlimeTrackerObservationBackend(
			backendId = "legacy",
			profileId = "absolute",
			trackersProvider = { trackers },
		)
		val pipeline = ConstraintPipeline(profileRegistry = registry())
		val runner = ObservationBackendRunner(pipeline, listOf(backend))

		assertEquals(ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT, backend.sourceSetMode)
		runner.poll("legacy", 100L)
		assertEquals(1, pipeline.observationCount)

		trackers = emptyList()
		runner.poll("legacy", 110L)
		assertEquals(0, pipeline.observationCount)
	}
}
