package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.TrackingModality
import dev.monaka.tracking.desktop.RawHmdPoseInputRejectionReason.*
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

/** SYNTHETIC TEST EVIDENCE at the internal atomic-message boundary. No provider transport exists. */
class StrongTrustedHmdPolicyTests {
	private val policy = RawHmdPoseFreshnessPolicy(500)
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 17)
	private val mapping = RawHmdAppliedMapping(space, "synthetic-output", "synthetic-calibration", 23, false)

	private class ReceiptClock(var now: Long = 1_000_000L)
	private inner class Fixture(private val receiptClock: ReceiptClock = ReceiptClock()) :
		ProtobufBridge("synthetic-policy-test", { receiptClock.now }) {
		var now: Long
			get() = receiptClock.now
			set(value) { receiptClock.now = value }
		val hmd = Tracker(Device(DeviceOrigin.STEAMVR), 901, "external-hmd", trackerPosition = TrackerPosition.HEAD,
			trackerNum = 0, hasPosition = true, hasRotation = true, isHmd = true, isComputed = true,
			allowVelocity = true, trackRotDirection = false).also { it.status = TrackerStatus.OK }
		val raw = ProtobufBridge::class.java.getDeclaredField("rawHmdPositions").let {
			it.isAccessible = true; it.get(this) as TrustedRawHmdPositionSource
		}
		val full = Position.newBuilder().setTrackerId(0).setX(1f).setY(2f).setZ(3f).setQw(1f)
			.setVx(.1f).setVy(.2f).setVz(.3f).setDataSource(Position.DataSource.FULL).build()
		var handle: TransportSessionHandle
		val context: RawHmdProviderSession
		init {
			val field = ProtobufBridge::class.java.getDeclaredField("remoteTrackersByTrackerId").apply { isAccessible = true }
			@Suppress("UNCHECKED_CAST")
			(field.get(this) as MutableMap<Int, Tracker>)[0] = hmd
			registerTrustedSteamVrHmd(hmd); handle = openInboundTransportSession()
			receive(full)
			context = RawHmdProviderSession(ReviewedHmdBackendContract("SYNTHETIC TEST EVIDENCE",
				acceptedHmdPoseMessageSample()!!.ingressIdentity.sourceId, "synthetic-owner", space,
				"synthetic-reviewed-acquisition-contract"), "provider-a", "raw-incarnation-a", "generation-a", mapping)
			assertTrue(raw.establishProviderSession(context, policy))
			seed()
		}
		fun evidence(id: Long = 1, session: RawHmdProviderSession = context) = RawHmdProviderPoseEvidence(
			session.backend.sourceIdentity, session.providerSessionEpoch, id, session.backend.rawSpaceOwner,
			session.rawSpaceIncarnation, session.rawSpaceGeneration, session.appliedMapping, true)
		fun seed(id: Long = 1, message: Position = full, proof: RawHmdProviderPoseEvidence? = evidence(id),
			transport: TransportSessionHandle = handle, current: Boolean = true) {
			// Exact same immutable decoded message supplies P/Q and its explicit synthetic provider proof.
			raw.positionMessageAccepted(hmd, Vector3(message.x, message.y, message.z), message,
				if (message.dataSource == Position.DataSource.FULL) TrackingModality.FULL else TrackingModality.ROTATION_ONLY,
				transport.epoch, current, now, proof)
		}
		fun pose() = raw.currentTrustedPoseSnapshot(handle.epoch)
		fun capability() = deriveRawHmdPoseInputCapability(pose(), raw.currentProviderSession(), policy)
		fun admission(capability: RawHmdPoseInputCapability = capability()) = admitCurrentRawHmdPoseInput(capability)
		fun receive(message: Position, transport: TransportSessionHandle? = handle) {
			messageReceived(ProtobufMessages.ProtobufMessage.newBuilder().setPosition(message).build(), transport); dataRead()
		}
		fun status(status: TrackerStatus, transport: TransportSessionHandle? = handle) {
			messageReceived(ProtobufMessages.ProtobufMessage.newBuilder().setTrackerStatus(
				ProtobufMessages.TrackerStatus.newBuilder().setTrackerId(0).setStatusValue(status.id)).build(), transport); dataRead()
		}
		fun rollover(): TransportSessionHandle { handle = openInboundTransportSession(); return handle }
		fun close(old: TransportSessionHandle = handle) = closeInboundTransportSession(old)
		fun reconnect() = reconnected()
		fun disconnect() = disconnected()
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessages.ProtobufMessage?) = true
		override fun createNewTracker(trackerAdded: ProtobufMessages.TrackerAdded): Tracker = error("Fixture registry")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = false
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "synthetic-policy-test"
	}

	private fun rejected(reason: RawHmdPoseInputRejectionReason, result: RawHmdPoseInputAdmission) =
		assertTrue(reason in assertIs<RawHmdPoseInputAdmission.Rejected>(result).reasons)

	@Test fun completeSyntheticContractAcceptsAndPollingDoesNotRenewReceipt() {
		val f = Fixture(); val pose = assertNotNull(f.pose()); val ready = f.capability()
		repeat(3) { assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission(ready)); assertSame(pose, f.pose()) }
		for (age in listOf(499L, 500L)) {
			f.now = pose.receivedAtSystemNanos + age; assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission(ready))
		}
		f.now++; rejected(SAMPLE_STALE, f.admission(ready))
	}

	@Test fun eachMissingProviderFactProducesUnavailableWithoutHostSubstitutions() {
		val f = Fixture(); val pose = f.pose()!!; val e = pose.providerEvidence!!
		val cases = listOf(
			e.copy(providerSessionEpoch = null) to PROVIDER_SESSION_UNAVAILABLE,
			e.copy(providerSessionEpoch = " ") to PROVIDER_SESSION_UNAVAILABLE,
			e.copy(observationId = null) to OBSERVATION_ID_UNAVAILABLE,
			e.copy(rawSpaceOwner = null) to RAW_SPACE_GENERATION_UNAVAILABLE,
			e.copy(rawSpaceIncarnation = "") to RAW_SPACE_GENERATION_UNAVAILABLE,
			e.copy(rawSpaceGeneration = null) to RAW_SPACE_GENERATION_UNAVAILABLE,
			e.copy(appliedMapping = null) to FRAME_SPACE_UNAVAILABLE,
			e.copy(sourceValid = null) to PROVIDER_EVIDENCE_INVALID,
			e.copy(sourceValid = false) to PROVIDER_EVIDENCE_INVALID)
		for ((proof, reason) in cases) {
			val bad = pose.copy(providerEvidence = proof)
			assertTrue(reason in assertIs<RawHmdPoseInputCapability.Unavailable>(
				deriveRawHmdPoseInputCapability(bad, f.context, policy)).reasons)
			rejected(reason, admitRawHmdPoseInput(f.capability(), { f.handle }, { bad }, { f.now }, { f.context }))
		}
		assertTrue(pose.sequence >= 0); assertNotNull(pose.transportSessionEpoch); assertEquals(23, mapping.revision)
	}

	@Test fun providerEvidenceCannotBeAddedToReadyForAProductionOpenVrSample() {
		val f = Fixture(); val old = f.capability(); f.receive(f.full)
		assertNull(f.acceptedHmdPoseMessageSample()!!.providerEvidence); assertNull(f.pose())
		rejected(POSITION_UNAVAILABLE, f.admission(old))
		val production = assertIs<RawHmdPoseInputCapability.Unavailable>(f.rawHmdPoseInputCapability())
		assertEquals(setOf(FRAME_REFERENCE_UNAVAILABLE, PROVIDER_SESSION_UNAVAILABLE,
			OBSERVATION_ID_UNAVAILABLE, RAW_SPACE_GENERATION_UNAVAILABLE), production.reasons)
		assertEquals(Vector3(1f, 2f, 3f), f.hmd.position); assertEquals(Quaternion.IDENTITY, f.hmd.getRawRotation())
		assertEquals(Vector3(.1f, .2f, .3f), f.hmd.getVelocity()); assertEquals(TrackerStatus.OK, f.hmd.status)
		rejected(CAPABILITY_UNAVAILABLE, f.currentRawHmdPoseAdmission())
	}

	@Test fun providerMismatchAndEveryRawOrOutputMismatchRejectIndependently() {
		val f = Fixture(); val pose = f.pose()!!; val e = pose.providerEvidence!!
		for (proof in listOf(e.copy(providerSessionEpoch = "provider-b"), e.copy(rawSpaceOwner = "another-owner"),
			e.copy(rawSpaceIncarnation = "another-incarnation"), e.copy(rawSpaceGeneration = "another-generation"),
			e.copy(appliedMapping = mapping.copy(outputSpaceEpoch = "another-output")),
			e.copy(appliedMapping = mapping.copy(revision = 24)), e.copy(observationId = 2))) {
			val bad = pose.copy(providerEvidence = proof)
			rejected(PROVIDER_EVIDENCE_INVALID, admitRawHmdPoseInput(f.capability(), { f.handle }, { bad }, { f.now }, { f.context }))
		}
	}

	@Test fun unreviewedContractAndWrongSourceDoNotGrantAuthority() {
		val f = Fixture(); val pose = f.pose()!!
		for (context in listOf(null, f.context.copy(backend = f.context.backend.copy(contractId = "")),
			f.context.copy(backend = f.context.backend.copy(proofSource = ""))))
			assertTrue(FRAME_REFERENCE_UNAVAILABLE in assertIs<RawHmdPoseInputCapability.Unavailable>(
				deriveRawHmdPoseInputCapability(pose, context, policy)).reasons)
		val bad = pose.copy(providerEvidence = pose.providerEvidence!!.copy(sourceIdentity = "output-source"))
		assertTrue(SOURCE_IDENTITY_MISMATCH in assertIs<RawHmdPoseInputCapability.Unavailable>(
			deriveRawHmdPoseInputCapability(bad, f.context, policy)).reasons)
	}

	@Test fun missingInvalidOrUnresolvedMappingCannotBeRetaggedByReceiver() {
		val f = Fixture(); val pose = f.pose()!!
		for (bad in listOf(mapping.copy(outputSpaceEpoch = ""), mapping.copy(calibrationEpoch = " "),
			mapping.copy(outputSpace = space.copy(id = "")), mapping.copy(outputSpace = space.copy(convention = "")),
			mapping.copy(outputSpace = space.copy(revision = -1)), mapping.copy(revision = null), mapping.copy(revision = -1)))
			assertIs<RawHmdPoseInputCapability.Unavailable>(deriveRawHmdPoseInputCapability(
				pose.copy(providerEvidence = pose.providerEvidence!!.copy(appliedMapping = bad)), f.context, policy))
	}

	@Test fun exactPayloadCannotChangeUnderSameHostSequenceAndObservation() {
		val f = Fixture(); val ready = f.capability(); val pose = f.pose()!!
		for (bad in listOf(pose.copy(position = Vector3(4f, 5f, 6f)), pose.copy(orientation = Quaternion(2f, 0f, 0f, 0f))))
			rejected(PROVIDER_EVIDENCE_INVALID, admitRawHmdPoseInput(ready, { f.handle }, { bad }, { f.now }, { f.context }))
	}

	@Test fun fullToRotationOnlyClearsTrustedButKeepsLegacyRotationAndVelocity() {
		val f = Fixture(); val ready = f.capability(); val historical = f.acceptedHmdPoseMessageSample()
		f.receive(Position.newBuilder().setTrackerId(0).setQx(1f).setVx(4f).setVy(5f).setVz(6f)
			.setDataSource(Position.DataSource.IMU).build())
		assertNull(f.pose()); rejected(POSITION_UNAVAILABLE, f.admission(ready))
		assertSame(historical, f.acceptedHmdPoseMessageSample())
		assertEquals(Quaternion(0f, 1f, 0f, 0f), f.hmd.getRawRotation())
		assertEquals(Vector3(4f, 5f, 6f), f.hmd.getVelocity()); assertEquals(TrackingModality.ROTATION_ONLY, f.hmd.sampleModality)
	}

	@Test fun imuWithCompleteXyzAndValidProofStillInvalidates() {
		val f = Fixture(); f.seed(2, f.full.toBuilder().setDataSource(Position.DataSource.IMU).build())
		assertNull(f.pose()); assertIs<RawHmdPoseInputAdmission.Rejected>(f.admission())
	}

	@Test fun incompleteNonfiniteAndInvalidQuaternionNeverFallBackToLastGoodCandidate() {
		val messages = listOf(Position.newBuilder().setTrackerId(0).setX(1f).setQw(1f).setDataSource(Position.DataSource.FULL).build(),
			Fixture().full.toBuilder().setX(Float.NaN).build(), Fixture().full.toBuilder().setQw(0f).build(),
			Fixture().full.toBuilder().setQw(Float.POSITIVE_INFINITY).build())
		for (message in messages) {
			val f = Fixture(); val ready = f.capability(); f.seed(2, message)
			assertNull(f.pose()); rejected(POSITION_UNAVAILABLE, f.admission(ready))
			assertNotNull(f.acceptedHmdPoseMessageSample())
		}
	}

	@Test fun validityFalseAndMissingEvidenceClearImmediately() {
		for (proofMissing in listOf(false, true)) {
			val f = Fixture(); val ready = f.capability()
			f.seed(2, proof = if (proofMissing) null else f.evidence(2).copy(sourceValid = false))
			assertNull(f.pose()); rejected(POSITION_UNAVAILABLE, f.admission(ready))
		}
	}

	@Test fun everyUnusableTrackerStatusInvalidatesAndOkDoesNotRevive() {
		for (status in TrackerStatus.entries.filter { !it.sendData }) {
			val f = Fixture(); val old = f.capability(); f.status(status)
			assertEquals(status, f.hmd.status); assertNull(f.pose()); rejected(POSITION_UNAVAILABLE, f.admission(old))
			f.status(TrackerStatus.OK); assertNull(f.pose())
			f.seed(2); assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
		}
	}

	@Test fun busyPreservesCandidateAccordingToExistingSendDataSemantics() {
		val f = Fixture(); val pose = f.pose(); f.status(TrackerStatus.BUSY)
		assertSame(pose, f.pose()); assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
	}

	@Test fun transportRolloverRequiresFreshProviderSessionAndNeverReusesOldReady() {
		val f = Fixture(); val old = f.capability(); val a = f.handle; f.rollover()
		assertNull(f.pose()); assertNull(f.raw.currentProviderSession()); rejected(POSITION_UNAVAILABLE, f.admission(old))
		assertFalse(f.close(a))
		f.seed(2); assertNull(f.pose())
		assertFalse(f.raw.establishProviderSession(f.context, policy))
		val fresh = f.context.copy(providerSessionEpoch = "provider-b", rawSpaceIncarnation = "raw-incarnation-b")
		assertTrue(f.raw.establishProviderSession(fresh, policy)); f.seed(proof = f.evidence(session = fresh))
		assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
	}

	@Test fun closedSessionAndDisconnectCallbackClearAuthorityButKeepTrackerObjectAndHistory() {
		for (closeTransport in listOf(true, false)) {
			val f = Fixture(); val history = f.acceptedHmdPoseMessageSample(); val ready = f.capability()
			if (closeTransport) assertTrue(f.close()) else f.disconnect()
			assertNull(f.pose()); assertSame(history, f.acceptedHmdPoseMessageSample())
			assertIs<RawHmdPoseInputAdmission.Rejected>(f.admission(ready))
			if (!closeTransport) assertEquals(TrackerStatus.DISCONNECTED, f.hmd.status)
		}
	}

	@Test fun reconnectCallbackRetiresProviderAndOldEvidenceCannotResume() {
		val f = Fixture(); f.reconnect(); assertNull(f.pose()); assertNull(f.raw.currentProviderSession())
		assertFalse(f.raw.establishProviderSession(f.context, policy)); f.seed(2); assertNull(f.pose())
	}

	@Test fun lateOldTransportAndSessionlessValidityLossDoNotClearCurrentCandidate() {
		val f = Fixture(); val old = f.handle; f.rollover()
		val next = f.context.copy(providerSessionEpoch = "provider-b")
		assertTrue(f.raw.establishProviderSession(next, policy)); f.seed(proof = f.evidence(session = next))
		val pose = f.pose(); val imu = Position.newBuilder().setTrackerId(0).setQw(1f).setDataSource(Position.DataSource.IMU).build()
		f.receive(imu, old); f.receive(imu, null); f.status(TrackerStatus.DISCONNECTED, old)
		assertSame(pose, f.pose()); assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
	}

	@Test fun providerSessionSwitchWithinSameTransportRejectsOldScopeAndCannotReactivateIt() {
		val f = Fixture(); val old = f.capability(); val next = f.context.copy(providerSessionEpoch = "provider-b")
		assertTrue(f.raw.establishProviderSession(next, policy)); f.seed(proof = f.evidence(session = next))
		rejected(PROVIDER_EVIDENCE_INVALID, f.admission(old)); val pose = f.pose()
		f.seed(2); assertSame(pose, f.pose()); assertFalse(f.raw.establishProviderSession(f.context, policy))
	}

	@Test fun duplicateSamePayloadIgnoresNewHostSampleAndCannotRenewFirstReceipt() {
		val f = Fixture(); val old = f.pose()!!; f.now += 501; f.seed()
		assertSame(old, f.pose()); assertTrue(f.acceptedHmdPoseMessageSample()!!.sequence > old.sequence)
		rejected(SAMPLE_STALE, f.admission())
		val freshHostSample = f.acceptedHmdPoseMessageSample()!!
		rejected(SAMPLE_SEQUENCE_MISMATCH, f.admission(deriveRawHmdPoseInputCapability(freshHostSample, f.context, policy)))
	}

	@Test fun conflictingDuplicateRetiresScopeAndRequiresNewProviderSession() {
		val f = Fixture(); val ready = f.capability(); f.seed(message = f.full.toBuilder().setX(9f).build())
		assertNull(f.pose()); rejected(PROVIDER_EVIDENCE_INVALID, f.admission(ready))
		assertFalse(f.raw.establishProviderSession(f.context, policy)); f.seed(2); assertNull(f.pose())
	}

	@Test fun olderObservationDoesNotReplaceOrRenewCurrentCandidate() {
		val f = Fixture(); f.seed(5); val pose = f.pose(); f.now += 100; f.seed(1)
		assertSame(pose, f.pose()); assertEquals(5, f.pose()!!.providerEvidence!!.observationId)
	}

	@Test fun lossFollowedByDuplicateCannotReviveButNewObservationCan() {
		val f = Fixture(); f.raw.invalidateCurrentCandidate(f.hmd); f.seed(); assertNull(f.pose())
		f.seed(2); assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
	}

	@Test fun rawOrOutputContextChangeClearsCandidateAndRetainsObservationHighWater() {
		val f = Fixture(); f.seed(10)
		val changed = f.context.copy(rawSpaceGeneration = "generation-b", appliedMapping = mapping.copy(outputSpaceEpoch = "output-b"))
		assertTrue(f.raw.establishProviderSession(changed, policy)); assertNull(f.pose())
		f.seed(9, proof = f.evidence(9, changed)); assertNull(f.pose())
		f.seed(11, proof = f.evidence(11, changed)); assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
	}

	@Test fun counterExhaustionCannotWrapAndNewProviderSessionRestartsLocalCounter() {
		val f = Fixture(); f.seed(Long.MAX_VALUE); assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
		f.seed(Long.MIN_VALUE); assertNull(f.pose()); f.seed(0); assertNull(f.pose())
		val next = f.context.copy(providerSessionEpoch = "provider-b")
		assertTrue(f.raw.establishProviderSession(next, policy)); f.seed(0, proof = f.evidence(0, next))
		assertIs<RawHmdPoseInputAdmission.Accepted>(f.admission())
	}

	@Test fun providerOrCurrentSampleChangeDuringAdmissionFailsFinalStabilityCheck() {
		for (changeProvider in listOf(true, false)) {
			val f = Fixture(); val ready = f.capability()
			rejected(CURRENT_SESSION_CHANGED, admitRawHmdPoseInput(ready, { f.handle }, { f.pose() }, {
				if (changeProvider) f.raw.establishProviderSession(f.context.copy(providerSessionEpoch = "provider-b"), policy)
				else f.raw.invalidateCurrentCandidate(f.hmd)
				f.now
			}, f.raw::currentProviderSession))
		}
	}

	@Test fun invalidReceiptOrFreshnessPolicyCannotProduceReady() {
		val f = Fixture(); val pose = f.pose()!!
		assertIs<RawHmdPoseInputCapability.Unavailable>(deriveRawHmdPoseInputCapability(pose.copy(receivedAtSystemNanos = -1), f.context, policy))
		assertIs<RawHmdPoseInputCapability.Unavailable>(deriveRawHmdPoseInputCapability(pose, f.context, RawHmdPoseFreshnessPolicy(0)))
	}

	@Test fun retiredRawAndOutputEpochsCannotReturnAfterContextChange() {
		for (restoreRaw in listOf(true, false)) {
			val f = Fixture()
			val next = f.context.copy(rawSpaceGeneration = "generation-b",
				appliedMapping = mapping.copy(outputSpaceEpoch = "output-b"))
			assertTrue(f.raw.establishProviderSession(next, policy)); f.seed(2, proof = f.evidence(2, next))
			val aba = if (restoreRaw) next.copy(rawSpaceGeneration = f.context.rawSpaceGeneration)
				else next.copy(appliedMapping = mapping)
			assertFalse(f.raw.establishProviderSession(aba, policy)); assertNull(f.pose())
		}
	}

	@Test fun changedMappingCannotKeepOldOutputEpochEvenWithNewRevision() {
		val f = Fixture()
		assertFalse(f.raw.establishProviderSession(f.context.copy(appliedMapping = mapping.copy(revision = 24)), policy))
		assertNull(f.pose()); assertNull(f.raw.currentProviderSession())
	}

	@Test fun retiredSessionBoundExhaustionFailsClosedWithoutEvictingOldTokens() {
		val f = Fixture(); val state = HmdProviderObservationState()
		assertTrue(state.establish(f.context))
		for (i in 1..128) assertTrue(state.establish(f.context.copy(providerSessionEpoch = "synthetic-provider-$i")))
		assertFalse(state.establish(f.context.copy(providerSessionEpoch = "synthetic-provider-129")))
		assertNull(state.session); assertNull(state.candidate)
		assertFalse(state.establish(f.context.copy(providerSessionEpoch = "synthetic-provider-fresh")))
	}

	@Test fun completeEvidenceStillCannotAdmitFeedbackIdentity() {
		val f = Fixture(); val pose = f.pose()!!
		val output = pose.copy(ingressIdentity = pose.ingressIdentity.copy(sourceId = "monaka-direct:head"),
			providerEvidence = pose.providerEvidence!!.copy(sourceIdentity = "monaka-direct:head"))
		assertTrue(FEEDBACK_SOURCE_NOT_ALLOWED in assertIs<RawHmdPoseInputCapability.Unavailable>(
			deriveRawHmdPoseInputCapability(output, f.context, policy)).reasons)
		rejected(FEEDBACK_SOURCE_NOT_ALLOWED, admitRawHmdPoseInput(f.capability(), { f.handle }, { output }, { f.now }, { f.context }))
	}
}
