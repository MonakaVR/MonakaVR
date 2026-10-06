package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.*
import dev.monaka.tracking.desktop.RawHmdPoseInputRejectionReason.*
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class RawHmdPoseAdmissionTests {
	private val session = TransportSessionHandle("session-a")
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 17)
	private val source = RawSourceIdentity("synthetic-ingress-hmd", RawSourceKind.RAW_HMD,
		isComputed = true, isHmd = true)
	private fun sample() = HmdAcceptedPoseMessageSample(Vector3(1f, 2f, 3f),
		PositionComponentPresence(true, true, true), Quaternion(.5f, .5f, -.5f, .5f),
		7, 1_000_000, "source-a", source, Position.DataSource.FULL.number, true,
		TrackingModality.FULL, session.epoch)

	/** Test-only evidence, never a runtime flag or an inference from session/source identity. */
	private fun ready(pose: HmdAcceptedPoseMessageSample = sample()) = RawHmdPoseInputCapability.Ready(
		pose.ingressIdentity.sourceId, pose.sourceEpoch, requireNotNull(pose.transportSessionEpoch),
		pose.sequence, space, "synthetic-frame-a", 23, RawHmdPoseFreshnessPolicy(500),
		RawHmdFrameProofKind.EXPLICIT_POSE_BOUND_FRAME_PROOF, "unit-test-pose-bound-proof",
	)
	private fun admit(pose: HmdAcceptedPoseMessageSample? = sample(),
		capability: RawHmdPoseInputCapability = ready(), now: Long = 1_000_100,
		active: TransportSessionHandle? = session) = admitRawHmdPoseInput(capability, { active }, { pose }, { now })
	private fun rejects(reason: RawHmdPoseInputRejectionReason, result: RawHmdPoseInputAdmission) {
		assertTrue(reason in assertIs<RawHmdPoseInputAdmission.Rejected>(result).reasons)
	}

	@Test fun completeFullPoseWithExplicitSampleBoundProofMapsEveryAcceptedField() {
		val pose = sample()
		val input = assertIs<RawHmdPoseInputAdmission.Accepted>(admit(pose)).input
		assertEquals(pose.ingressIdentity, input.source)
		assertEquals(pose.position, input.position); assertEquals(pose.orientation, input.orientation)
		assertEquals(space, input.space)
		assertEquals(ObservationSampleProvenance(pose.sequence, pose.receivedAtSystemNanos,
			pose.sourceEpoch, "synthetic-frame-a", 23, space), input.provenance)
	}

	@Test fun unavailableCapabilityHasExplicitImmutableReasonsAndCannotAdmit() {
		val mutable = mutableSetOf(FRAME_REFERENCE_UNAVAILABLE)
		val capability = RawHmdPoseInputCapability.Unavailable(mutable)
		mutable.clear()
		val result = assertIs<RawHmdPoseInputAdmission.Rejected>(admit(capability = capability))
		assertEquals(setOf(CAPABILITY_UNAVAILABLE, FRAME_REFERENCE_UNAVAILABLE), result.reasons)
		assertEquals(setOf(FRAME_REFERENCE_UNAVAILABLE), capability.reasons)
		assertFailsWith<UnsupportedOperationException> { (result.reasons as MutableSet).clear() }
		assertFailsWith<UnsupportedOperationException> { (capability.reasons as MutableSet).clear() }
	}

	@Test fun missingProofKindAndMissingProofSourceFailClosed() {
		rejects(FRAME_REFERENCE_UNAVAILABLE, admit(capability = ready().copy(
			proofKind = RawHmdFrameProofKind.NO_AUTHORITATIVE_FRAME_PROOF)))
		for (proofSource in listOf(null, "", " "))
			rejects(FRAME_REFERENCE_UNAVAILABLE, admit(capability = ready().copy(proofSource = proofSource)))
	}

	@Test fun sourceSessionSequenceAndSpaceAloneDoNotEstablishAFrame() {
		val evidence = ready().copy(proofKind = RawHmdFrameProofKind.NO_AUTHORITATIVE_FRAME_PROOF)
		rejects(FRAME_REFERENCE_UNAVAILABLE, admit(capability = evidence))
	}

	@Test fun missingOrBlankFrameEpochRejectsWithoutSubstitution() {
		for (epoch in listOf(null, "", "\t"))
			rejects(FRAME_EPOCH_UNAVAILABLE, admit(capability = ready().copy(frameCalibrationEpoch = epoch)))
	}

	@Test fun coordinateSpaceMustBeExplicitAndItsDescriptorValid() {
		rejects(FRAME_SPACE_UNAVAILABLE, admit(capability = ready().copy(space = null)))
		for (invalid in listOf(space.copy(id = ""), space.copy(convention = " "), space.copy(revision = -1)))
			rejects(FRAME_SPACE_INVALID, admit(capability = ready().copy(space = invalid)))
	}

	@Test fun coordinateSpaceIdConventionAndRevisionArePreservedExactly() {
		val exact = space.copy(id = "another-explicit-frame", revision = 99)
		val input = assertIs<RawHmdPoseInputAdmission.Accepted>(admit(capability = ready().copy(space = exact))).input
		assertEquals(exact, input.space); assertEquals(exact, input.provenance.space)
	}

	@Test fun sourceIdentityMustMatchEvidence() {
		rejects(SOURCE_IDENTITY_MISMATCH, admit(capability = ready().copy(expectedSourceId = "another-source")))
	}

	@Test fun sourceEpochMustMatchEvidence() {
		rejects(SOURCE_EPOCH_MISMATCH, admit(capability = ready().copy(expectedSourceEpoch = "other-object")))
	}

	@Test fun capabilitySessionMustMatchSampleAndActiveHandle() {
		rejects(SESSION_EPOCH_MISMATCH, admit(capability = ready().copy(expectedTransportSessionEpoch = "b")))
		rejects(SESSION_EPOCH_UNAVAILABLE, admit(capability = ready().copy(expectedTransportSessionEpoch = "")))
	}

	@Test fun sessionlessSampleCannotInheritAnActiveSession() {
		val pose = sample().copy(transportSessionEpoch = null)
		rejects(SESSION_EPOCH_UNAVAILABLE, admit(pose))
		assertNull(pose.transportSessionEpoch)
	}

	@Test fun sampleSessionMustMatchActiveSessionEvenWhenCapabilityMatchesSample() {
		rejects(SESSION_EPOCH_MISMATCH, admit(active = TransportSessionHandle("b")))
		rejects(SESSION_EPOCH_MISMATCH, admit(sample().copy(transportSessionEpoch = "b")))
	}

	@Test fun missingActiveSessionAndMissingCurrentSampleRejectNormally() {
		rejects(SESSION_EPOCH_UNAVAILABLE, admit(active = null))
		rejects(POSITION_UNAVAILABLE, admit(pose = null))
	}

	@Test fun finalCheckUsesHandleObjectIdentityRatherThanEpochStringEquality() {
		var reads = 0
		val result = admitRawHmdPoseInput(ready(),
			{ if (++reads == 1) session else TransportSessionHandle(session.epoch) }, { sample() }, { 1_000_100 })
		rejects(CURRENT_SESSION_CHANGED, result)
	}

	@Test fun capabilityForAnotherSequenceCannotAuthorizeIdenticalNumbers() {
		val next = sample().copy(sequence = sample().sequence + 1)
		assertEquals(sample().position, next.position); assertEquals(sample().orientation, next.orientation)
		rejects(SAMPLE_SEQUENCE_MISMATCH, admit(next))
		assertIs<RawHmdPoseInputAdmission.Accepted>(admit(next, ready(next)))
		rejects(SAMPLE_SEQUENCE_MISMATCH, admit(capability = ready().copy(expectedSampleSequence = 6)))
	}

	@Test fun fullRequiredEvenWhenImuContainsCompleteFiniteXyzAndValidQ() {
		val imu = sample().copy(modality = TrackingModality.ROTATION_ONLY, dataSourceValue = Position.DataSource.IMU.number)
		assertEquals(HmdPoseMessagePairingStatus.COMPLETE, imu.pairingStatus)
		rejects(POSITION_MODALITY_NOT_FULL, admit(imu))
	}

	@Test fun noneAndInconsistentModalityCannotAdmitPosition() {
		rejects(POSITION_MODALITY_NOT_FULL, admit(sample().copy(modality = TrackingModality.NONE)))
		rejects(POSITION_MODALITY_NOT_FULL, admit(sample().copy(dataSourceValue = Position.DataSource.IMU.number)))
		for (value in listOf(0, 2)) rejects(POSITION_MODALITY_NOT_FULL,
			admit(sample().copy(dataSourceValue = value, modality = TrackingModality.NONE)))
	}

	@Test fun missingAndUnknownDataSourceFailClosedIndependentlyOfNumericCompleteness() {
		rejects(DATA_SOURCE_UNAVAILABLE, admit(sample().copy(dataSourcePresent = false)))
		rejects(DATA_SOURCE_UNSUPPORTED, admit(sample().copy(dataSourceValue = 99)))
	}

	@Test fun everyIncompleteXyzPresenceRejectsWithoutFillingComponents() {
		for (presence in listOf(PositionComponentPresence(false, true, true),
			PositionComponentPresence(true, false, true), PositionComponentPresence(true, true, false)))
			rejects(POSITION_COMPONENTS_INCOMPLETE, admit(sample().copy(positionPresence = presence)))
	}

	@Test fun everyNonfinitePositionComponentRejects() {
		for (position in listOf(Vector3(Float.NaN, 2f, 3f), Vector3(1f, Float.POSITIVE_INFINITY, 3f),
			Vector3(1f, 2f, Float.NEGATIVE_INFINITY)))
			rejects(POSITION_NONFINITE, admit(sample().copy(position = position)))
	}

	@Test fun zeroTinyNonfiniteAndOverflowedQuaternionRejectWithoutIdentitySubstitution() {
		for (q in listOf(Quaternion(0f, 0f, 0f, 0f), Quaternion(1e-6f, 0f, 0f, 0f),
			Quaternion(Float.NaN, 0f, 0f, 0f), Quaternion(1f, Float.POSITIVE_INFINITY, 0f, 0f),
			Quaternion(Float.MAX_VALUE, 0f, 0f, 0f))) {
			val pose = sample().copy(orientation = q)
			rejects(ORIENTATION_INVALID, admit(pose))
			assertEquals(q.w.toRawBits(), pose.orientation.w.toRawBits())
		}
	}

	@Test fun structurallyValidNonnormalizedQuaternionIsCopiedExactly() {
		val pose = sample().copy(orientation = Quaternion(2f, 1f, 0f, 0f))
		assertEquals(pose.orientation, assertIs<RawHmdPoseInputAdmission.Accepted>(admit(pose)).input.orientation)
	}

	@Test fun outputInternalAndNonRawHmdIdentitiesCannotAdmit() {
		for (identity in listOf(source.copy(sourceId = "monaka-direct:head"), source.copy(sourceId = "monaka-solver:head"),
			source.copy(sourceId = "monaka-private:head"), source.copy(sourceId = "human://head"),
			source.copy(isInternal = true), source.copy(kind = RawSourceKind.DERIVED_OUTPUT), source.copy(isHmd = false))) {
			val pose = sample().copy(ingressIdentity = identity)
			rejects(FEEDBACK_SOURCE_NOT_ALLOWED, admit(pose, ready(pose)))
		}
	}

	@Test fun exactReceiptFreshnessBoundaryAcceptsAndOneNanosecondLaterRejects() {
		assertIs<RawHmdPoseInputAdmission.Accepted>(admit(now = 1_000_500))
		rejects(SAMPLE_STALE, admit(now = 1_000_501))
	}

	@Test fun futureReceiptTimeHasSeparateReason() {
		val result = admit(now = 999_999)
		rejects(FUTURE_RECEIPT_TIME, result)
		assertFalse(SAMPLE_STALE in assertIs<RawHmdPoseInputAdmission.Rejected>(result).reasons)
	}

	@Test fun freshnessPolicyMustBePositive() {
		for (limit in listOf(0L, -1L, Long.MIN_VALUE)) rejects(FRESHNESS_POLICY_INVALID,
			admit(capability = ready().copy(freshnessPolicy = RawHmdPoseFreshnessPolicy(limit))))
	}

	@Test fun longRangeReceiptComparisonCannotOverflowIntoFalseFreshness() {
		val pose = sample().copy(receivedAtSystemNanos = 0)
		assertIs<RawHmdPoseInputAdmission.Accepted>(admit(pose,
			ready(pose).copy(freshnessPolicy = RawHmdPoseFreshnessPolicy(Long.MAX_VALUE)), Long.MAX_VALUE))
		rejects(SAMPLE_STALE, admit(pose, ready(pose).copy(freshnessPolicy = RawHmdPoseFreshnessPolicy(Long.MAX_VALUE - 1)), Long.MAX_VALUE))
		rejects(FUTURE_RECEIPT_TIME, admit(pose.copy(receivedAtSystemNanos = Long.MAX_VALUE), now = 0))
		rejects(RECEIPT_TIME_INVALID, admit(pose.copy(receivedAtSystemNanos = Long.MIN_VALUE)))
		rejects(RECEIPT_TIME_INVALID, admit(now = -1))
	}

	@Test fun invalidAcceptedSequenceOrSourceEpochRejectsWithoutConstructorException() {
		rejects(SAMPLE_PROVENANCE_INVALID, admit(sample().copy(sequence = -1)))
		rejects(SAMPLE_PROVENANCE_INVALID, admit(sample().copy(sourceEpoch = "")))
	}

	@Test fun authoritativeMappingRevisionCopiesExactlyAndAbsentRemainsNull() {
		assertEquals(23, assertIs<RawHmdPoseInputAdmission.Accepted>(admit()).input.provenance.mappingRevision)
		assertNull(assertIs<RawHmdPoseInputAdmission.Accepted>(admit(capability = ready().copy(mappingRevision = null))).input.provenance.mappingRevision)
		rejects(MAPPING_REVISION_INVALID, admit(capability = ready().copy(mappingRevision = -1)))
	}

	@Test fun productionGenericSourceIsUnavailableEvenWithCurrentCompleteFullPose() {
		val bridge = Capture(); bridge.acceptFull()
		val capability = assertIs<RawHmdPoseInputCapability.Unavailable>(bridge.rawHmdPoseInputCapability())
		assertEquals(setOf(FRAME_REFERENCE_UNAVAILABLE), capability.reasons)
		val pose = assertNotNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertEquals(HmdPoseMessagePairingStatus.COMPLETE, pose.pairingStatus)
		assertEquals(TrackingModality.FULL, pose.modality)
		assertEquals(setOf(CAPABILITY_UNAVAILABLE, FRAME_REFERENCE_UNAVAILABLE),
			assertIs<RawHmdPoseInputAdmission.Rejected>(bridge.currentRawHmdPoseAdmission()).reasons)
	}

	@Test fun bridgeSuccessReadsCopiedSameMessagePoseEvenAfterTrackerStorageChanges() {
		val bridge = Capture(); bridge.acceptFull()
		val pose = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		bridge.hmd.position = Vector3(90f, 80f, 70f); bridge.hmd.setRotation(Quaternion(0f, 1f, 0f, 0f))
		val input = assertIs<RawHmdPoseInputAdmission.Accepted>(bridge.admitCurrentRawHmdPoseInput(ready(pose))).input
		assertEquals(pose.position, input.position); assertEquals(pose.orientation, input.orientation)
		assertEquals(Vector3(90f, 80f, 70f), bridge.hmd.position)
	}

	@Test fun sessionChangesDuringBridgeAdmissionRejectDeterministicallyWithoutSleep() {
		lateinit var bridge: Capture
		var changeSession = false
		bridge = Capture { if (changeSession) { changeSession = false; bridge.open() }; 1_000_000 }
		bridge.acceptFull()
		val evidence = ready(bridge.acceptedCurrentSessionHmdPoseMessageSample()!!)
		changeSession = true
		rejects(CURRENT_SESSION_CHANGED, bridge.admitCurrentRawHmdPoseInput(evidence))
	}

	@Test fun disconnectDuringBridgeAdmissionRejectsWithoutAReconnection() {
		lateinit var bridge: Capture
		var disconnect = false
		bridge = Capture { if (disconnect) { disconnect = false; bridge.close(bridge.active()!!) }; 1_000_000 }
		bridge.acceptFull(); val evidence = ready(bridge.acceptedCurrentSessionHmdPoseMessageSample()!!)
		disconnect = true
		rejects(CURRENT_SESSION_CHANGED, bridge.admitCurrentRawHmdPoseInput(evidence))
	}

	@Test fun trackerRecreationWithSameIdNameNumbersAndSessionInvalidatesOldEvidence() {
		val bridge = Capture(); bridge.acceptFull()
		val old = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		val evidence = ready(old); val active = bridge.active()
		bridge.replaceHmd(); bridge.acceptFull()
		val next = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		assertSame(active, bridge.active()); assertEquals(old.sequence, next.sequence)
		assertEquals(old.ingressIdentity, next.ingressIdentity); assertEquals(old.position, next.position)
		assertNotEquals(old.sourceEpoch, next.sourceEpoch)
		rejects(SOURCE_EPOCH_MISMATCH, bridge.admitCurrentRawHmdPoseInput(evidence))
	}

	@Test fun sameTrackerAcrossReconnectKeepsSourceButInvalidatesSessionEvidence() {
		val bridge = Capture(); bridge.acceptFull()
		val old = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		val b = bridge.open(); bridge.acceptFull(b)
		val next = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		assertEquals(old.sourceEpoch, next.sourceEpoch); assertNotEquals(old.transportSessionEpoch, next.transportSessionEpoch)
		rejects(SESSION_EPOCH_MISMATCH, bridge.admitCurrentRawHmdPoseInput(ready(old)))
		assertIs<RawHmdPoseInputAdmission.Accepted>(bridge.admitCurrentRawHmdPoseInput(ready(next)))
	}

	@Test fun frameProofIsBoundToOneSampleEvenWithoutSourceOrSessionChange() {
		val bridge = Capture(); bridge.acceptFull()
		val first = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		val frameA = ready(first)
		assertIs<RawHmdPoseInputAdmission.Accepted>(bridge.admitCurrentRawHmdPoseInput(frameA))
		bridge.acceptFull(); val next = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		assertEquals(first.sourceEpoch, next.sourceEpoch); assertEquals(first.transportSessionEpoch, next.transportSessionEpoch)
		assertEquals(first.position, next.position); assertEquals(first.orientation, next.orientation)
		rejects(SAMPLE_SEQUENCE_MISMATCH, bridge.admitCurrentRawHmdPoseInput(frameA))
		val frameB = ready(next).copy(frameCalibrationEpoch = "synthetic-frame-b", mappingRevision = null)
		val input = assertIs<RawHmdPoseInputAdmission.Accepted>(bridge.admitCurrentRawHmdPoseInput(frameB)).input
		assertEquals("synthetic-frame-b", input.provenance.calibrationEpoch)
		assertNull(input.provenance.mappingRevision)
	}

	@Test fun historicalAndSessionlessBacklogCannotSubstituteForCurrentCandidate() {
		val bridge = Capture(); val a = bridge.active()!!; bridge.acceptFull(a)
		val historical = bridge.acceptedHmdPoseMessageSample()!!
		val b = bridge.open()
		rejects(POSITION_UNAVAILABLE, bridge.admitCurrentRawHmdPoseInput(ready(historical)))
		bridge.enqueueFull(a); bridge.dataRead()
		assertEquals(a.epoch, bridge.acceptedHmdPoseMessageSample()!!.transportSessionEpoch)
		assertNull(bridge.acceptedCurrentSessionHmdPoseMessageSample())
		rejects(POSITION_UNAVAILABLE, bridge.admitCurrentRawHmdPoseInput(ready(historical)))
		bridge.acceptFull(b); val current = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!
		bridge.enqueueFull(a); bridge.dataRead(); bridge.enqueueFull(null); bridge.dataRead()
		assertNull(bridge.acceptedHmdPoseMessageSample()!!.transportSessionEpoch)
		assertSame(current, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		assertIs<RawHmdPoseInputAdmission.Accepted>(bridge.admitCurrentRawHmdPoseInput(ready(current)))
	}

	@Test fun pollingAdmissionTicksAndHeartbeatNeverRefreshSequenceOrReceipt() {
		var now = 1_000_000L
		val bridge = Capture { now }; bridge.acceptFull()
		val pose = bridge.acceptedCurrentSessionHmdPoseMessageSample()!!; val evidence = ready(pose)
		repeat(5) {
			bridge.hmd.dataTick(); bridge.hmd.heartbeat(); bridge.dataRead()
			assertIs<RawHmdPoseInputAdmission.Accepted>(bridge.admitCurrentRawHmdPoseInput(evidence))
			assertSame(pose, bridge.acceptedCurrentSessionHmdPoseMessageSample())
		}
		now += 501
		rejects(SAMPLE_STALE, bridge.admitCurrentRawHmdPoseInput(evidence))
		assertEquals(1, pose.sequence); assertEquals(1_000_000, pose.receivedAtSystemNanos)
	}

	@Test fun rejectedReadSideAdmissionDoesNotMutateLegacyPositionOrientationVelocityModalityOrStatus() {
		val bridge = Capture(); bridge.acceptFull()
		val position = bridge.hmd.position; val rotation = bridge.hmd.getRawRotation()
		val velocity = bridge.hmd.getVelocity(); val mode = bridge.hmd.sampleModality; val status = bridge.hmd.status
		val orientationSequence = bridge.hmd.correctionOrientationSample()!!.sequence
		repeat(3) { assertIs<RawHmdPoseInputAdmission.Rejected>(bridge.currentRawHmdPoseAdmission()) }
		assertEquals(position, bridge.hmd.position); assertEquals(rotation, bridge.hmd.getRawRotation())
		assertEquals(velocity, bridge.hmd.getVelocity()); assertEquals(mode, bridge.hmd.sampleModality)
		assertEquals(status, bridge.hmd.status); assertEquals(orientationSequence, bridge.hmd.correctionOrientationSample()!!.sequence)
		// A rejected trust read does not disable the next ordinary IMU/no-X orientation update.
		bridge.enqueue(Position.newBuilder().setTrackerId(0).setQx(1f).setDataSource(Position.DataSource.IMU).build(), bridge.active())
		bridge.dataRead()
		assertEquals(position, bridge.hmd.position); assertEquals(Quaternion(0f, 1f, 0f, 0f), bridge.hmd.getRawRotation())
		assertEquals(orientationSequence + 1, bridge.hmd.correctionOrientationSample()!!.sequence)
		assertEquals(TrackingModality.ROTATION_ONLY, bridge.hmd.sampleModality)
	}

	@Test fun rejectionCodesAreStableUniqueAndOrderedDeterministically() {
		val all = RawHmdPoseInputRejectionReason.entries
		assertEquals(all.size, all.map { it.code }.toSet().size)
		val rejected = assertIs<RawHmdPoseInputAdmission.Rejected>(admit(sample().copy(
			positionPresence = PositionComponentPresence(true, false, true), orientation = Quaternion(0f, 0f, 0f, 0f))))
		assertEquals(listOf("hmd_position_components_incomplete", "hmd_orientation_invalid"), rejected.reasons.map { it.code })
	}

	private class Capture(clock: () -> Long = { 1_000_000 }) : ProtobufBridge("admission-test", clock) {
		private fun tracker() = Tracker(Device(DeviceOrigin.STEAMVR), 900, "external-hmd",
			trackerPosition = TrackerPosition.HEAD, trackerNum = 0, hasPosition = true, hasRotation = true,
			isHmd = true, isComputed = true, allowVelocity = true, trackRotDirection = false)
			.also { it.status = TrackerStatus.OK }
		var hmd = tracker()
		init { install(); open() }
		@Suppress("UNCHECKED_CAST")
		private fun install() {
			val field = ProtobufBridge::class.java.getDeclaredField("remoteTrackersByTrackerId").apply { isAccessible = true }
			(field.get(this) as MutableMap<Int, Tracker>)[0] = hmd
			registerTrustedSteamVrHmd(hmd)
		}
		fun replaceHmd() { hmd = tracker(); install() }
		fun open() = openInboundTransportSession()
		fun close(handle: TransportSessionHandle) = closeInboundTransportSession(handle)
		fun active() = currentInboundTransportSession()
		fun enqueue(position: Position, handle: TransportSessionHandle?) = messageReceived(
			ProtobufMessage.parseFrom(ProtobufMessage.newBuilder().setPosition(position).build().toByteArray()), handle)
		fun enqueueFull(handle: TransportSessionHandle?) = enqueue(Position.newBuilder().setTrackerId(0)
			.setX(1f).setY(2f).setZ(3f).setQw(.5f).setQx(.5f).setQy(-.5f).setQz(.5f)
			.setVx(.1f).setVy(.2f).setVz(.3f).setDataSource(Position.DataSource.FULL).build(), handle)
		fun acceptFull(handle: TransportSessionHandle? = active()) { enqueueFull(handle); dataRead() }
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?) = true
		override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("Existing registry fixture")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = false
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "admission-test"
	}
}
