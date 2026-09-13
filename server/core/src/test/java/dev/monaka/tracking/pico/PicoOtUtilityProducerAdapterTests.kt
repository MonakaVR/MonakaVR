package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PicoOtUtilityProducerAdapterTests {
	private class FakeBridge(
		override val coordinateConvention: PicoOtCoordinateConvention,
		var snapshotValue: PicoOtUtilityPoseSnapshot,
	) : PicoOtUtilityBridge {
		var lastBatteryRequest: Set<String>? = null
		var batteryResult: Map<String, Int> = emptyMap()

		override fun readPoseSnapshot(): PicoOtUtilityPoseSnapshot = snapshotValue

		override fun readBatteryPercent(trackerIds: Set<String>): Map<String, Int> {
			lastBatteryRequest = trackerIds
			return batteryResult
		}
	}

	@Test
	fun adapterPreservesStableIdsComponentStatesAndCoordinateConvention() {
		val bridge = FakeBridge(
			coordinateConvention = PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
			snapshotValue = PicoOtUtilityPoseSnapshot(
				trackingSpaceId = "pico-space-a",
				trackers = listOf(
					PicoOtUtilityTrackerPose(
						trackerId = "tracker-7",
						position = null,
						rotation = Quaternion.IDENTITY,
						positionState = PicoOtComponentTrackingState.LOST,
						rotationState = PicoOtComponentTrackingState.TRACKED,
					),
				),
			),
		)
		val adapter = PicoOtUtilityProducerAdapter(bridge)

		val snapshot = adapter.snapshot()

		assertEquals("pico-space-a", snapshot.trackingSpaceId)
		assertEquals(PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS, snapshot.coordinateConvention)
		val tracker = snapshot.trackers.single()
		assertEquals("tracker-7", tracker.trackerId)
		assertNull(tracker.position)
		assertEquals(Quaternion.IDENTITY, tracker.rotation)
		assertEquals(PicoOtComponentTrackingState.LOST, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.TRACKED, tracker.rotationState)
	}

	@Test
	fun adapterDelegatesSparseBatteryPollForExactlyRequestedTrackers() {
		val bridge = FakeBridge(
			coordinateConvention = PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
			snapshotValue = PicoOtUtilityPoseSnapshot("space-1", emptyList()),
		)
		bridge.batteryResult = mapOf("left-foot" to 61)
		val adapter = PicoOtUtilityProducerAdapter(bridge)

		val result = adapter.pollBatteryPercent(setOf("left-foot", "right-foot"))

		assertEquals(setOf("left-foot", "right-foot"), bridge.lastBatteryRequest)
		assertEquals(mapOf("left-foot" to 61), result)
	}

	@Test
	fun adapterCreatedSessionCarriesMatchingConventionIntoRuntimeFrame() {
		val bridge = FakeBridge(
			coordinateConvention = PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
			snapshotValue = PicoOtUtilityPoseSnapshot(
				trackingSpaceId = "space-1",
				trackers = listOf(
					PicoOtUtilityTrackerPose(
						trackerId = "hip",
						position = Vector3(0.1f, 1f, 0.2f),
						rotation = Quaternion.IDENTITY,
					),
				),
			),
		)
		bridge.batteryResult = mapOf("hip" to 82)
		val adapter = PicoOtUtilityProducerAdapter(bridge)
		val sent = mutableListOf<PicoOtTransportFrame>()
		val runtime = PicoOtProducerRuntime(
			session = adapter.createSession("session-a", "space-1"),
			cadence = PicoOtProducerCadence(
				PicoOtProducerCadencePolicy(
					poseIntervalNanos = 10L,
					batteryPollIntervalNanos = 100L,
				),
			),
			poseSource = adapter,
			batterySource = adapter,
			sender = PicoOtFrameSender { frame -> sent.add(frame).let { 1 } },
		)

		assertTrue(runtime.tick(0L))
		val frame = sent.single()
		assertEquals(PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS, frame.coordinateConvention)
		assertEquals("space-1", frame.trackingSpaceId)
		assertEquals(Vector3(0.1f, 1f, 0.2f), frame.trackers.single().position)
		assertEquals(82, frame.trackers.single().batteryPercent)
	}

	@Test
	fun runtimeRejectsPoseConventionThatDoesNotMatchProducerSession() {
		val pose = PicoOtProducerPoseSnapshot(
			trackingSpaceId = "space-1",
			trackers = emptyList(),
			coordinateConvention = PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
		)
		val runtime = PicoOtProducerRuntime(
			session = PicoOtProducerSession(
				sessionId = "session-a",
				initialTrackingSpaceId = "space-1",
				coordinateConvention = PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
			),
			cadence = PicoOtProducerCadence(
				PicoOtProducerCadencePolicy(
					poseIntervalNanos = 10L,
					batteryPollIntervalNanos = 100L,
				),
			),
			poseSource = PicoOtProducerPoseSource { pose },
			batterySource = PicoOtProducerBatterySource { emptyMap() },
			sender = PicoOtFrameSender { 1 },
		)

		assertFailsWith<IllegalArgumentException> { runtime.tick(0L) }
		assertEquals(0L, runtime.framesSent)
		assertEquals(0L, runtime.batteryPolls)
	}

	@Test
	fun duplicateUtilityTrackerIdsAreRejectedBeforeTheyReachRuntime() {
		assertFailsWith<IllegalArgumentException> {
			PicoOtUtilityPoseSnapshot(
				trackingSpaceId = "space-1",
				trackers = listOf(
					PicoOtUtilityTrackerPose("same", rotation = Quaternion.IDENTITY),
					PicoOtUtilityTrackerPose("same", rotation = Quaternion.IDENTITY),
				),
			)
		}
	}
}
