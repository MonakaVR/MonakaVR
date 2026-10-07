package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.config.FiltersConfig
import dev.slimevr.math.Angle
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.udp.TrackerDataType
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.test.*

class SlimeRawImuProductionBoundaryTests {
	private val space = CoordinateSpace("explicit-world", "rh_y_up_neg_z_forward", 7)
	private fun tracker(name: String = "physical-hip", physical: Boolean = true, imu: Boolean = true,
		internal: Boolean = false, computed: Boolean = false, hmd: Boolean = false,
		rotation: Boolean = true, target: TrackerPosition? = TrackerPosition.HIP,
		dataType: TrackerDataType = TrackerDataType.ROTATION) = Tracker(
		if (physical) UDPDevice(InetSocketAddress("127.0.0.1", 20000), InetAddress.getLoopbackAddress(), "fixture") else null,
		101, name, trackerPosition = target, hasPosition = hmd, hasRotation = rotation,
		isInternal = internal, isComputed = computed, isHmd = hmd,
		imuType = if (imu) IMUType.UNKNOWN else null, trackerDataType = dataType,
		allowReset = rotation, allowMounting = rotation, allowFiltering = rotation, trackRotDirection = false,
	).also { it.status = TrackerStatus.OK; it.setRotation(Quaternion.rotationAroundYAxis(.25f)) }
	private fun binding(t: Tracker) = SlimeRawImuCoordinateSpaceBinding("slime:${t.name}", space, true)
	private fun capture(t: Tracker, during: () -> Unit = {}) = SlimeIndependentImuOrientationCapture {
		during()
		t.correctionOrientationSample()!!.receivedAtSystemNanos + 10
	}
	private fun available(t: Tracker, boundary: SlimeRawImuProductionBoundary = SlimeRawImuProductionBoundary(capture(t)),
		at: Long = 100, bound: SlimeRawImuCoordinateSpaceBinding = binding(t)) =
		assertIs<SlimeRawImuInputResult.Available>(boundary.adapt(t, at, bound)).input
	private fun rejected(t: Tracker, expected: SlimeRawImuInputRejectionReason,
		bound: SlimeRawImuCoordinateSpaceBinding = binding(t), during: () -> Unit = {}) {
		assertEquals(expected, assertIs<SlimeRawImuInputResult.Unavailable>(
			SlimeRawImuProductionBoundary(capture(t, during)).adapt(t, 100, bound)).reason)
	}
	private fun equivalent(a: Quaternion, b: Quaternion) = assertTrue(abs(a.unit().dot(b.unit())) > .99999f)
	private fun epoch(imu: RawImuOrientationInput): PositionPredictionEpoch {
		val body = assertIs<HipBodyModelSnapshotResult.Available>(
			HipBodyModelSnapshot.create(.1f, .2f, .3f, .4f, .5f, .6f)).snapshot
		val hmd = RawHmdPoseInput(RawSourceIdentity("synthetic:hmd", RawSourceKind.RAW_HMD, isHmd = true),
			Vector3(0f, 1.7f, 0f), Quaternion.IDENTITY, imu.space,
			ObservationSampleProvenance(1, 1, "hmd-session", "hmd-cal", null, imu.space))
		return MainDecoupledHipInput(hmd, imu, body, FixedCalibrationIdentity("synthetic-fixed", "fixed:1"),
			imu.space, 3, 1000).epoch()
	}

	@Test fun physicalHipProducesExactRawIdentityAndProvenance() {
		val t = tracker()
		val input = available(t)
		assertEquals(RawSourceIdentity("slime:physical-hip", RawSourceKind.RAW_IMU), input.source)
		assertTrue(input.source.isRawImu())
		assertEquals(space, input.space)
		assertEquals(space, input.provenance.space)
		assertNull(input.provenance.mappingRevision)
		assertEquals(t.correctionOrientationSample()!!.sequence, input.provenance.sequence)
		assertEquals(90, input.provenance.sampleAtNanos)
		assertEquals(t.correctionSourceEpoch, input.provenance.sourceEpoch)
		assertEquals(t.resetsHandler.correctionCalibrationEpoch(), input.provenance.calibrationEpoch)
		val e = epoch(input)
		assertEquals(input.source.sourceId, e.imuSourceId)
		assertEquals(input.provenance.sourceEpoch, e.imuSourceEpoch)
		assertEquals(input.provenance.calibrationEpoch, e.imuCalibrationEpoch)
		assertNull(e.imuMappingRevision)
		assertEquals(space, e.coordinateSpace)
	}

