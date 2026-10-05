package dev.monaka.tracking.desktop

import dev.monaka.tracking.*
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.trackers.*
import dev.slimevr.tracking.trackers.TrackerStatus as LocalStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class InboundTransportSessionTests {
	private fun pose(x: Float) = ProtobufMessage.newBuilder().setPosition(Position.newBuilder()
		.setTrackerId(0).setX(x).setY(2f).setZ(3f).setQw(1f).setDataSource(Position.DataSource.FULL)).build()

	@Test fun everyLogicalAcceptHasUniqueOpaqueHandleIndependentOfReceiptClockAndObjectReuse() {
		val bridge = Capture() // Receipt clock is always 100, including across logical accepts.
		val reusedConnection = Any()
		val accepts = mutableListOf<Pair<Any, TransportSessionHandle>>()
		repeat(32) {
			val handle = bridge.open(); accepts += reusedConnection to handle
			assertTrue(handle.epoch.isNotBlank()); assertSame(handle, bridge.active())
			assertTrue(bridge.close(handle)); assertNull(bridge.active())
		}
		assertEquals(1, accepts.map { it.first }.distinct().size)
		assertEquals(32, accepts.map { it.second.epoch }.distinct().size)
	}

	@Test fun matchingCloseClearsActiveButDelayedOldCloseCannotClearNewSession() {
		val bridge = Capture(); val a = bridge.open()
		assertTrue(bridge.close(a)); val b = bridge.open()
		assertNotEquals(a, b); assertFalse(bridge.close(a)); assertSame(b, bridge.active())
		assertTrue(bridge.close(b)); assertNull(bridge.active())
		assertFalse(bridge.close(b)) // Idempotent termination/error/finally-equivalent cleanup.
	}

	@Test fun queuedSessionAContextReachesPositionBoundaryAfterBBecomesActiveWithoutRetagOrDrop() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.enqueue(pose(2f), a)
		assertTrue(bridge.close(a)); val b = bridge.open(); bridge.enqueue(pose(3f), b)
		val initialRotationSequence = bridge.hmd.correctionOrientationSample()?.sequence ?: 0L
		bridge.dataRead()
		assertEquals(listOf(1f, 2f, 3f), bridge.processed.map { it.first.position.x })
		assertEquals(listOf(a, a, b), bridge.processed.map { it.second })
		assertEquals(listOf<TransportSessionHandle?>(a, a, b), bridge.positionContexts)
		assertEquals(listOf(Vector3(1f, 2f, 3f), Vector3(2f, 2f, 3f), Vector3(3f, 2f, 3f)), bridge.positionsAfterProcessing)
		assertSame(b, bridge.active()); assertEquals(Vector3(3f, 2f, 3f), bridge.hmd.position)
		assertEquals(initialRotationSequence + 3, bridge.hmd.correctionOrientationSample()!!.sequence)
		assertEquals(3, bridge.acceptedHmdPositionSample()!!.sequence)
	}

	@Test fun disconnectedBacklogStillProcessesLegacyNumericValuesWithHistoricalSessionContext() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(7f), a); assertTrue(bridge.close(a))
		bridge.dataRead()
		assertNull(bridge.active()); assertSame(a, bridge.positionContexts.single())
		assertEquals(Vector3(7f, 2f, 3f), bridge.hmd.position)
		assertEquals(Quaternion.IDENTITY, bridge.hmd.getRawRotation())
		assertEquals(1, bridge.acceptedHmdPoseMessageSample()!!.sequence)
	}

	@Test fun sessionlessOverloadDoesNotInferAnActiveSessionAndKeepsLegacyProcessing() {
		val bridge = Capture(); bridge.installHmd(); val active = bridge.open()
		bridge.enqueueWithoutSession(pose(5f)); bridge.dataRead()
		assertNull(bridge.processed.single().second); assertNull(bridge.positionContexts.single())
		assertSame(active, bridge.active()); assertEquals(Vector3(5f, 2f, 3f), bridge.hmd.position)
		assertEquals(TrackingModality.FULL, bridge.hmd.sampleModality)
		assertEquals(LocalStatus.OK, bridge.hmd.status)
	}

	@Test fun allMessageKindsRetainOneSessionWithoutImplicitOpenOrClose() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		val messages = listOf(
			pose(1f),
			ProtobufMessage.newBuilder().setTrackerAdded(TrackerAdded.newBuilder().setTrackerId(0)).build(),
			ProtobufMessage.newBuilder().setTrackerStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.newBuilder()
				.setTrackerId(0).setStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.Status.DISCONNECTED)).build(),
			ProtobufMessage.newBuilder().setBattery(Battery.newBuilder().setTrackerId(0).setBatteryLevel(50f)).build(),
			ProtobufMessage.newBuilder().setVersion(Version.newBuilder().setProtocolVersion(2)).build(),
			ProtobufMessage.newBuilder().setUserAction(UserAction.newBuilder().setName(ProtobufBridge.DIRECT_CAPABILITY)).build(),
		)
		messages.forEach { bridge.enqueue(it, a) }; bridge.dataRead()
		assertEquals(messages, bridge.processed.map { it.first })
		assertTrue(bridge.processed.all { it.second === a }); assertSame(a, bridge.active())
		bridge.hmd.dataTick(); bridge.hmd.heartbeat(); bridge.dataRead()
		assertSame(a, bridge.active()); assertEquals(messages.size, bridge.processed.size)
	}

	@Test fun directCapabilityNegotiationRemainsSeparateFromInboundSessionIdentity() {
		val bridge = Capture()
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.slime("main"), outputMode = OutputMode.DIRECT)
		}
		DirectConstraintOutput(assignments.snapshot(), CoordinateSpace("test", "rh_y_up_neg_z_forward", 0)) { 123 }.use { output ->
			bridge.configureDirectOutputs(output.trackers.values.toList())
			val a = bridge.open(); bridge.reconnectCallback(); bridge.flush()
			val queryA = bridge.sent.single { it.hasUserAction() }.userAction
			assertSame(a, bridge.active()); assertNotEquals(a.epoch, queryA.actionArgumentsMap["connection"])
			bridge.enqueue(ProtobufMessage.newBuilder().setUserAction(queryA.toBuilder().setName(ProtobufBridge.DIRECT_CAPABILITY)).build(), a)
			bridge.dataRead(); assertSame(a, bridge.active())
			bridge.disconnectCallback(); assertSame(a, bridge.active()) // Output lifecycle is not transport close.
			assertTrue(bridge.close(a)); val b = bridge.open()
			bridge.sent.clear(); bridge.reconnectCallback(); bridge.flush()
			val queryB = bridge.sent.single { it.hasUserAction() }.userAction
			assertNotEquals(queryA.actionArgumentsMap, queryB.actionArgumentsMap)
			assertSame(b, bridge.active()); assertNotEquals(b.epoch, queryB.actionArgumentsMap["connection"])
		}
	}

	@Test fun hmdRecordsDoNotConsumeSessionInfrastructureOrRemoveSessionUnavailableReason() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.dataRead()
		val first = bridge.acceptedHmdPoseMessageSample()!!
		assertTrue(bridge.close(a)); val b = bridge.open()
		bridge.enqueue(pose(2f), b); bridge.dataRead()
		val next = bridge.acceptedHmdPoseMessageSample()!!
		assertNotEquals(a.epoch, b.epoch); assertEquals(first.sourceEpoch, next.sourceEpoch)
		assertEquals(first.ingressIdentity, next.ingressIdentity)
		assertEquals(100, next.receivedAtSystemNanos); assertEquals(first.sequence + 1, next.sequence)
		assertFalse(next.rawPoseInputEligible)
		assertTrue("hmd_session_epoch_unavailable" in next.rawPoseInputRejectionReasons)
	}

	private class Capture : ProtobufBridge("session-test", { 100 }) {
		val processed = mutableListOf<Pair<ProtobufMessage, TransportSessionHandle?>>()
		val positionContexts = mutableListOf<TransportSessionHandle?>()
		val positionsAfterProcessing = mutableListOf<Vector3>()
		val sent = mutableListOf<ProtobufMessage>()
		val hmd = Tracker(Device(DeviceOrigin.STEAMVR), 900, "external-hmd", trackerPosition = TrackerPosition.HEAD,
			trackerNum = 0, hasPosition = true, hasRotation = true, isHmd = true, isComputed = true,
			trackRotDirection = false).also { it.status = LocalStatus.OK }
		@Suppress("UNCHECKED_CAST")
		fun installHmd() {
			val field = ProtobufBridge::class.java.getDeclaredField("remoteTrackersByTrackerId").apply { isAccessible = true }
			(field.get(this) as MutableMap<Int, Tracker>)[0] = hmd
			registerTrustedSteamVrHmd(hmd)
		}
		fun open() = openInboundTransportSession()
		fun close(handle: TransportSessionHandle) = closeInboundTransportSession(handle)
		fun active() = currentInboundTransportSession()
		fun enqueue(message: ProtobufMessage, handle: TransportSessionHandle) = messageReceived(ProtobufMessage.parseFrom(message.toByteArray()), handle)
		fun enqueueWithoutSession(message: ProtobufMessage) = messageReceived(ProtobufMessage.parseFrom(message.toByteArray()))
		fun reconnectCallback() = reconnected()
		fun disconnectCallback() = disconnected()
		fun flush() = updateMessageQueue()
		override fun processMessageReceived(message: ProtobufMessage?, transportSession: TransportSessionHandle?) {
			processed += requireNotNull(message) to transportSession
			super.processMessageReceived(message, transportSession)
		}
		override fun positionReceived(positionMessage: Position, transportSession: TransportSessionHandle?) {
			positionContexts += transportSession
			super.positionReceived(positionMessage, transportSession)
			positionsAfterProcessing += hmd.position
		}
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?): Boolean { sent += requireNotNull(message); return true }
		override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("Existing registry fixture")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = false
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "session-test"
	}
}
