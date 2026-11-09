package dev.monaka.tracking.desktop

import dev.monaka.tracking.TrackingModality
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.Battery
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import dev.slimevr.desktop.platform.ProtobufMessages.TrackerAdded
import dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus as WireTrackerStatus
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.trackers.Device
import dev.slimevr.tracking.trackers.DeviceOrigin
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerRole
import dev.slimevr.tracking.trackers.TrackerStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteTrackerReconnectTests {
	@Test fun reconnectReusesTheLeftControllerAndRejectsOldTransportPacketsAtReusedIndices() {
		val bridge = Capture()
		val first = bridge.open()
		bridge.read(first, added(1, "left-serial"), status(1, WireTrackerStatus.Status.OK), pose(1, 1f), battery(1, 0.7f))
		val left = bridge.registered.single()
		val device = left.device
		left.trackerPosition = TrackerPosition.LEFT_HAND
		left.customName = "My controller"
		assertEquals(1f, left.position.x)
		assertEquals(TrackerStatus.OK, left.status)

		assertTrue(bridge.close(first))
		bridge.dataRead()
		assertNull(bridge.byIndex(1))
		assertEquals(TrackingModality.NONE, left.sampleModality)
		assertEquals(TrackerStatus.DISCONNECTED, left.status)

		val second = bridge.open()
		bridge.read(second, added(1, "right-serial"), added(7, "left-serial"), status(7, WireTrackerStatus.Status.OK), pose(7, 7f), battery(7, 0.5f))
		val right = bridge.registered.last()
		assertEquals(listOf(left, right), bridge.registered)
		assertSame(left, bridge.byIndex(7))
		assertSame(right, bridge.byIndex(1))
		assertSame(device, left.device)
		assertEquals(1, left.trackerNum)
		assertEquals(TrackerPosition.LEFT_HAND, left.trackerPosition)
		assertEquals("My controller", left.customName)
		assertEquals(7f, left.position.x)
		assertEquals(0.5f, left.batteryLevel)
		assertEquals(TrackerStatus.OK, left.status)

		bridge.read(second, status(1, WireTrackerStatus.Status.OK), pose(1, 2f), battery(1, 0.9f))
		bridge.read(first, added(1, "left-serial"), status(1, WireTrackerStatus.Status.DISCONNECTED), pose(1, 99f), battery(1, 0.1f))
		bridge.read(null, pose(1, 99f))
		assertSame(right, bridge.byIndex(1))
		assertEquals(2f, right.position.x)
		assertEquals(0.9f, right.batteryLevel)
		assertEquals(TrackerStatus.OK, right.status)
		assertEquals(listOf(left, right), bridge.registered)
	}

	@Test fun conflictingSerialRetiresOnlyTheAffectedWireIndexAndRepeatedRegistrationIsIdempotent() {
		val bridge = Capture()
		val session = bridge.open()
		bridge.read(session, added(1, "left-serial"), added(1, "left-serial"), pose(1, 1f), added(2, "other-serial"), status(2, WireTrackerStatus.Status.OK), pose(2, 2f))
		val (left, other) = bridge.registered
		bridge.read(session, added(2, "left-serial"), pose(2, 99f))
		assertSame(left, bridge.byIndex(1))
		assertNull(bridge.byIndex(2))
		assertEquals(1f, left.position.x)
		assertEquals(2f, other.position.x)
		assertEquals(TrackerStatus.DISCONNECTED, other.status)
		bridge.read(session, added(2, "other-serial"), status(2, WireTrackerStatus.Status.OK), pose(2, 3f))
		assertSame(other, bridge.byIndex(2))
		assertEquals(3f, other.position.x)
		assertEquals(listOf(left, other), bridge.registered)
	}

	@Test fun serialIdentityIsLocalToEachBridgeAndRequiresANonblankSerial() {
		val bridge = Capture()
		val another = Capture()
		val first = bridge.open()
		bridge.read(first, added(1, "serial"), added(2, ""), added(2, ""))
		another.read(another.open(), added(1, "serial"))
		val (known, anonymous) = bridge.registered
		assertNotSame(known, another.registered.single())

		// A fast reconnect can complete before the next server tick.
		assertTrue(bridge.close(first))
		bridge.read(bridge.open(), added(3, "serial"), added(2, ""))
		assertSame(known, bridge.byIndex(3))
		val replacement = assertNotNull(bridge.byIndex(2))
		assertNotSame(anonymous, replacement)
		assertEquals(listOf(known, anonymous, replacement), bridge.registered)
	}

	@Test fun onlyTheCurrentTransportCanEstablishTheHmdRegistration() {
		val bridge = Capture()
		val first = bridge.open()
		bridge.enqueue(first, added(0, "old-hmd"))
		assertTrue(bridge.close(first))
		val second = bridge.open()
		bridge.read(second, added(0, "current-hmd"), status(0, WireTrackerStatus.Status.OK), pose(0, 2f))
		val hmd = bridge.registered.single()
		assertEquals("current-hmd", hmd.name)
		assertSame(hmd, bridge.byIndex(0))
		assertEquals(second.epoch, bridge.acceptedCurrentSessionHmdPoseMessageSample()?.transportSessionEpoch)

		bridge.read(first, pose(0, 1f))
		assertEquals(first.epoch, bridge.acceptedHmdPoseMessageSample()?.transportSessionEpoch)
		assertEquals(second.epoch, bridge.acceptedCurrentSessionHmdPoseMessageSample()?.transportSessionEpoch)
	}

	private fun added(index: Int, serial: String) = ProtobufMessage.newBuilder()
		.setTrackerAdded(TrackerAdded.newBuilder().setTrackerId(index).setTrackerSerial(serial)).build()

	private fun status(index: Int, value: WireTrackerStatus.Status) = ProtobufMessage.newBuilder()
		.setTrackerStatus(WireTrackerStatus.newBuilder().setTrackerId(index).setStatus(value)).build()

	private fun pose(index: Int, x: Float) = ProtobufMessage.newBuilder().setPosition(
		Position.newBuilder().setTrackerId(index).setX(x).setY(2f).setZ(3f).setQw(1f).setDataSource(Position.DataSource.FULL),
	).build()

	private fun battery(index: Int, level: Float) = ProtobufMessage.newBuilder()
		.setBattery(Battery.newBuilder().setTrackerId(index).setBatteryLevel(level)).build()

	private class Capture : ProtobufBridge("controller-reconnect-test") {
		override val sessionScopedRemoteTrackers = true
		val registered = mutableListOf<Tracker>()

		fun open() = openInboundTransportSession()
		fun close(session: TransportSessionHandle) = closeInboundTransportSession(session)
		fun byIndex(index: Int) = getInternalRemoteTrackerById(index)
		fun enqueue(session: TransportSessionHandle?, message: ProtobufMessage) =
			messageReceived(ProtobufMessage.parseFrom(message.toByteArray()), session)

		fun read(session: TransportSessionHandle?, vararg messages: ProtobufMessage) {
			messages.forEach { enqueue(session, it) }
			dataRead()
		}

		override fun createNewTracker(trackerAdded: TrackerAdded) = Tracker(
			Device(DeviceOrigin.STEAMVR), registered.size + 1, trackerAdded.trackerSerial,
			trackerPosition = null, trackerNum = trackerAdded.trackerId, hasPosition = true, hasRotation = true,
			isHmd = trackerAdded.trackerId == 0,
		).also(::registerTrustedSteamVrHmd)

		override fun registerRemoteTracker(tracker: Tracker) { registered += tracker }
		override fun batteryReceived(batteryMessage: Battery) {
			byIndex(batteryMessage.trackerId)?.batteryLevel = batteryMessage.batteryLevel
		}
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?) = true
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = currentInboundTransportSession() != null
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "controller-reconnect-test"
	}
}
