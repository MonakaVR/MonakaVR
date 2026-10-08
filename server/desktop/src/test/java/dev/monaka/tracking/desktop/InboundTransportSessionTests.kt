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
			val queryA = bridge.sent.single { it.hasUserAction() && it.userAction.name == "${ProtobufBridge.DIRECT_CAPABILITY}?" }.userAction
			assertSame(a, bridge.active()); assertNotEquals(a.epoch, queryA.actionArgumentsMap["connection"])
			bridge.enqueue(ProtobufMessage.newBuilder().setUserAction(queryA.toBuilder().setName(ProtobufBridge.DIRECT_CAPABILITY)).build(), a)
			bridge.dataRead(); assertSame(a, bridge.active())
			bridge.disconnectCallback(); assertSame(a, bridge.active()) // Output lifecycle is not transport close.
			assertTrue(bridge.close(a)); val b = bridge.open()
			bridge.sent.clear(); bridge.reconnectCallback(); bridge.flush()
			val queryB = bridge.sent.single { it.hasUserAction() && it.userAction.name == "${ProtobufBridge.DIRECT_CAPABILITY}?" }.userAction
			assertNotEquals(queryA.actionArgumentsMap, queryB.actionArgumentsMap)
			assertSame(b, bridge.active()); assertNotEquals(b.epoch, queryB.actionArgumentsMap["connection"])
		}
	}

	@Test fun hmdRecordsCarrySeparateObjectAndTransportLifetimesAcrossSameObjectReconnect() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.dataRead()
		val first = bridge.acceptedHmdPoseMessageSample()!!
		assertEquals(a.epoch, first.transportSessionEpoch)
		assertSame(first, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertTrue(bridge.close(a)); val b = bridge.open()
		bridge.enqueue(pose(2f), b); bridge.dataRead()
		val next = bridge.acceptedHmdPoseMessageSample()!!
		assertNotEquals(a.epoch, b.epoch); assertEquals(first.sourceEpoch, next.sourceEpoch)
		assertEquals(first.ingressIdentity, next.ingressIdentity)
		assertEquals(100, next.receivedAtSystemNanos); assertEquals(first.sequence + 1, next.sequence)
		assertIs<RawHmdPoseInputCapability.Unavailable>(bridge.rawHmdPoseInputCapability())
		assertEquals(b.epoch, next.transportSessionEpoch)
		assertSame(next, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertFalse("hmd_session_epoch_unavailable" in next.rawPoseMetadataLimitations)
		assertEquals(setOf("hmd_space_unverified", "hmd_frame_epoch_unavailable"), next.rawPoseMetadataLimitations)
	}

	@Test fun oneAcceptanceSharesAllMetadataAcrossHistoricalAndCurrentPositionAndPose() {
		var clockCalls = 0
		val bridge = Capture { clockCalls++; 123L }; bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(4f), a); bridge.dataRead()
		val position = bridge.acceptedHmdPositionSample()!!
		val paired = bridge.acceptedHmdPoseMessageSample()!!
		assertEquals(1, clockCalls)
		assertEquals(position.sequence, paired.sequence)
		assertEquals(position.receivedAtSystemNanos, paired.receivedAtSystemNanos)
		assertEquals(position.sourceEpoch, paired.sourceEpoch)
		assertEquals(position.ingressIdentity, paired.ingressIdentity)
		assertEquals(a.epoch, position.transportSessionEpoch)
		assertEquals(position.transportSessionEpoch, paired.transportSessionEpoch)
		assertSame(position, bridge.acceptedCurrentSessionHmdPositionSample())
		assertSame(paired, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertEquals(HmdPoseMessagePairingStatus.COMPLETE, paired.pairingStatus)
		assertTrue("hmd_pose_pairing_unavailable" in position.rawPoseMetadataLimitations)
	}

	@Test fun disconnectAndReconnectBeforeFirstPosePreserveHistoryButExposeNoCurrentCandidate() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.dataRead()
		val original = bridge.acceptedHmdPoseMessageSample()!!
		assertTrue(bridge.close(a))
		assertSame(original, bridge.acceptedHmdPoseMessageSample())
		assertNull(bridge.acceptedCurrentSessionHmdPositionSample())
		assertNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		bridge.open()
		assertSame(original, bridge.acceptedHmdPoseMessageSample())
		assertNull(bridge.acceptedCurrentSessionHmdPositionSample())
		assertNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertEquals(a.epoch, original.transportSessionEpoch)
	}

	@Test fun delayedAAndSessionlessAcceptanceCannotOverwriteCurrentBDespiteLegacyNumericUpdates() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		// Hold the decoded A envelope at the position boundary; production FIFO is not reordered.
		val delayedA = pose(1f).position
		bridge.close(a); val b = bridge.open()
		bridge.enqueue(pose(2f), b); bridge.dataRead()
		val bPosition = bridge.acceptedCurrentSessionHmdPositionSample()!!
		val bPose = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		val rotationSequence = bridge.hmd.correctionOrientationSample()!!.sequence
		bridge.acceptAtPositionBoundary(delayedA, a)
		assertEquals(Vector3(1f, 2f, 3f), bridge.hmd.position)
		assertEquals(a.epoch, bridge.acceptedHmdPoseMessageSample()!!.transportSessionEpoch)
		assertSame(bPosition, bridge.acceptedCurrentSessionHmdPositionSample())
		assertSame(bPose, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		bridge.enqueueWithoutSession(pose(3f)); bridge.dataRead()
		assertEquals(Vector3(3f, 2f, 3f), bridge.hmd.position)
		val sessionless = bridge.acceptedHmdPoseMessageSample()!!
		assertNull(sessionless.transportSessionEpoch)
		assertTrue("hmd_session_epoch_unavailable" in sessionless.rawPoseMetadataLimitations)
		assertEquals(bPose.sequence + 2, sessionless.sequence)
		assertEquals(rotationSequence + 2, bridge.hmd.correctionOrientationSample()!!.sequence)
		bridge.enqueue(ProtobufMessage.newBuilder().setBattery(Battery.newBuilder().setTrackerId(0).setBatteryLevel(50f)).build(), b)
		bridge.enqueue(ProtobufMessage.newBuilder().setTrackerStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.newBuilder()
			.setTrackerId(0).setStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.Status.DISCONNECTED)).build(), b)
		bridge.dataRead(); bridge.hmd.dataTick(); bridge.hmd.heartbeat(); bridge.dataRead()
		assertFalse(bridge.close(a)); assertSame(b, bridge.active())
		repeat(20) {
			assertSame(bPosition, bridge.acceptedCurrentSessionHmdPositionSample())
			assertSame(bPose, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		}
		bridge.enqueue(pose(4f), b); bridge.dataRead()
		val next = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		assertNotSame(bPose, next); assertEquals(b.epoch, next.transportSessionEpoch)
		assertEquals(sessionless.sequence + 1, next.sequence)
	}

	@Test fun staleAndSessionlessBacklogBeforeFirstBMessageNeverManufactureCurrentB() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.close(a); val b = bridge.open(); bridge.dataRead()
		assertEquals(a.epoch, bridge.acceptedHmdPoseMessageSample()!!.transportSessionEpoch)
		assertNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertFalse("hmd_session_epoch_unavailable" in bridge.acceptedHmdPoseMessageSample()!!.rawPoseMetadataLimitations)
		bridge.enqueueWithoutSession(pose(2f)); bridge.dataRead()
		assertNull(bridge.acceptedHmdPositionSample()!!.transportSessionEpoch)
		assertNull(bridge.acceptedCurrentSessionHmdPositionSample())
		assertNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		bridge.enqueue(pose(3f), b); bridge.dataRead()
		assertEquals(b.epoch, bridge.acceptedCurrentSessionHmdPoseMessageSample()!!.transportSessionEpoch)
	}

	@Test fun disconnectedBacklogKeepsKnownHistoricalLineageWithoutCurrentCandidate() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(9f), a); bridge.close(a); bridge.dataRead()
		assertEquals(a.epoch, bridge.acceptedHmdPositionSample()!!.transportSessionEpoch)
		assertEquals(a.epoch, bridge.acceptedHmdPoseMessageSample()!!.transportSessionEpoch)
		assertNull(bridge.acceptedCurrentSessionHmdPositionSample())
		assertNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertEquals(Vector3(9f, 2f, 3f), bridge.hmd.position)
	}

	@Test fun sessionLineageDoesNotPromotePartialOrInvalidMessageStructure() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		val cases = listOf(
			Position.newBuilder().setTrackerId(0).setX(1f).setQw(1f).build() to "hmd_position_components_incomplete",
			pose(Float.NaN).position to "hmd_position_nonfinite",
			pose(Float.POSITIVE_INFINITY).position to "hmd_position_nonfinite",
			pose(1f).position.toBuilder().setQw(0f).build() to "hmd_orientation_invalid",
			pose(1f).position.toBuilder().setQw(Float.NaN).build() to "hmd_orientation_invalid",
		)
		for ((message, reason) in cases) {
			bridge.acceptAtPositionBoundary(message, a)
			val sample = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
			assertEquals(a.epoch, sample.transportSessionEpoch)
			assertTrue(reason in sample.structuralRejectionReasons)
			assertNotEquals(HmdPoseMessagePairingStatus.COMPLETE, sample.pairingStatus)
			assertFalse("hmd_session_epoch_unavailable" in sample.rawPoseMetadataLimitations)
			assertIs<RawHmdPoseInputCapability.Unavailable>(bridge.rawHmdPoseInputCapability())
			assertEquals(message.x.toRawBits(), bridge.hmd.position.x.toRawBits())
		}
	}

	@Test fun noXUpdatesLegacyRotationAndVelocityButNotAnyHmdRecordOrReceiptClock() {
		var clockCalls = 0
		val bridge = Capture { clockCalls++; 100L }; bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.dataRead()
		val current = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		val orientationSequence = bridge.hmd.correctionOrientationSample()!!.sequence
		val rotationOnly = Position.newBuilder().setTrackerId(0).setQw(0.5f).setQx(0.5f)
			.setVx(4f).setVy(5f).setVz(6f).setDataSource(Position.DataSource.IMU).build()
		bridge.acceptAtPositionBoundary(rotationOnly, a)
		assertSame(current, bridge.acceptedHmdPoseMessageSample())
		assertSame(current, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertEquals(1, clockCalls)
		assertEquals(orientationSequence + 1, bridge.hmd.correctionOrientationSample()!!.sequence)
		assertEquals(Quaternion(0.5f, 0.5f, 0f, 0f), bridge.hmd.getRawRotation())
		assertEquals(Vector3(4f, 5f, 6f), bridge.hmd.getVelocity())
		assertEquals(TrackingModality.ROTATION_ONLY, bridge.hmd.sampleModality)
	}

	@Test fun trackerRecreationClearsBothSlotsButDoesNotReplaceTransportIdentity() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.dataRead()
		val old = bridge.acceptedHmdPoseMessageSample()!!
		bridge.replaceHmd()
		assertNull(bridge.acceptedHmdPositionSample()); assertNull(bridge.acceptedHmdPoseMessageSample())
		assertNull(bridge.acceptedCurrentSessionHmdPositionSample()); assertNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertSame(a, bridge.active())
		bridge.enqueue(pose(2f), a); bridge.dataRead()
		val next = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		assertNotEquals(old.sourceEpoch, next.sourceEpoch); assertEquals(a.epoch, next.transportSessionEpoch)
		assertEquals(1, next.sequence)
		bridge.registerAgain(bridge.hmd)
		assertSame(next, bridge.acceptedCurrentSessionHmdPoseMessageSample())
	}

	@Test fun transportLineageCannotBypassRegistrationOrAcceptAnObsoleteTrackerObject() {
		val oldTracker = Capture().hmd
		val newTracker = Capture().hmd
		val source = TrustedRawHmdPositionSource("test")
		fun accept(tracker: Tracker) = source.positionMessageAccepted(
			tracker, Vector3(1f, 2f, 3f), pose(1f).position, TrackingModality.FULL, "session", true, 100L,
		)
		accept(oldTracker) // Unregistered lookalike, despite transport proof.
		assertNull(source.snapshot()); assertNull(source.currentPositionSnapshot("session"))
		source.registerFromSteamVrIngress(oldTracker); accept(oldTracker)
		val old = source.snapshot()!!
		source.registerFromSteamVrIngress(newTracker); accept(oldTracker)
		assertNull(source.snapshot()); assertNull(source.currentPositionSnapshot("session"))
		accept(newTracker)
		assertNotEquals(old.sourceEpoch, source.snapshot()!!.sourceEpoch)
		assertEquals(1, source.snapshot()!!.sequence)
		assertEquals("session", source.snapshot()!!.transportSessionEpoch)
	}

	@Test fun directOutputLifecycleDoesNotChangeCurrentHmdSessionOrAcceptance() {
		val bridge = Capture(); bridge.installHmd(); val a = bridge.open()
		bridge.enqueue(pose(1f), a); bridge.dataRead()
		val current = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		bridge.reconnectCallback(); bridge.disconnectCallback()
		assertSame(a, bridge.active()); assertSame(current, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertEquals(a.epoch, current.transportSessionEpoch)
	}

	@Test fun blankSessionMetadataIsRejectedWithoutThrowingIntoLegacyIngress() {
		val tracker = Capture().hmd
		val source = TrustedRawHmdPositionSource("test")
		source.registerFromSteamVrIngress(tracker)
		source.positionMessageAccepted(tracker, Vector3(0f, 0f, 0f), pose(0f).position, TrackingModality.FULL, " ", true, 100L)
		assertNull(source.snapshot()); assertNull(source.currentPoseMessageSnapshot(" "))
		source.positionMessageAccepted(tracker, Vector3(0f, 0f, 0f), pose(0f).position, TrackingModality.FULL, "valid", true, 100L)
		val position = source.snapshot()!!; val paired = source.poseMessageSnapshot()!!
		assertFailsWith<IllegalArgumentException> { position.copy(transportSessionEpoch = "") }
		assertFailsWith<IllegalArgumentException> { paired.copy(transportSessionEpoch = "\t") }
	}

	@Test fun knownCurrentSessionDoesNotAuthorizeDerivedOrNonSteamVrHmdSources() {
		fun candidate(name: String = "head", origin: DeviceOrigin = DeviceOrigin.STEAMVR,
			remoteId: Int = 0, internal: Boolean = false, isHmd: Boolean = true) = Tracker(
			Device(origin), 901, name, trackerPosition = TrackerPosition.HEAD, trackerNum = remoteId,
			hasPosition = true, hasRotation = true, isHmd = isHmd, isInternal = internal,
			isComputed = true, trackRotDirection = false,
		)
		for (tracker in listOf(
			candidate(remoteId = 1, isHmd = false), candidate(internal = true), candidate(origin = DeviceOrigin.UDP),
			candidate(isHmd = false), candidate(name = "monaka-direct:head"), candidate(name = "monaka-solver:head"),
			candidate(name = "monaka-private:head"), candidate(name = "human://head"),
		)) {
			val source = TrustedRawHmdPositionSource("test")
			source.registerFromSteamVrIngress(tracker)
			source.positionMessageAccepted(tracker, Vector3(1f, 2f, 3f), pose(1f).position, TrackingModality.FULL, "session", true, 100L)
			assertNull(source.snapshot(), tracker.name)
			assertNull(source.poseMessageSnapshot(), tracker.name)
			assertNull(source.currentPositionSnapshot("session"), tracker.name)
			assertNull(source.currentPoseMessageSnapshot("session"), tracker.name)
		}
	}

	private class Capture(receiptClock: () -> Long = { 100 }) : ProtobufBridge("session-test", receiptClock) {
		val processed = mutableListOf<Pair<ProtobufMessage, TransportSessionHandle?>>()
		val positionContexts = mutableListOf<TransportSessionHandle?>()
		val positionsAfterProcessing = mutableListOf<Vector3>()
		val sent = mutableListOf<ProtobufMessage>()
		private fun newHmd() = Tracker(Device(DeviceOrigin.STEAMVR), 900, "external-hmd", trackerPosition = TrackerPosition.HEAD,
			trackerNum = 0, hasPosition = true, hasRotation = true, isHmd = true, isComputed = true,
			allowVelocity = true, trackRotDirection = false).also { it.status = LocalStatus.OK }
		var hmd = newHmd()
		fun replaceHmd() { hmd = newHmd(); installHmd() }
		fun registerAgain(tracker: Tracker) = registerTrustedSteamVrHmd(tracker)
		fun acceptAtPositionBoundary(message: Position, handle: TransportSessionHandle?) = positionReceived(message, handle, 100L)
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
		override fun processMessageReceived(message: ProtobufMessage?, transportSession: TransportSessionHandle?, receivedAtSystemNanos: Long?) {
			processed += requireNotNull(message) to transportSession
			super.processMessageReceived(message, transportSession, receivedAtSystemNanos)
		}
		override fun positionReceived(positionMessage: Position, transportSession: TransportSessionHandle?, receivedAtSystemNanos: Long?) {
			positionContexts += transportSession
			super.positionReceived(positionMessage, transportSession, receivedAtSystemNanos)
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
