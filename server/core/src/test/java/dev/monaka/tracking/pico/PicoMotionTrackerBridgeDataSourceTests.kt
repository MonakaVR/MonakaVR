package dev.monaka.tracking.pico

import dev.monaka.tracking.ConstraintPipeline
import dev.monaka.tracking.ObservationBackendRunner
import dev.monaka.tracking.ObservationQuality
import dev.monaka.tracking.ObservationSourceProfile
import dev.monaka.tracking.ObservationSourceProfileRegistry
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PicoMotionTrackerBridgeDataSourceTests {
	private val identityMapper = PicoMotionTrackerBridgePoseMapper { state ->
		PicoMotionTrackerBridgeMappedPose(
			position = state.positionMeters,
			rotation = state.orientation,
		)
	}

	private fun state(
		serial: String = "PICO-SERIAL-001",
		connectedSnapshot: Boolean = true,
		positionValid: Boolean = true,
		orientationSamplePresent: Boolean = true,
		position: Vector3? = Vector3(0.1f, 1f, 0.2f),
		rotation: Quaternion? = Quaternion.IDENTITY,
		posePcMonotonicNanos: Long = 100L,
	) = PicoMotionTrackerBridgeState(
		serial = serial,
		connectedSnapshot = connectedSnapshot,
		positionMeters = position,
		orientation = rotation,
		positionValid = positionValid,
		orientationSamplePresent = orientationSamplePresent,
		posePcMonotonicNanos = posePcMonotonicNanos,
	)

	@Test
	fun validOpticalPoseMapsBothComponentsToTracked() {
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider { listOf(state()) },
			poseMapper = identityMapper,
		)

		val tracker = source.snapshot(999L).trackers.single()

		assertEquals("PICO-SERIAL-001", tracker.trackerId)
		assertEquals(Vector3(0.1f, 1f, 0.2f), tracker.position)
		assertEquals(Quaternion.IDENTITY, tracker.rotation)
		assertEquals(PicoOtComponentTrackingState.TRACKED, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.TRACKED, tracker.rotationState)
		assertEquals(100L, tracker.observedAtNanos)
	}

	@Test
	fun opticalLossKeepsContinuingOrientationAsDegradedComponent() {
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider {
				listOf(
					state(
						positionValid = false,
						orientationSamplePresent = true,
					),
				)
			},
			poseMapper = identityMapper,
		)

		val tracker = source.snapshot(200L).trackers.single()

		assertEquals(PicoOtComponentTrackingState.LOST, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.DEGRADED, tracker.rotationState)
		assertNotNull(tracker.position)
		assertNotNull(tracker.rotation)
	}

	@Test
	fun missingOrientationSampleMapsRotationToLostWithoutInventingValidity() {
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider {
				listOf(
					state(
						positionValid = true,
						orientationSamplePresent = false,
						rotation = null,
					),
				)
			},
			poseMapper = identityMapper,
		)

		val tracker = source.snapshot(200L).trackers.single()

		assertEquals(PicoOtComponentTrackingState.TRACKED, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.LOST, tracker.rotationState)
		assertNull(tracker.rotation)
	}

	@Test
	fun enumerationConnectedSnapshotIsNotUsedAsLivePresenceGate() {
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider {
				listOf(state(connectedSnapshot = false))
			},
			poseMapper = identityMapper,
		)

		assertEquals(listOf("PICO-SERIAL-001"), source.snapshot(200L).trackers.map { it.trackerId })
	}

	@Test
	fun bridgePcTimestampDrivesMonakaFreshnessInsteadOfPollTime() {
		val registry = ObservationSourceProfileRegistry(
			listOf(
				ObservationSourceProfile.sixDof(
					profileId = "pico-bridge",
					priority = 100,
					positionTimeoutNanos = 50L,
					rotationTimeoutNanos = 50L,
				),
			),
		)
		val pipeline = ConstraintPipeline(profileRegistry = registry)
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider {
				listOf(state(posePcMonotonicNanos = 100L))
			},
			poseMapper = identityMapper,
		)
		val backend = PicoOtObservationBackend(
			backendId = "pico-bridge",
			profileId = "pico-bridge",
			dataSource = source,
			targetResolver = { TrackerPosition.HIP },
		)
		val runner = ObservationBackendRunner(pipeline, listOf(backend))

		runner.poll("pico-bridge", 1_000L)
		val rawObservation = pipeline.observations().single()
		assertEquals(100L, rawObservation.observedAtNanos)

		val agedObservation = pipeline.observations(1_000L).single()
		assertEquals(ObservationQuality.STALE, agedObservation.positionQuality)
		assertEquals(ObservationQuality.STALE, agedObservation.rotationQuality)

		val resolved = pipeline.resolve(TrackerPosition.HIP, 1_000L)
		assertNull(resolved.position)
		assertNull(resolved.rotation)
	}

	@Test
	fun duplicatePersistentSerialsAreRejected() {
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider {
				listOf(state(), state())
			},
			poseMapper = identityMapper,
		)

		assertFailsWith<IllegalArgumentException> { source.snapshot(200L) }
	}

	@Test
	fun mapperMustNotDropAComponentAdvertisedAsValidByBridge() {
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider { listOf(state()) },
			poseMapper = PicoMotionTrackerBridgePoseMapper {
				PicoMotionTrackerBridgeMappedPose(position = null, rotation = Quaternion.IDENTITY)
			},
		)

		assertFailsWith<IllegalArgumentException> { source.snapshot(200L) }
	}
}
