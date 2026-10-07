package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.tracking.mtp.MtpPoseAdapter
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.test.*

class MainTrackerMountCalibrationTests {
	private val source = "mtp:A"
	private val origin = RawSourceIdentity(source, RawSourceKind.RAW_BACKEND)
	private val space = CoordinateSpace("canonical", "rh_y_up_neg_z_forward", 2)
	private val provenance = ObservationSampleProvenance(17, 80, "publisher:clock", "upstream-input", 7, space)
	private val p = Vector3(2f, 3f, 4f)
	private val half = sqrt(.5f)
	private fun observation(q: Quaternion = Quaternion.IDENTITY) = PoseObservation(source, TrackerPosition.HIP,
		999, position = p, rotation = q, provenance = provenance)
	private fun calibration(offset: Vector3 = Vector3.NULL, session: String = "mount:1", sourceId: String = source,
		id: String = "main-mount") = assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(
		MainTrackerMountCalibrationSnapshot.create(id, session, sourceId, offset)).snapshot
	private fun teacher(raw: PoseObservation = observation(), offset: Vector3 = Vector3.NULL,
		cal: MainTrackerMountCalibrationSnapshot = calibration(offset)) =
		assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(raw, origin, cal)).teacher
	private fun rejected(reason: MainHipCenterTeacherRejectionReason, raw: PoseObservation = observation(),
		rawOrigin: RawSourceIdentity = origin, cal: MainTrackerMountCalibrationSnapshot = calibration()) {
		assertEquals(reason, assertIs<MainHipCenterTeacherResult.Unavailable>(
			MainTrackerMountToHipCenter.normalize(raw, rawOrigin, cal)).reason)
	}
	private fun near(expected: Vector3, actual: Vector3) {
		assertEquals(expected.x, actual.x, .00001f)
		assertEquals(expected.y, actual.y, .00001f)
		assertEquals(expected.z, actual.z, .00001f)
	}

	@Test fun identityAndExplicitZeroOffsetKeepPosition() { assertEquals(p, teacher().position) }
	@Test fun identityAddsNonzeroLocalOffset() {
		near(Vector3(2.5f, 2f, 6f), teacher(offset = Vector3(.5f, -1f, 2f)).position)
	}
	// Golden values follow repository Hamilton products, independent of the production helper.
	@Test fun positiveNinetyYawMapsLocalXToNegativeZ() {
		near(Vector3(2f, 3f, 3f), teacher(observation(Quaternion(half, 0f, half, 0f)), Vector3.POS_X).position)
	}
	@Test fun positiveNinetyPitchMapsLocalYToPositiveZ() {
		near(Vector3(2f, 3f, 5f), teacher(observation(Quaternion(half, half, 0f, 0f)), Vector3.POS_Y).position)
	}
	@Test fun positiveNinetyRollMapsLocalXToPositiveY() {
		near(Vector3(2f, 4f, 4f), teacher(observation(Quaternion(half, 0f, 0f, half)), Vector3.POS_X).position)
	}
	@Test fun arbitraryUnitQuaternionHasIndependentRationalGolden() {
		// q=(1,2,3,4)/sqrt(30): first rotation-matrix column = (-2/3,2/3,1/3).
		val n = sqrt(30f)
		near(Vector3(2f - 2f / 3f, 3f + 2f / 3f, 4f + 1f / 3f),
			teacher(observation(Quaternion(1f / n, 2f / n, 3f / n, 4f / n)), Vector3.POS_X).position)
	}
	@Test fun finiteNonunitQuaternionIsNormalizedWithoutScalingOffset() {
		near(Vector3(2f, 3f, 5f), teacher(observation(Quaternion(2f, 2f, 0f, 0f)), Vector3.POS_Y).position)
	}
	@Test fun extremeFiniteQuaternionDoesNotOverflowItsNorm() {
		near(Vector3(2f, 3f, 5f), teacher(observation(Quaternion(Float.MAX_VALUE, Float.MAX_VALUE, 0f, 0f)), Vector3.POS_Y).position)
	}
	@Test fun subnormalNonzeroQuaternionDoesNotUnderflowItsNorm() {
		near(Vector3(2f, 3f, 5f), teacher(observation(Quaternion(Float.MIN_VALUE, Float.MIN_VALUE, 0f, 0f)), Vector3.POS_Y).position)
	}
	@Test fun quaternionTwinsGiveSamePosition() {
		val q = Quaternion(half, 0f, half, 0f)
		near(teacher(observation(q), Vector3.POS_X).position, teacher(observation(-q), Vector3.POS_X).position)
	}
	@Test fun zeroQuaternionRejectedEvenForZeroOffset() {
		rejected(MainHipCenterTeacherRejectionReason.ORIENTATION_INVALID, observation(Quaternion.NULL))
	}
	@Test fun everyNonfiniteQuaternionComponentRejected() {
		for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			for (q in listOf(Quaternion(bad, 0f, 0f, 0f), Quaternion(1f, bad, 0f, 0f),
				Quaternion(1f, 0f, bad, 0f), Quaternion(1f, 0f, 0f, bad)))
				rejected(MainHipCenterTeacherRejectionReason.ORIENTATION_INVALID, observation(q))
		}
	}
	@Test fun everyNonfinitePositionComponentRejected() {
		for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
			for (v in listOf(Vector3(bad, 0f, 0f), Vector3(0f, bad, 0f), Vector3(0f, 0f, bad)))
				rejected(MainHipCenterTeacherRejectionReason.POSITION_NONFINITE, observation().copy(position = v))
	}
	@Test fun finiteInputsWithUnrepresentableOutputFailClosed() {
		rejected(MainHipCenterTeacherRejectionReason.TRANSFORM_NONFINITE,
			observation().copy(position = Vector3(Float.MAX_VALUE, 0f, 0f)),
			cal = calibration(Vector3(Float.MAX_VALUE, 0f, 0f)))
	}
	@Test fun missingPositionRejected() {
		rejected(MainHipCenterTeacherRejectionReason.POSITION_UNAVAILABLE,
			observation().copy(position = null, positionQuality = ObservationQuality.UNAVAILABLE))
	}
	@Test fun missingRotationRejected() {
		rejected(MainHipCenterTeacherRejectionReason.ROTATION_UNAVAILABLE,
			observation().copy(rotation = null, rotationQuality = ObservationQuality.UNAVAILABLE))
	}
	@Test fun everyUnusablePositionQualityRejected() {
		for (quality in ObservationQuality.entries.filterNot { it.usable })
			rejected(MainHipCenterTeacherRejectionReason.POSITION_QUALITY_UNUSABLE, observation().copy(positionQuality = quality))
	}
	@Test fun everyUnusableRotationQualityRejected() {
		for (quality in ObservationQuality.entries.filterNot { it.usable })
			rejected(MainHipCenterTeacherRejectionReason.ROTATION_QUALITY_UNUSABLE, observation().copy(rotationQuality = quality))
	}
	@Test fun degradedRemainsUsableAndPositionQualityIsPreserved() {
		assertEquals(ObservationQuality.DEGRADED, teacher(observation().copy(
			positionQuality = ObservationQuality.DEGRADED, rotationQuality = ObservationQuality.DEGRADED)).positionQuality)
	}
	@Test fun nonfullModalityRejectedEvenWithResidualPositionAndRotation() {
		for (modality in listOf(TrackingModality.ROTATION_ONLY, TrackingModality.NONE))
			rejected(MainHipCenterTeacherRejectionReason.MODALITY_NOT_FULL, observation().copy(modality = modality))
	}
	@Test fun onlyHipTargetAccepted() {
		for (target in TrackerPosition.entries.filterNot { it == TrackerPosition.HIP })
			rejected(MainHipCenterTeacherRejectionReason.TARGET_NOT_HIP, observation().copy(target = target))
	}
	@Test fun differentCalibrationSourceRejectedWithSameNumericOffset() {
		rejected(MainHipCenterTeacherRejectionReason.CALIBRATION_SOURCE_MISMATCH, cal = calibration(sourceId = "mtp:B"))
	}
	@Test fun rawOriginMustMatchObservationSource() {
		rejected(MainHipCenterTeacherRejectionReason.SOURCE_IDENTITY_MISMATCH, rawOrigin = origin.copy(sourceId = "mtp:B"))
	}
	@Test fun allFeedbackNamespacesRejectedOnEitherIdentity() {
		for (prefix in listOf("monaka-direct:", "monaka-solver:", "monaka-private:", "human://")) {
			rejected(MainHipCenterTeacherRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED,
				observation().copy(sourceId = prefix + "hip"))
			rejected(MainHipCenterTeacherRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED, rawOrigin = origin.copy(sourceId = prefix + "hip"))
		}
	}
	@Test fun computedInternalAndDerivedOriginsRejected() {
		for (bad in listOf(origin.copy(isComputed = true), origin.copy(isInternal = true),
			origin.copy(kind = RawSourceKind.COMPUTED_TRACKER), origin.copy(kind = RawSourceKind.DERIVED_OUTPUT)))
			rejected(MainHipCenterTeacherRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED, rawOrigin = bad)
	}
	@Test fun hmdAndImuOriginsRejected() {
		for (bad in listOf(origin.copy(kind = RawSourceKind.RAW_HMD), origin.copy(kind = RawSourceKind.RAW_IMU), origin.copy(isHmd = true)))
			rejected(MainHipCenterTeacherRejectionReason.SOURCE_NOT_RAW_BACKEND, rawOrigin = bad)
	}
	@Test fun provenanceAndSpaceAreRequired() {
		rejected(MainHipCenterTeacherRejectionReason.PROVENANCE_UNAVAILABLE, observation().copy(provenance = null))
		rejected(MainHipCenterTeacherRejectionReason.SPACE_UNAVAILABLE, observation().copy(provenance = provenance.copy(space = null)))
	}
	@Test fun unsupportedOrInvalidSpaceRejected() {
		for (bad in listOf(space.copy(convention = "other"), space.copy(id = " "), space.copy(revision = -1)))
			rejected(MainHipCenterTeacherRejectionReason.SPACE_UNSUPPORTED, observation().copy(provenance = provenance.copy(space = bad)))
	}
	@Test fun provenanceConstructorRejectsBlankUpstreamEpochs() {
		assertFailsWith<IllegalArgumentException> { provenance.copy(sourceEpoch = " ") }
		assertFailsWith<IllegalArgumentException> { provenance.copy(calibrationEpoch = " ") }
	}
	@Test fun calibrationRejectsBlankIdentifiersAndNonfiniteOffset() {
		for ((id, epoch, binding) in listOf(Triple("", "epoch", source), Triple("id", " ", source), Triple("id", "epoch", "")))
			assertIs<MainTrackerMountCalibrationSnapshotResult.Unavailable>(MainTrackerMountCalibrationSnapshot.create(id, epoch, binding, Vector3.NULL))
		for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
			for (v in listOf(Vector3(bad, 0f, 0f), Vector3(0f, bad, 0f), Vector3(0f, 0f, bad)))
				assertEquals(MainHipCenterTeacherRejectionReason.CALIBRATION_INVALID,
					assertIs<MainTrackerMountCalibrationSnapshotResult.Unavailable>(MainTrackerMountCalibrationSnapshot.create("id", "epoch", source, v)).reason)
		assertFailsWith<IllegalArgumentException> { MainTrackerMountCalibrationIdentity("", "epoch") }
		assertFailsWith<IllegalArgumentException> { MainTrackerMountCalibrationIdentity("id", " ") }
	}
	@Test fun callerValueReassignmentCannotAlterSnapshot() {
		var offset = Vector3(.5f, -1f, 2f)
		val cal = calibration(offset)
		offset += Vector3.POS_X // Vector3 has only immutable val components; operation replaces caller value.
		assertEquals(Vector3(1.5f, -1f, 2f), offset)
		assertEquals(Vector3(.5f, -1f, 2f), cal.trackerToHipCenterLocalOffset)
		assertEquals(calibration(Vector3(.5f, -1f, 2f)).identity, cal.identity)
	}
	@Test fun sameContentAndSessionProduceSameIdentityWithGoldenEpoch() {
		assertEquals(calibration().identity, calibration().identity)
		assertEquals("main-mount-v1;source=5:mtp:A;session=7:mount:1;00000000;00000000;00000000", calibration().identity.epoch)
	}
	@Test fun sameOffsetDifferentMountSessionIsDistinct() {
		assertNotEquals(calibration().identity, calibration(session = "mount:2").identity)
	}
	@Test fun changedOffsetCannotKeepEffectiveEpochEvenIfCallerReusesSession() {
		for (v in listOf(Vector3.POS_X, Vector3.POS_Y, Vector3.POS_Z, Vector3(-0f, 0f, 0f)))
			assertNotEquals(calibration().identity.epoch, calibration(v).identity.epoch)
	}
	@Test fun changedSourceOrCalibrationIdIsDistinct() {
		assertNotEquals(calibration().identity, calibration(sourceId = "mtp:B").identity)
		assertNotEquals(calibration().identity, calibration(id = "other").identity)
	}
	@Test fun opaqueEpochPartsCannotCollideByDelimiterInjection() {
		assertNotEquals(calibration(sourceId = "mtp:A;session=1:x", session = "y").identity,
			calibration(sourceId = "mtp:A", session = "x;session=1:y").identity)
	}
	@Test fun rawProvenanceAndPhysicalTimeStayExactAcrossRepeatedNormalization() {
		val raw = observation()
		val result = teacher(raw, Vector3.POS_X)
		assertSame(raw.provenance, result.provenance)
		assertSame(provenance.space, result.space)
		assertEquals(17, result.provenance.sequence)
		assertEquals(80, result.provenance.sampleAtNanos)
		assertEquals(7, result.provenance.mappingRevision)
		assertEquals("publisher:clock", result.provenance.sourceEpoch)
		assertEquals("upstream-input", result.provenance.calibrationEpoch)
		assertEquals(result, teacher(raw.copy(observedAtNanos = Long.MAX_VALUE), Vector3.POS_X))
		assertEquals(raw, observation())
	}
	@Test fun reconnectSampleMappingAndSpaceProgressionDoNotRecreateMountIdentity() {
		val cal = calibration()
		for (changed in listOf(provenance.copy(sourceEpoch = "reconnect:2"), provenance.copy(sequence = 18, sampleAtNanos = 81),
			provenance.copy(calibrationEpoch = "upstream:2"), provenance.copy(mappingRevision = null),
			provenance.copy(space = space.copy(id = "room-b", revision = 9)))) {
			val result = teacher(observation().copy(provenance = changed), cal = cal)
			assertSame(changed, result.provenance)
			assertEquals(cal.identity, result.mountCalibration)
		}
	}
	@Test fun onlySameSampleRawRotationAffectsOffset() {
		val raw = observation(Quaternion(half, 0f, half, 0f)).copy(correctionRotation = Quaternion.IDENTITY)
		near(Vector3(2f, 3f, 3f), teacher(raw, Vector3.POS_X).position)
		assertEquals(teacher(raw, Vector3.POS_X), teacher(raw.copy(correctionRotation = Quaternion.NULL), Vector3.POS_X))
		near(Vector3(3f, 3f, 4f), teacher(raw.copy(rotation = Quaternion.IDENTITY), Vector3.POS_X).position)
	}
	@Test fun derivedTeacherHasOnlyPositionWithExplicitBodyReference() {
		val result = teacher()
		assertEquals(PositionBodyReference.HIP_CENTER, result.bodyReference)
		assertEquals(TrackerPosition.HIP, result.target)
		assertEquals(origin, result.rawOrigin)
		assertFalse(PoseObservation::class.java.isInstance(result))
		assertTrue(MainHipCenterPositionTeacher::class.java.declaredFields.none { it.name.contains("rotation", true) })
	}
	@Test fun mainCalibrationTypesRemainAbsentFromPredictorLineage() {
		for (type in listOf(MainDecoupledHipInput::class.java, MainDecoupledHipPredictor::class.java,
			PositionPredictionEpoch::class.java, RawHmdPoseInput::class.java, RawImuOrientationInput::class.java)) {
			assertTrue(type.declaredFields.none { it.type.name.contains("MainTrackerMountCalibration") })
			assertTrue(type.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType }
				.none { it.name.contains("MainTrackerMountCalibration") })
		}
	}
	@Test fun actualMtpAdapterOutputIsTransformedWithoutRelabelOrProvenanceLoss() {
		val pose = assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(
			File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes())).value as MtpPose
		// Wire xyzw -> ktmath wxyz; +90 yaw sends local +X to world -Z.
		val raw = MtpPoseAdapter().adapt(pose.copy(orientation = listOf(0.0, sqrt(.5), 0.0, sqrt(.5))), TrackerPosition.HIP, 120)
		val cal = calibration(Vector3.POS_X, sourceId = raw.sourceId)
		val result = assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(
			raw, RawSourceIdentity(raw.sourceId, RawSourceKind.RAW_BACKEND), cal)).teacher
		near(raw.position!! + Vector3(0f, 0f, -1f), result.position)
		assertSame(raw.provenance, result.provenance)
		assertEquals(pose.input.session_id, result.provenance.calibrationEpoch)
		assertEquals(120, result.provenance.sampleAtNanos)
		assertEquals(pose.mapping_revision, result.provenance.mappingRevision)
		assertEquals(pose.coordinate_space, result.space)
		assertNotEquals(raw.position, result.position)
		assertNotNull(raw.rotation)
		assertEquals(TrackingModality.FULL, raw.modality)
	}
}