	@Test fun selectedOrientationUsesFixedMountAndResetTransform() {
		val t = tracker()
		t.resetsHandler.mountingOrientation = Quaternion.rotationAroundZAxis(.4f)
		t.resetsHandler.resetFull(Quaternion.rotationAroundYAxis(.6f))
		t.resetsHandler.resetYaw(Quaternion.rotationAroundYAxis(.7f))
		val expected = t.resetsHandler.getCorrectionReferenceRotationFrom(t.getRawRotation())
		equivalent(expected, available(t).orientation)
		assertTrue(abs(expected.unit().dot(t.getRawRotation().unit())) < .999f)
	}

	@Test fun constraintFeedbackDoesNotAlterIndependentOrientationOrCalibrationLineage() {
		val t = tracker()
		val before = available(t)
		t.resetsHandler.updateConstraintFix(Quaternion.rotationAroundYAxis(.8f))
		val after = available(t)
		assertEquals(before, after)
		assertTrue(abs(after.orientation.dot(t.getRotation().unit())) < .999f)
	}

	@Test fun stayAlignedAndFilteringNeverEnterRawInput() {
		val t = tracker()
		val before = available(t)
		t.stayAligned.yawCorrection = Angle.ofRad(.9f)
		assertTrue(abs(before.orientation.dot(t.getAdjustedRotationForceStayAligned().unit())) < .999f)
		t.filteringHandler.readFilteringConfig(FiltersConfig().also { it.type = "smoothing"; it.amount = .8f }, Quaternion.IDENTITY)
		t.filteringHandler.dataTick(Quaternion.rotationAroundZAxis(1.2f))
		t.filteringHandler.update()
		t.tick(.02f)
		assertEquals(before, available(t))
	}

