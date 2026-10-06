package dev.monaka.tracking.desktop

import dev.monaka.tracking.TrackingModality
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus as WireTrackerStatus
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class TrustedHmdPositionProvenanceTests {
	private fun tracker(name: String = "external-hmd", origin: DeviceOrigin? = DeviceOrigin.STEAMVR,
		remoteId: Int = 0, hmd: Boolean = true, internal: Boolean = false, computed: Boolean = true) = Tracker(
		origin?.let { Device(it) }, 900, name, trackerPosition = TrackerPosition.HEAD, trackerNum = remoteId,
		hasPosition = true, hasRotation = true, isHmd = hmd, isInternal = internal, isComputed = computed,
		allowVelocity = true, trackRotDirection = false,
	).also { it.status = dev.slimevr.tracking.trackers.TrackerStatus.OK }

	private fun position(id: Int = 0, xyz: Vector3? = Vector3(1f, 2f, 3f),
		rotation: Quaternion = Quaternion.IDENTITY, velocity: Vector3? = null,
		mode: Position.DataSource = Position.DataSource.FULL): Position = Position.newBuilder()
		.setTrackerId(id).setDataSource(mode).setQw(rotation.w).setQx(rotation.x).setQy(rotation.y).setQz(rotation.z)
		.apply {
			xyz?.let { setX(it.x); setY(it.y); setZ(it.z) }
			velocity?.let { setVx(it.x); setVy(it.y); setVz(it.z) }
		}.build()

	@Test fun acceptedPayloadsPreserveLocalIngressTimeAndIdenticalXyzStillAdvanceSequence() {
		var now = 100L
		val bridge = Capture { now }; val hmd = tracker(); bridge.install(hmd)
		assertNull(bridge.acceptedHmdPositionSample())
		bridge.enqueue(position()); now = 110; bridge.dataRead()
		val first = assertNotNull(bridge.acceptedHmdPositionSample())
		assertEquals(1, first.sequence); assertEquals(100, first.receivedAtSystemNanos)
		assertEquals(hmd.position, first.position)
		for ((index, receipt) in listOf(120L, 120L, 150L).withIndex()) {
			now = receipt; bridge.accept(position())
			val next = assertNotNull(bridge.acceptedHmdPositionSample())
			assertEquals(index + 2L, next.sequence)
			assertEquals(receipt, next.receivedAtSystemNanos)
			assertEquals(first.position, next.position)
			assertEquals(first.sourceEpoch, next.sourceEpoch)
		}
	}

	@Test fun rotationVelocityStatusBatteryTicksAndPollingCannotManufacturePositionSamples() {
		var now = 100L; val bridge = Capture { now }; val hmd = tracker(); bridge.install(hmd)
		bridge.accept(position()); val original = assertNotNull(bridge.acceptedHmdPositionSample())
		val originalPose = assertNotNull(bridge.acceptedHmdPoseMessageSample())
		now = 1_000_000
		bridge.accept(position(xyz = null, rotation = Quaternion(0f, 1f, 0f, 0f), mode = Position.DataSource.IMU))
		bridge.accept(position(xyz = null, velocity = Vector3(4f, 5f, 6f)))
		bridge.message(ProtobufMessage.newBuilder().setTrackerStatus(WireTrackerStatus.newBuilder()
			.setTrackerId(0).setStatus(WireTrackerStatus.Status.OK)).build())
		bridge.message(ProtobufMessage.newBuilder().setBattery(Battery.newBuilder().setTrackerId(0).setBatteryLevel(50f)).build())
		bridge.message(ProtobufMessage.getDefaultInstance())
		val hpm = HumanPoseManager(listOf(hmd))
		repeat(100) {
			hmd.dataTick(); hmd.heartbeat(); hmd.tick(.01f); hpm.update(); bridge.dataRead()
			assertSame(original, bridge.acceptedHmdPositionSample())
			assertSame(originalPose, bridge.acceptedHmdPoseMessageSample())
		}
		assertEquals(original.position, hmd.position)
		assertEquals(1, original.sequence); assertEquals(100, original.receivedAtSystemNanos)
	}

	@Test fun trustedExternalHmdMayBeComputedButHeadRoleAndOriginFlagsAloneAreInsufficient() {
		val bridge = Capture { 100 }; val external = tracker(); bridge.install(external); bridge.accept(position())
		val accepted = assertNotNull(bridge.acceptedHmdPositionSample())
		assertTrue(external.isComputed)
		assertTrue(accepted.ingressIdentity.isRawHmd())
		assertTrue(accepted.ingressIdentity.isComputed)
		assertEquals(accepted.ingressIdentity, bridge.acceptedHmdPoseMessageSample()!!.ingressIdentity)
		for (candidate in listOf(
			tracker(remoteId = 1, hmd = false), tracker(internal = true), tracker(origin = null),
			tracker(origin = DeviceOrigin.UDP), tracker(hmd = false),
			tracker(name = "monaka-direct:head"), tracker(name = "monaka-solver:head"),
			tracker(name = "monaka-private:head"), tracker(name = "human://Head"),
		)) {
			val rejected = Capture { 100 }; rejected.install(candidate); rejected.accept(position(id = candidate.trackerNum))
			assertNull(rejected.acceptedHmdPositionSample(), candidate.name)
			assertNull(rejected.acceptedHmdPoseMessageSample(), candidate.name)
		}
		val unregistered = Capture { 100 }; unregistered.install(tracker(), trustedCreation = false)
		unregistered.accept(position()); assertNull(unregistered.acceptedHmdPositionSample())
		assertNull(unregistered.acceptedHmdPoseMessageSample())
	}

	@Test fun objectLifetimeEpochChangesOnRecreationAndNotOnRepeatedRegistrationOrStatus() {
		val bridge = Capture { 100 }; val first = tracker(); bridge.install(first); bridge.accept(position())
		val previous = assertNotNull(bridge.acceptedHmdPositionSample())
		val previousPose = assertNotNull(bridge.acceptedHmdPoseMessageSample())
		bridge.install(first)
		bridge.connect(); bridge.disconnect()
		first.status = dev.slimevr.tracking.trackers.TrackerStatus.OK
		assertSame(previous, bridge.acceptedHmdPositionSample()) // Historical, not proof of current-session freshness.
		assertSame(previousPose, bridge.acceptedHmdPoseMessageSample())
		bridge.accept(position()); assertEquals(previous.sourceEpoch, bridge.acceptedHmdPositionSample()!!.sourceEpoch)
		assertEquals(previousPose.sourceEpoch, bridge.acceptedHmdPoseMessageSample()!!.sourceEpoch)
		val recreated = tracker(); bridge.install(recreated)
		assertNull(bridge.acceptedHmdPositionSample())
		assertNull(bridge.acceptedHmdPoseMessageSample())
		bridge.accept(position()); val next = assertNotNull(bridge.acceptedHmdPositionSample())
		assertNotEquals(previous.sourceEpoch, next.sourceEpoch); assertEquals(1, next.sequence)
		assertEquals(next.sourceEpoch, bridge.acceptedHmdPoseMessageSample()!!.sourceEpoch)
		assertEquals(1, bridge.acceptedHmdPoseMessageSample()!!.sequence)
	}

	@Test fun storedOrOverriddenPositionCannotBePairedWithOlderAcceptedMetadata() {
		val bridge = Capture { 100 }; val hmd = tracker(); bridge.install(hmd); bridge.accept(position())
		val captured = assertNotNull(bridge.acceptedHmdPositionSample())
		hmd.position = Vector3(99f, 88f, 77f) // Other writers remain ordinary storage writes.
		assertEquals(Vector3(1f, 2f, 3f), bridge.acceptedHmdPositionSample()!!.position)
		assertSame(captured, bridge.acceptedHmdPositionSample())
		bridge.accept(position(xyz = Vector3(7f, 8f, 9f)))
		val next = assertNotNull(bridge.acceptedHmdPositionSample())
		assertEquals(2, next.sequence); assertEquals(Vector3(7f, 8f, 9f), next.position)
		assertEquals(Vector3(1f, 2f, 3f), captured.position) // Immutable historical tuple remains coherent.
	}

	@Test fun legacyNumericRotationVelocityModalityAndStatusBehaviorIsPreserved() {
		val bridge = Capture { 100 }; val hmd = tracker(); bridge.install(hmd)
		val q = Quaternion(.5f, .5f, -.5f, .5f); val v = Vector3(.1f, .2f, .3f)
		bridge.accept(position(rotation = q, velocity = v))
		assertEquals(Vector3(1f, 2f, 3f), hmd.position); assertEquals(q, hmd.getRawRotation())
		assertEquals(v, hmd.getVelocity()); assertEquals(TrackingModality.FULL, hmd.sampleModality)
		assertEquals(dev.slimevr.tracking.trackers.TrackerStatus.OK, hmd.status)
		val orientationSequence = hmd.correctionOrientationSample()!!.sequence
		bridge.accept(Position.newBuilder().setTrackerId(0).setVx(8f).setVy(9f).setVz(10f).build())
		assertEquals(Vector3(1f, 2f, 3f), hmd.position)
		assertEquals(Quaternion(0f, 0f, 0f, 0f), hmd.getRawRotation()) // Existing protobuf defaults, not manufactured validity.
		assertEquals(Vector3(8f, 9f, 10f), hmd.getVelocity()); assertEquals(TrackingModality.NONE, hmd.sampleModality)
		assertEquals(orientationSequence + 1, hmd.correctionOrientationSample()!!.sequence)
		assertEquals(1, bridge.acceptedHmdPositionSample()!!.sequence)
		// Preserve the legacy hasX acceptance condition, including default missing Y/Z values.
		bridge.accept(Position.newBuilder().setTrackerId(0).setX(4f).setDataSource(Position.DataSource.IMU).build())
		assertEquals(Vector3(4f, 0f, 0f), hmd.position)
		assertEquals(2, bridge.acceptedHmdPositionSample()!!.sequence)
	}

	@Test fun acceptedReceiptProvenanceDoesNotCompletePhase2aSpaceFrameOrPosePairing() {
		val bridge = Capture { 100 }; bridge.install(tracker()); bridge.accept(position())
		val accepted = assertNotNull(bridge.acceptedHmdPositionSample())
		assertIs<RawHmdPoseInputCapability.Unavailable>(bridge.rawHmdPoseInputCapability())
		assertEquals(setOf("hmd_space_unverified", "hmd_frame_epoch_unavailable", "hmd_pose_pairing_unavailable"),
			accepted.rawPoseMetadataLimitations)
	}

	@Test fun wireHasNoAcquisitionTimestampSequenceOrFrameIdentitySoReceiptClockIsSeparate() {
		assertEquals(listOf("tracker_id", "x", "y", "z", "qx", "qy", "qz", "qw", "data_source", "vx", "vy", "vz"),
			Position.getDescriptor().fields.map { it.name })
	}

	@Test fun sameDecodedMessageCopiesShareAcceptanceMetadataButRemainRuntimeBlocked() {
		val bridge = Capture { 123 }; val hmd = tracker(); bridge.install(hmd)
		val q = Quaternion(.5f, .5f, -.5f, .5f)
		bridge.accept(position(rotation = q))
		val p = assertNotNull(bridge.acceptedHmdPositionSample())
		val pose = assertNotNull(bridge.acceptedHmdPoseMessageSample())
		assertEquals(p.position, pose.position); assertEquals(q, pose.orientation)
		assertEquals(p.sequence, pose.sequence); assertEquals(p.receivedAtSystemNanos, pose.receivedAtSystemNanos)
		assertEquals(p.sourceEpoch, pose.sourceEpoch); assertEquals(p.ingressIdentity, pose.ingressIdentity)
		assertEquals(PositionComponentPresence(true, true, true), pose.positionPresence)
		assertEquals(HmdPoseMessagePairingStatus.COMPLETE, pose.pairingStatus)
		assertTrue(pose.structuralRejectionReasons.isEmpty()); assertIs<RawHmdPoseInputCapability.Unavailable>(bridge.rawHmdPoseInputCapability())
		assertEquals(setOf("hmd_space_unverified", "hmd_frame_epoch_unavailable", "hmd_session_epoch_unavailable"),
			pose.rawPoseMetadataLimitations)
	}

	@Test fun messageCopyDoesNotFollowTrackerOrientationResetStorageOrNextMessage() {
		val bridge = Capture { 123 }; val hmd = tracker(); bridge.install(hmd)
		hmd.setRotation(Quaternion(0f, 1f, 0f, 0f))
		val q = Quaternion(.5f, -.5f, .5f, .5f)
		bridge.accept(position(rotation = q)); val old = bridge.acceptedHmdPoseMessageSample()!!
		hmd.position = Vector3(9f, 8f, 7f); hmd.setRotation(Quaternion.IDENTITY)
		assertEquals(q, old.orientation); assertEquals(Vector3(1f, 2f, 3f), old.position)
		assertSame(old, bridge.acceptedHmdPoseMessageSample())
		bridge.accept(position(xyz = Vector3(4f, 5f, 6f), rotation = Quaternion(0f, 0f, 1f, 0f)))
		val next = bridge.acceptedHmdPoseMessageSample()!!
		assertEquals(old.sequence + 1, next.sequence)
		assertEquals(Vector3(4f, 5f, 6f), next.position); assertEquals(Quaternion(0f, 0f, 1f, 0f), next.orientation)
		assertEquals(q, old.orientation); assertEquals(Vector3(1f, 2f, 3f), old.position)
	}

	@Test fun partialXyzAcceptancePreservesLegacyZerosWithoutClaimingCompletePosition() {
		val bridge = Capture { 123 }; val hmd = tracker(); bridge.install(hmd)
		for ((index, presence) in listOf(
			PositionComponentPresence(true, false, false), PositionComponentPresence(true, true, false),
			PositionComponentPresence(true, false, true), PositionComponentPresence(true, true, true),
		).withIndex()) {
			bridge.accept(Position.newBuilder().setX(1f).setQw(1f).apply {
				if (presence.y) setY(2f); if (presence.z) setZ(3f)
			}.build())
			val pose = bridge.acceptedHmdPoseMessageSample()!!
			assertEquals(index + 1L, pose.sequence); assertEquals(presence, pose.positionPresence)
			assertEquals(Vector3(1f, if (presence.y) 2f else 0f, if (presence.z) 3f else 0f), hmd.position)
			assertEquals(hmd.position, pose.position)
			assertEquals(if (presence.complete) HmdPoseMessagePairingStatus.COMPLETE else
				HmdPoseMessagePairingStatus.INCOMPLETE_POSITION, pose.pairingStatus)
		}
		val previous = bridge.acceptedHmdPoseMessageSample()
		bridge.accept(Position.newBuilder().setY(2f).setZ(3f).setQw(1f).build())
		assertSame(previous, bridge.acceptedHmdPoseMessageSample())
	}

	@Test fun invalidDecodedOrientationIsRecordedWithoutIdentitySubstitutionOrBlockingLegacyWrite() {
		val bridge = Capture { 123 }; val hmd = tracker(); bridge.install(hmd)
		for (q in listOf(Quaternion(0f, 0f, 0f, 0f), Quaternion(1e-6f, 0f, 0f, 0f),
			Quaternion(Float.NaN, 0f, 0f, 0f), Quaternion(1f, Float.POSITIVE_INFINITY, 0f, 0f),
			Quaternion(Float.MAX_VALUE, 0f, 0f, 0f))) {
			bridge.accept(position(rotation = q)); val pose = bridge.acceptedHmdPoseMessageSample()!!
			assertEquals(HmdPoseMessagePairingStatus.INVALID_ORIENTATION, pose.pairingStatus)
			assertEquals(q.w.toRawBits(), pose.orientation.w.toRawBits())
			assertEquals(q.x.toRawBits(), pose.orientation.x.toRawBits())
			assertEquals(q.w.toRawBits(), hmd.getRawRotation().w.toRawBits())
			assertEquals(pose.sequence, bridge.acceptedHmdPositionSample()!!.sequence)
			assertTrue("hmd_orientation_invalid" in pose.rawPoseMetadataLimitations)
		}
		// No Q fields produces decoded zero Q, never an inferred identity rotation.
		bridge.accept(Position.newBuilder().setX(1f).setY(2f).setZ(3f).build())
		assertEquals(HmdPoseMessagePairingStatus.INVALID_ORIENTATION, bridge.acceptedHmdPoseMessageSample()!!.pairingStatus)
		val nonNormalized = Quaternion(2f, 1f, 0f, 0f)
		bridge.accept(position(rotation = nonNormalized))
		assertEquals(HmdPoseMessagePairingStatus.COMPLETE, bridge.acceptedHmdPoseMessageSample()!!.pairingStatus)
		assertEquals(nonNormalized, bridge.acceptedHmdPoseMessageSample()!!.orientation)
	}

	@Test fun nonfinitePositionRemainsHistoricalAcceptanceAndFailsPairedCandidateValidation() {
		val bridge = Capture { 123 }; val hmd = tracker(); bridge.install(hmd)
		for (v in listOf(Vector3(Float.NaN, 2f, 3f), Vector3(1f, Float.POSITIVE_INFINITY, 3f),
			Vector3(1f, 2f, Float.NEGATIVE_INFINITY))) {
			bridge.accept(position(xyz = v)); val pose = bridge.acceptedHmdPoseMessageSample()!!
			assertEquals(HmdPoseMessagePairingStatus.INVALID_POSITION, pose.pairingStatus)
			assertTrue("hmd_position_nonfinite" in pose.structuralRejectionReasons)
			assertEquals(v.x.toRawBits(), hmd.position.x.toRawBits())
			assertEquals(v.y.toRawBits(), pose.position.y.toRawBits())
			assertEquals(v.z.toRawBits(), bridge.acceptedHmdPositionSample()!!.position.z.toRawBits())
		}
	}

	@Test fun dataSourcePresenceAndModalityAreSeparateFromStructuralCompleteness() {
		val bridge = Capture { 123 }; val hmd = tracker(); bridge.install(hmd)
		for ((value, mode) in listOf(3 to TrackingModality.FULL, 1 to TrackingModality.ROTATION_ONLY,
			0 to TrackingModality.NONE, 2 to TrackingModality.NONE, 99 to TrackingModality.NONE)) {
			bridge.accept(position().toBuilder().setDataSourceValue(value).build())
			val pose = bridge.acceptedHmdPoseMessageSample()!!
			assertEquals(value, pose.dataSourceValue); assertTrue(pose.dataSourcePresent)
			assertEquals(mode, pose.modality); assertEquals(mode, hmd.sampleModality)
			assertEquals(HmdPoseMessagePairingStatus.COMPLETE, pose.pairingStatus)
		}
		bridge.accept(position().toBuilder().clearDataSource().build())
		val pose = bridge.acceptedHmdPoseMessageSample()!!
		assertFalse(pose.dataSourcePresent); assertEquals(0, pose.dataSourceValue)
		assertEquals(TrackingModality.NONE, pose.modality)
		assertEquals(HmdPoseMessagePairingStatus.COMPLETE, pose.pairingStatus)
	}

	@Test fun ingressCapturesOneClockPerMessageAndKeepsPoseAndOrientationCountersIndependent() {
		var calls = 0; val bridge = Capture { (++calls).toLong() }; val hmd = tracker(); bridge.install(hmd)
		val before = hmd.correctionOrientationSample()?.sequence ?: 0L
		bridge.accept(position(xyz = null)) // Ingress clock runs once; no accepted position sample.
		assertEquals(1, calls); assertNull(bridge.acceptedHmdPoseMessageSample())
		repeat(3) { index ->
			bridge.accept(position()); val pose = bridge.acceptedHmdPoseMessageSample()!!
			assertEquals(index + 2, calls); assertEquals(index + 1L, pose.sequence)
			assertEquals(calls.toLong(), pose.receivedAtSystemNanos)
			assertEquals(pose.receivedAtSystemNanos, bridge.acceptedHmdPositionSample()!!.receivedAtSystemNanos)
			assertEquals(before + index + 2L, hmd.correctionOrientationSample()!!.sequence)
		}
	}

	@Test fun wirePresenceProvesXyzAndDataSourceButCannotProveExplicitQuaternionEncoding() {
		val fields = Position.getDescriptor().fields.associateBy { it.name }
		for (name in listOf("x", "y", "z", "data_source")) assertTrue(fields.getValue(name).hasPresence(), name)
		for (name in listOf("qx", "qy", "qz", "qw")) assertFalse(fields.getValue(name).hasPresence(), name)
		assertEquals(Quaternion(0f, 0f, 0f, 0f), Quaternion(Position.getDefaultInstance().qw,
			Position.getDefaultInstance().qx, Position.getDefaultInstance().qy, Position.getDefaultInstance().qz))
	}

	private class Capture(clock: () -> Long) : ProtobufBridge("trusted-hmd-test", clock) {
		// Test-only fixture population of the existing private remote registry; no production mutable seam.
		@Suppress("UNCHECKED_CAST")
		fun install(tracker: Tracker, trustedCreation: Boolean = true) {
			val field = ProtobufBridge::class.java.getDeclaredField("remoteTrackersByTrackerId").apply { isAccessible = true }
			(field.get(this) as MutableMap<Int, Tracker>)[tracker.trackerNum] = tracker
			if (trustedCreation) registerTrustedSteamVrHmd(tracker)
		}
		fun enqueue(value: Position) = messageReceived(ProtobufMessage.parseFrom(
			ProtobufMessage.newBuilder().setPosition(value).build().toByteArray()))
		fun accept(value: Position) { enqueue(value); dataRead() }
		fun message(value: ProtobufMessage) { messageReceived(value); dataRead() }
		fun connect() = reconnected()
		fun disconnect() = disconnected()
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?) = true
		override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("Fixture uses the existing registry")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = false
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "trusted-hmd-test"
	}
}
