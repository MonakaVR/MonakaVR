package dev.monaka.tracking.desktop

import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.trackers.*
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class HmdProviderEvidenceTransportTests {
	private fun position() = Position.newBuilder().setX(1f).setY(2f).setZ(3f).setQw(1f)
		.setDataSource(Position.DataSource.FULL).build()
	private fun evidence(p: Position = position(), id: Long = 17) = HmdProviderSampleEvidenceV1.newBuilder()
		.setProviderSessionEpoch("provider-session").setObservationId(id)
		.setRawX(-0f).setRawY(Float.fromBits(0x7fc12345)).setRawZ(Float.POSITIVE_INFINITY)
		.setRawQx(0f).setRawQy(0f).setRawQz(0f).setRawQw(1f)
		.setWireX(p.x).setWireY(p.y).setWireZ(p.z).setWireQx(p.qx).setWireQy(p.qy).setWireQz(p.qz).setWireQw(p.qw)
		.setDataSource(p.dataSourceValue).setPoseValid(false).setDeviceConnected(true).setTrackingResult(300).build()
	private fun full(id: Long = 17) = position().toBuilder().setHmdProviderEvidenceV1(evidence(id = id)).build()
	private fun action(name: String, token: String) = ProtobufMessage.newBuilder().setUserAction(UserAction.newBuilder()
		.setName(name).putActionArguments("connection", token).putActionArguments("backend", "unreviewed-remote-string")).build()
	private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }

	@Test fun currentTransportFreshChallengeAndServerOwnedContract() {
		val b = Capture(); val first = b.open(); val query = b.query()
		assertEquals("${ProtobufBridge.HMD_PROVIDER_CAPABILITY}?", query.name)
		val token = query.actionArgumentsMap.getValue("connection")
		assertTrue(token.isNotBlank()); assertNotEquals(first.epoch, token)
		b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, "wrong"), first)
		assertNull(b.currentHmdProviderTransportAssociation())
		b.message(action(ProtobufBridge.DIRECT_CAPABILITY, token), first)
		assertNull(b.currentHmdProviderTransportAssociation())
		b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, token), TransportSessionHandle(first.epoch))
		assertNull(b.currentHmdProviderTransportAssociation())
		b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, token), first)
		val association = assertNotNull(b.currentHmdProviderTransportAssociation())
		assertEquals(first.epoch, association.transportSessionEpoch)
		assertSame(REVIEWED_HMD_SAMPLE_TRANSPORT_V1, association.contract)
		b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, token), first)
		assertEquals(association, b.currentHmdProviderTransportAssociation())
		assertTrue(b.close(first)); assertNull(b.currentHmdProviderTransportAssociation())
		val second = b.open(); val next = b.query().actionArgumentsMap.getValue("connection")
		assertNotEquals(token, next)
		b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, token), second)
		b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, next), first)
		assertNull(b.currentHmdProviderTransportAssociation()); assertFalse(b.close(first))
		b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, next), second)
		assertNotNull(b.currentHmdProviderTransportAssociation())
	}

	@Test fun newNewPartialEvidenceHasServerIdentityAndNeverEnablesRawAdmission() {
		val b = Capture(); val s = b.open(); b.confirm(s)
		b.accept(full(Long.MAX_VALUE), s)
		val pose = assertNotNull(b.acceptedCurrentSessionHmdPoseMessageSample())
		val e = assertNotNull(pose.providerTransportEvidence)
		assertEquals(Long.MAX_VALUE, e.observationId); assertEquals("provider-session", e.providerSessionEpoch)
		assertNotEquals(s.epoch, e.providerSessionEpoch)
		assertEquals(Int.MIN_VALUE, e.rawPose.x.toRawBits()); assertEquals(0x7fc12345, e.rawPose.y.toRawBits())
		assertEquals(Float.POSITIVE_INFINITY, e.rawPose.z)
		assertFalse(e.poseValid); assertTrue(e.deviceConnected); assertEquals(300, e.trackingResult)
		val partial = assertNotNull(pose.providerEvidence)
		assertEquals(pose.ingressIdentity.sourceId, partial.sourceIdentity)
		assertEquals("steamvr:provider-transport-test:external-hmd", partial.sourceIdentity)
		assertEquals(e.partialProviderEvidence(pose.ingressIdentity.sourceId), partial)
		assertNull(partial.sourceValid); assertNull(partial.rawSpaceOwner); assertNull(partial.rawSpaceIncarnation)
		assertNull(partial.rawSpaceGeneration); assertNull(partial.appliedMapping)
		assertIs<RawHmdPoseInputCapability.Unavailable>(b.rawHmdPoseInputCapability())
		assertNull(b.providerSession()); assertNull(b.trustedCandidate(s))
		assertEquals(e, b.acceptedCurrentSessionHmdProviderTransportEvidence())
		b.close(s); assertNull(b.acceptedCurrentSessionHmdProviderTransportEvidence())
	}

	@Test fun missingEveryNestedFieldAndInvalidDomainsDiscardEvidenceOnly() {
		val b = Capture(); val s = b.open(); b.confirm(s)
		val mutations = HmdProviderSampleEvidenceV1.getDescriptor().fields.map { field ->
			full().toBuilder().setHmdProviderEvidenceV1(evidence().toBuilder().clearField(field)).build()
		} + listOf(
			full().toBuilder().setHmdProviderEvidenceV1(evidence().toBuilder().setProviderSessionEpoch(" ")).build(),
			full().toBuilder().setHmdProviderEvidenceV1(evidence(id = -1)).build(),
			full().toBuilder().setHmdProviderEvidenceV1(evidence().toBuilder().setDataSource(1)).build(),
			full().toBuilder().setHmdProviderEvidenceV1(evidence().toBuilder().setWireQw(-1f)).build(),
			full().toBuilder().clearY().build(), full().toBuilder().clearZ().build(),
			full().toBuilder().clearDataSource().build(), full().toBuilder().setDataSource(Position.DataSource.IMU).build(),
		)
		for (p in mutations) {
			val oldSeq = b.hmd.correctionOrientationSample()?.sequence ?: 0L
			b.accept(p, s)
			assertNull(b.acceptedHmdPoseMessageSample()!!.providerTransportEvidence)
			assertNull(b.acceptedHmdPoseMessageSample()!!.providerEvidence)
			assertEquals(p.x.toRawBits(), b.hmd.position.x.toRawBits())
			assertEquals(p.y.toRawBits(), b.hmd.position.y.toRawBits())
			assertEquals(p.z.toRawBits(), b.hmd.position.z.toRawBits())
			assertEquals(p.qw.toRawBits(), b.hmd.getRawRotation().w.toRawBits())
			assertEquals(oldSeq + 1, b.hmd.correctionOrientationSample()!!.sequence)
			assertEquals(TrackerStatus.OK, b.hmd.status)
		}
		b.accept(full().toBuilder().clearX().build(), s)
		assertNull(b.acceptedCurrentSessionHmdProviderTransportEvidence())
	}

	@Test fun signedZeroOneBitAndNanPayloadBindingUseRawBits() {
		for (bits in listOf(0, Int.MIN_VALUE, 1, 0x7f7fffff, 0x7fc12345, 0x7fc54321, 0x7f800000)) {
			val p = position().toBuilder().setX(Float.fromBits(bits)).setQx(Float.fromBits(bits)).build()
			val with = Position.parseFrom(p.toBuilder().setHmdProviderEvidenceV1(evidence(p)).build().toByteArray())
			val decoded = assertNotNull(decodeHmdProviderSampleTransport(with))
			assertEquals(bits, decoded.wirePose.x.toRawBits()); assertEquals(bits, decoded.wirePose.qx.toRawBits())
			assertNull(decodeHmdProviderSampleTransport(with.toBuilder().setHmdProviderEvidenceV1(
				with.hmdProviderEvidenceV1.toBuilder().setWireX(Float.fromBits(bits xor 1))).build()))
		}
		val p = position().toBuilder().setX(0f).build()
		assertNull(decodeHmdProviderSampleTransport(p.toBuilder().setHmdProviderEvidenceV1(evidence(p).toBuilder().setWireX(-0f)).build()))
	}

	@Test fun newServerOldDriverAndUnconfirmedOrForeignEvidenceKeepGenericUpdates() {
		val b = Capture(); val first = b.open()
		b.accept(position(), first); assertNull(b.currentHmdProviderTransportAssociation())
		val generic = b.acceptedHmdPoseMessageSample()!!
		b.accept(full(), first); assertNull(b.acceptedHmdPoseMessageSample()!!.providerEvidence)
		assertEquals(generic.position, b.hmd.position)
		b.confirm(first); val second = b.open(); b.confirm(second)
		b.accept(full(), first); assertNull(b.acceptedHmdPoseMessageSample()!!.providerTransportEvidence)
		assertNull(b.acceptedCurrentSessionHmdProviderTransportEvidence())
		b.accept(full(), second); assertNotNull(b.acceptedCurrentSessionHmdProviderTransportEvidence())
		b.accept(position().toBuilder().setVx(4f).setVy(5f).setVz(6f).build(), second)
		assertNull(b.acceptedCurrentSessionHmdProviderTransportEvidence()); assertEquals(4f, b.hmd.getVelocity().x)
		assertIs<RawHmdPoseInputCapability.Unavailable>(b.rawHmdPoseInputCapability())
	}

	@Test fun oldFieldTagsAndAbsentEvidenceGoldenBytesAreStable() {
		val names = listOf("tracker_id", "x", "y", "z", "qx", "qy", "qz", "qw", "data_source", "vx", "vy", "vz")
		assertEquals(names, Position.getDescriptor().fields.take(12).map { it.name })
		assertEquals((1..13).toList(), Position.getDescriptor().fields.map { it.number })
		assertEquals((1..6).toList(), ProtobufMessage.getDescriptor().oneofs.single().fields.map { it.number })
		assertEquals("0a16150000803f1d000000402500004040450000803f4803", hex(ProtobufMessage.newBuilder().setPosition(position()).build().toByteArray()))
		assertEquals("0a07450000803f4801", hex(ProtobufMessage.newBuilder().setPosition(Position.newBuilder().setQw(1f).setDataSource(Position.DataSource.IMU)).build().toByteArray()))
		assertEquals("0a180807150000803f1d000000402500004040450000803f4803", hex(ProtobufMessage.newBuilder().setPosition(position().toBuilder().setTrackerId(7)).build().toByteArray()))
		val evidenceFields = HmdProviderSampleEvidenceV1.getDescriptor().fields
		assertEquals(20, evidenceFields.size); assertTrue(evidenceFields.all { it.hasPresence() })
		assertTrue(evidenceFields.none { it.name.contains("space") || it.name.contains("mapping") || it.name.contains("time") || it.name.contains("source_identity") })
	}

	@Test fun directAndHmdChallengesCannotBeCrossUsedAndBothCanNegotiate() {
		val b = Capture()
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.slime("main"), outputMode = OutputMode.DIRECT)
		}
		DirectConstraintOutput(assignments.snapshot(), CoordinateSpace("test", "rh_y_up_neg_z_forward", 0)) { 123 }.use { output ->
			b.configureDirectOutputs(output.trackers.values.toList())
			val s = b.open(); val hmdToken = b.query().actionArgumentsMap.getValue("connection")
			b.reconnect(); b.flush()
			val directToken = b.sent.last { it.hasUserAction() && it.userAction.name == "${ProtobufBridge.DIRECT_CAPABILITY}?" }
				.userAction.actionArgumentsMap.getValue("connection")
			assertNotEquals(directToken, hmdToken)
			b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, directToken), s)
			assertNull(b.currentHmdProviderTransportAssociation())
			b.message(action(ProtobufBridge.DIRECT_CAPABILITY, hmdToken), s); assertFalse(b.directSupported())
			b.message(action(ProtobufBridge.HMD_PROVIDER_CAPABILITY, hmdToken), s)
			assertNotNull(b.currentHmdProviderTransportAssociation()); assertFalse(b.directSupported())
			b.message(action(ProtobufBridge.DIRECT_CAPABILITY, directToken), s); assertTrue(b.directSupported())
			b.accept(full(), s); assertNotNull(b.acceptedCurrentSessionHmdProviderTransportEvidence())
		}
	}

	@Test fun unregisteredHmdAndNonHmdNeverProjectProviderEvidence() {
		val b = Capture(trustedCreation = false); val s = b.open(); b.confirm(s)
		b.accept(full(), s); assertNull(b.acceptedHmdPoseMessageSample())
		assertEquals(1f, b.hmd.position.x)
		assertNull(decodeHmdProviderSampleTransport(full().toBuilder().setTrackerId(7).build()))
	}

	private class Capture(trustedCreation: Boolean = true) : ProtobufBridge("provider-transport-test", { 123L }) {
		val sent = mutableListOf<ProtobufMessage>()
		val hmd = Tracker(Device(DeviceOrigin.STEAMVR), 900, "external-hmd", trackerPosition = TrackerPosition.HEAD,
			trackerNum = 0, hasPosition = true, hasRotation = true, isHmd = true, isComputed = true,
			allowVelocity = true, trackRotDirection = false).also { it.status = TrackerStatus.OK }
		init {
			@Suppress("UNCHECKED_CAST")
			val map = ProtobufBridge::class.java.getDeclaredField("remoteTrackersByTrackerId").apply { isAccessible = true }
				.get(this) as MutableMap<Int, Tracker>
			map[0] = hmd; if (trustedCreation) registerTrustedSteamVrHmd(hmd)
		}
		fun open() = openInboundTransportSession()
		fun close(s: TransportSessionHandle) = closeInboundTransportSession(s)
		fun query(): UserAction { updateMessageQueue(); return sent.last { it.hasUserAction() && it.userAction.name == "${HMD_PROVIDER_CAPABILITY}?" }.userAction }
		fun confirm(s: TransportSessionHandle) { val q = query(); message(ProtobufMessage.newBuilder().setUserAction(q.toBuilder().setName(HMD_PROVIDER_CAPABILITY)).build(), s) }
		fun message(m: ProtobufMessage, s: TransportSessionHandle) { messageReceived(ProtobufMessage.parseFrom(m.toByteArray()), s); dataRead() }
		fun accept(p: Position, s: TransportSessionHandle) = message(ProtobufMessage.newBuilder().setPosition(p).build(), s)
		private fun source() = ProtobufBridge::class.java.getDeclaredField("rawHmdPositions").apply { isAccessible = true }.get(this) as TrustedRawHmdPositionSource
		fun providerSession() = source().currentProviderSession()
		fun trustedCandidate(s: TransportSessionHandle) = source().currentTrustedPoseSnapshot(s.epoch)
		fun reconnect() = reconnected()
		fun flush() = updateMessageQueue()
		fun directSupported() = ProtobufBridge::class.java.getDeclaredField("directOutputSupported").apply { isAccessible = true }.getBoolean(this)
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?): Boolean { sent += requireNotNull(message); return true }
		override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("Fixture registry")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = false
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "provider-transport-test"
	}
}