	@Test fun changingLearnedPhaseOneCorrectionDoesNotEnterRawInput() {
		val t = tracker()
		val before = available(t)
		val learner = HipRotationCorrection(RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, space, true),
			RotationCorrectionTuning(2_000_000, 1_000_000, .0001, .0001, 3.2, .1, 1000.0, 1, 100_000_000, 100_000_000))
		fun sample(id: String, seq: Long, at: Long, q: Quaternion, full: Boolean) = PoseObservation(
			id, TrackerPosition.HIP, at, position = if (full) Vector3(1f, 2f, 3f) else null, rotation = q,
			positionQuality = if (full) ObservationQuality.TRACKED else ObservationQuality.UNAVAILABLE,
			rotationQuality = ObservationQuality.TRACKED,
			modality = if (full) TrackingModality.FULL else TrackingModality.ROTATION_ONLY,
			provenance = ObservationSampleProvenance(seq, at, "session", "mount", null, space), correctionRotation = q)
		for ((seq, at) in listOf(1L to 1_000_000L, 2L to 4_000_000L)) {
			learner.update(sample("mtp:main", seq, at, Quaternion.rotationAroundYAxis(.5f), true),
				sample(before.source.sourceId, seq, at, Quaternion.IDENTITY, false), "mtp:main", at, 1, space)
		}
		assertTrue(learner.ready)
		assertNotEquals(Quaternion.IDENTITY, learner.correction)
		assertEquals(before, available(t))
	}

	@ParameterizedTest @ValueSource(booleans = [false, true])
	fun observationAndRawBoundariesSharePhysicalSampleTimeInEitherOrder(rawFirst: Boolean) {
		val t = tracker()
		var clock = t.correctionOrientationSample()!!.receivedAtSystemNanos + 10
		val pose = SlimeTrackerPoseObservationAdapter(receiptClock = { clock })
		val raw = SlimeRawImuProductionBoundary(pose.independentImuCapture)
		val first = if (rawFirst) available(t, raw).provenance else pose.adapt(t, 100)!!.provenance!!
		clock += 50
		t.heartbeat(); t.dataTick(); t.tick(.01f)
		val second = if (rawFirst) pose.adapt(t, 200)!!.provenance!! else available(t, raw, 200).provenance
		assertEquals(first.copy(space = null), second.copy(space = null))
		assertEquals(90, second.sampleAtNanos)
		val observation = pose.adapt(t, 300)!!
		assertNull(observation.provenance!!.space)
		assertNull(observation.provenance.mappingRevision)
		equivalent(available(t, raw, 300).orientation, observation.correctionRotation!!)
		assertEquals(t.getRotation(), observation.rotation)
		assertEquals(TrackingModality.ROTATION_ONLY, observation.modality)
	}

	@Test fun acceptedSampleProgressionChangesSequenceAndTimeButNotPredictionEpoch() {
		val t = tracker()
		val raw = SlimeRawImuProductionBoundary(capture(t))
		val before = available(t, raw)
		t.setRotation(Quaternion.rotationAroundYAxis(.8f))
		val after = available(t, raw, 200)
		assertEquals(before.provenance.sequence + 1, after.provenance.sequence)
		assertEquals(190, after.provenance.sampleAtNanos)
		assertNotEquals(before.orientation, after.orientation)
		assertEquals(epoch(before), epoch(after))
	}

	@ParameterizedTest @ValueSource(strings = ["handshake", "status", "replacement", "mount", "reset", "mountReset", "spaceRevision", "spaceId"])
	fun relationChangesAlterPredictionEpochWithIdenticalSensorQuaternion(change: String) {
		var t = tracker()
		val before = available(t)
		val sensor = t.getRawRotation()
		var bound = binding(t)
		when (change) {
			"handshake" -> t.markObservationReconnect()
			"status" -> { t.status = TrackerStatus.DISCONNECTED; t.status = TrackerStatus.OK }
			"replacement" -> t = tracker()
			"mount" -> t.resetsHandler.mountingOrientation = Quaternion.IDENTITY
			"reset" -> t.resetsHandler.resetYaw(Quaternion.IDENTITY)
			"mountReset" -> {
				t.resetsHandler.saveMountingReset = true
				t.resetsHandler.trySetMountingReset(Quaternion.rotationAroundZAxis(.2f))
			}
			"spaceRevision" -> bound = bound.copy(space = space.copy(revision = 8))
			"spaceId" -> bound = bound.copy(space = space.copy(id = "other-world"))
		}
		val after = available(t, bound = bound)
		assertEquals(sensor, t.getRawRotation())
		assertNotEquals(epoch(before), epoch(after))
		assertEquals(before.provenance.mappingRevision, after.provenance.mappingRevision)
	}

	@ParameterizedTest @ValueSource(strings = ["unconfirmed", "mismatch", "blankSource", "blankId", "negativeRevision", "wrongConvention"])
	fun invalidBindingsFailClosed(case: String) {
		val t = tracker()
		val b = binding(t)
		val (bad, reason) = when (case) {
			"unconfirmed" -> b.copy(operatorConfirmed = false) to SlimeRawImuInputRejectionReason.SPACE_BINDING_UNCONFIRMED
			"mismatch" -> b.copy(sourceId = "slime:other") to SlimeRawImuInputRejectionReason.SPACE_BINDING_SOURCE_MISMATCH
			"blankSource" -> b.copy(sourceId = " ") to SlimeRawImuInputRejectionReason.SPACE_BINDING_SOURCE_MISMATCH
			"blankId" -> b.copy(space = space.copy(id = " ")) to SlimeRawImuInputRejectionReason.SPACE_INVALID
			"negativeRevision" -> b.copy(space = space.copy(revision = -1)) to SlimeRawImuInputRejectionReason.SPACE_INVALID
			else -> b.copy(space = space.copy(convention = "other")) to SlimeRawImuInputRejectionReason.SPACE_INVALID
		}
		rejected(t, reason, bad)
	}

	@ParameterizedTest @ValueSource(strings = ["noDevice", "notImu", "wrongDataType", "internal", "computed", "hmd", "noRotation", "none"])
	fun physicalEligibilityRejectsNonPhysicalAndDerivedTrackers(case: String) {
		val (t, reason) = when (case) {
			"noDevice" -> tracker(physical = false) to SlimeRawImuInputRejectionReason.TRACKER_NOT_PHYSICAL
			"notImu" -> tracker(imu = false) to SlimeRawImuInputRejectionReason.TRACKER_NOT_IMU
			"wrongDataType" -> tracker(dataType = TrackerDataType.FLEX_ANGLE) to SlimeRawImuInputRejectionReason.TRACKER_NOT_IMU
			"internal" -> tracker(internal = true) to SlimeRawImuInputRejectionReason.TRACKER_INTERNAL
			"computed" -> tracker(computed = true) to SlimeRawImuInputRejectionReason.TRACKER_COMPUTED
			"hmd" -> tracker(hmd = true) to SlimeRawImuInputRejectionReason.TRACKER_IS_HMD
			"noRotation" -> tracker(rotation = false) to SlimeRawImuInputRejectionReason.ROTATION_UNAVAILABLE
			else -> tracker().also { it.sampleModality = TrackingModality.NONE } to SlimeRawImuInputRejectionReason.ROTATION_UNAVAILABLE
		}
		rejected(t, reason)
	}

	@Test fun arbitraryOrUnassignedTargetsCannotBecomeHipInput() {
		for (target in listOf(null, TrackerPosition.LEFT_FOOT, TrackerPosition.RIGHT_FOOT, TrackerPosition.CHEST, TrackerPosition.WAIST))
			rejected(tracker(target = target), SlimeRawImuInputRejectionReason.TARGET_NOT_HIP)
	}

	@ParameterizedTest @ValueSource(strings = ["OK", "BUSY", "OCCLUDED", "TIMED_OUT", "ERROR", "DISCONNECTED"])
	fun statusUsesExistingRotationOnlyQualitySemantics(status: String) {
		val t = tracker().also { it.status = TrackerStatus.valueOf(status) }
		val quality = t.status.slimeObservationQuality(TrackingModality.ROTATION_ONLY)
		if (quality.usable) available(t) else rejected(t, SlimeRawImuInputRejectionReason.STATUS_UNUSABLE)
		assertEquals(quality, SlimeTrackerPoseObservationAdapter().adapt(t, 100)!!.rotationQuality)
	}

	@ParameterizedTest @ValueSource(strings = ["zero", "nan", "inf", "overflow", "tiny"])
	fun invalidQuaternionsAreRejectedWithoutIdentitySubstitution(case: String) {
		val t = tracker()
		t.setRotation(when (case) {
			"zero" -> Quaternion.NULL
			"nan" -> Quaternion(Float.NaN, 0f, 0f, 0f)
			"inf" -> Quaternion(Float.POSITIVE_INFINITY, 0f, 0f, 0f)
			"overflow" -> Quaternion(Float.MAX_VALUE, 0f, 0f, 0f)
			else -> Quaternion(1e-8f, 0f, 0f, 0f)
		})
		rejected(t, SlimeRawImuInputRejectionReason.ORIENTATION_INVALID)
	}

	@Test fun finiteNonUnitQuaternionIsNormalizedOnlyAtRawBoundary() {
		val t = tracker()
		t.setRotation(Quaternion(2f, 0f, 0f, 0f))
		val input = available(t)
		assertEquals(1f, input.orientation.lenSq(), .00001f)
		assertEquals(4f, t.getRawRotation().lenSq())
	}

	@ParameterizedTest @ValueSource(strings = ["monaka-direct:", "monaka-solver:", "monaka-private:", "human://"])
	fun outputNamespacesAreRejectedBeforeSlimePrefixCanHideThem(prefix: String) {
		rejected(tracker(name = "${prefix}output"), SlimeRawImuInputRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED)
		val t = tracker()
		val result = SlimeRawImuProductionBoundary(capture(t), "${prefix}output").adapt(t, 100, binding(t))
		assertEquals(SlimeRawImuInputRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED,
			assertIs<SlimeRawImuInputResult.Unavailable>(result).reason)
	}

	@Test fun sourcePrefixRespectsExistingAdapterConvention() {
		val t = tracker()
		val bound = binding(t).copy(sourceId = "physical:${t.name}")
		val input = available(t, SlimeRawImuProductionBoundary(capture(t), "physical"), bound = bound)
		assertEquals(bound.sourceId, input.source.sourceId)
		val result = SlimeRawImuProductionBoundary(capture(t), "monaka-direct").adapt(t, 100, binding(t))
		assertEquals(SlimeRawImuInputRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED,
			assertIs<SlimeRawImuInputResult.Unavailable>(result).reason)
	}

	@ParameterizedTest @ValueSource(strings = ["sample", "source", "calibration"])
	fun captureChangesFailClosedAndCannotPoisonTimeCache(change: String) {
		val t = tracker()
		var mutate = true
		val shared = capture(t) {
			if (mutate) {
				mutate = false
				when (change) {
					"sample" -> t.setRotation(Quaternion.rotationAroundYAxis(.8f))
					"source" -> t.markObservationReconnect()
					else -> t.resetsHandler.mountingOrientation = Quaternion.IDENTITY
				}
			}
		}
		val raw = SlimeRawImuProductionBoundary(shared)
		val expected = when (change) {
			"sample" -> SlimeRawImuInputRejectionReason.SAMPLE_UNSTABLE
			"source" -> SlimeRawImuInputRejectionReason.SOURCE_EPOCH_CHANGED
			else -> SlimeRawImuInputRejectionReason.CALIBRATION_EPOCH_CHANGED
		}
		assertEquals(expected, assertIs<SlimeRawImuInputResult.Unavailable>(raw.adapt(t, 100, binding(t))).reason)
		assertEquals(190, available(t, raw, 200).provenance.sampleAtNanos)
	}

	@Test fun missingAcceptedSampleAndUnmappableReceiptTimeAreRejected() {
		val t = Tracker(tracker().device, 102, "not-yet-sampled", trackerPosition = TrackerPosition.HIP,
			hasRotation = true, imuType = IMUType.UNKNOWN, trackRotDirection = false).also { it.status = TrackerStatus.OK }
		rejected(t, SlimeRawImuInputRejectionReason.SAMPLE_UNAVAILABLE)
		t.setRotation(Quaternion.IDENTITY)
		for (age in listOf(-1L, 101L)) {
			val raw = SlimeRawImuProductionBoundary(SlimeIndependentImuOrientationCapture {
				t.correctionOrientationSample()!!.receivedAtSystemNanos + age })
			assertEquals(SlimeRawImuInputRejectionReason.SAMPLE_TIME_UNAVAILABLE,
				assertIs<SlimeRawImuInputResult.Unavailable>(raw.adapt(t, 100, binding(t))).reason)
		}
	}
}
