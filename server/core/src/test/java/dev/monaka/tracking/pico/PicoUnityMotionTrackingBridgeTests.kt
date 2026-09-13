package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PicoUnityMotionTrackingBridgeTests {
	private class FakeClient : PicoUnityMotionTrackingClient {
		var ids: List<Long> = listOf(1L)
		var spaceId: String = "unity-space-1"
		val locations = linkedMapOf<Long, PicoUnityMotionTrackerLocationResult>()
		val batteries = linkedMapOf<Long, PicoUnityMotionTrackerBatteryResult>()

		override fun connectedTrackerIds(): List<Long> = ids

		override fun trackingSpaceId(): String = spaceId

		override fun getMotionTrackerLocation(trackerId: Long): PicoUnityMotionTrackerLocationResult =
			locations[trackerId] ?: PicoUnityMotionTrackerLocationResult(
				resultCode = -1,
				isValidPose = false,
			)

		override fun getMotionTrackerBattery(trackerId: Long): PicoUnityMotionTrackerBatteryResult =
			batteries[trackerId] ?: PicoUnityMotionTrackerBatteryResult(resultCode = -1)
	}

	@Test
	fun validUnityPoseMapsToTrackedComponentsAndAdvertisesUnityBasis() {
		val client = FakeClient().apply {
			locations[1L] = PicoUnityMotionTrackerLocationResult(
				resultCode = PICO_UNITY_RESULT_SUCCESS,
				isValidPose = true,
				position = Vector3(1f, 2f, 3f),
				rotation = Quaternion(0.5f, 0.1f, 0.2f, 0.3f),
			)
		}
		val bridge = PicoUnityMotionTrackingBridge(client)

		assertEquals(
			PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
			bridge.coordinateConvention,
		)
		val snapshot = bridge.readPoseSnapshot()
		assertEquals("unity-space-1", snapshot.trackingSpaceId)
		val tracker = snapshot.trackers.single()
		assertEquals("1", tracker.trackerId)
		assertEquals(Vector3(1f, 2f, 3f), tracker.position)
		assertEquals(PicoOtComponentTrackingState.TRACKED, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.TRACKED, tracker.rotationState)
	}

	@Test
	fun invalidPoseRemainsPresentButMapsBothComponentsToLost() {
		val client = FakeClient().apply {
			locations[1L] = PicoUnityMotionTrackerLocationResult(
				resultCode = PICO_UNITY_RESULT_SUCCESS,
				isValidPose = false,
				position = Vector3(0.1f, 1f, 0.2f),
				rotation = Quaternion.IDENTITY,
			)
		}
		val tracker = PicoUnityMotionTrackingBridge(client).readPoseSnapshot().trackers.single()

		assertEquals(PicoOtComponentTrackingState.LOST, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.LOST, tracker.rotationState)
		assertEquals(Vector3(0.1f, 1f, 0.2f), tracker.position)
	}

	@Test
	fun failedLocationCallMapsConnectedTrackerToLostWithoutInventingPose() {
		val client = FakeClient().apply {
			locations[1L] = PicoUnityMotionTrackerLocationResult(
				resultCode = -1010002002,
				isValidPose = false,
			)
		}
		val tracker = PicoUnityMotionTrackingBridge(client).readPoseSnapshot().trackers.single()

		assertEquals(PicoOtComponentTrackingState.LOST, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.LOST, tracker.rotationState)
		assertEquals(null, tracker.position)
		assertEquals(null, tracker.rotation)
	}

	@Test
	fun disconnectedTrackerDisappearsFromAuthoritativeSnapshot() {
		val client = FakeClient().apply {
			ids = listOf(1L, 2L)
			locations[1L] = PicoUnityMotionTrackerLocationResult(
				resultCode = 0,
				isValidPose = true,
				position = Vector3(0f, 0f, 0f),
				rotation = Quaternion.IDENTITY,
			)
			locations[2L] = locations.getValue(1L)
		}
		val bridge = PicoUnityMotionTrackingBridge(client)

		assertEquals(setOf("1", "2"), bridge.readPoseSnapshot().trackers.map { it.trackerId }.toSet())
		client.ids = listOf(2L)
		assertEquals(listOf("2"), bridge.readPoseSnapshot().trackers.map { it.trackerId })
	}

	@Test
	fun batteryLevelsConvertFromSdkZeroToOneRangeIntoPercent() {
		val client = FakeClient().apply {
			ids = listOf(1L, 2L, 3L, 4L)
			batteries[1L] = PicoUnityMotionTrackerBatteryResult(0, 0f)
			batteries[2L] = PicoUnityMotionTrackerBatteryResult(0, 0.505f)
			batteries[3L] = PicoUnityMotionTrackerBatteryResult(0, 1f)
			batteries[4L] = PicoUnityMotionTrackerBatteryResult(-1, 0.8f)
		}
		val battery = PicoUnityMotionTrackingBridge(client).readBatteryPercent(
			setOf("1", "2", "3", "4"),
		)

		assertEquals(0, battery["1"])
		assertEquals(51, battery["2"])
		assertEquals(100, battery["3"])
		assertTrue("4" !in battery)
	}

	@Test
	fun malformedSdkIdentityAndDuplicateConnectionStateAreRejected() {
		val duplicateIds = FakeClient().apply { ids = listOf(1L, 1L) }
		assertFailsWith<IllegalArgumentException> {
			PicoUnityMotionTrackingBridge(duplicateIds).readPoseSnapshot()
		}

		val bridge = PicoUnityMotionTrackingBridge(FakeClient())
		assertFailsWith<IllegalArgumentException> {
			bridge.readBatteryPercent(setOf("not-a-number"))
		}
	}

	@Test
	fun validPoseRequiresBothComponentsBecauseUnitySdkValidityIsWholePose() {
		assertFailsWith<IllegalArgumentException> {
			PicoUnityMotionTrackerLocationResult(
				resultCode = 0,
				isValidPose = true,
				position = Vector3(0f, 0f, 0f),
				rotation = null,
			)
		}
	}
}
