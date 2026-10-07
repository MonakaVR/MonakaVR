package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

/** Actual 5V -> structural preflight -> 5U boundaries; prediction is deliberately synthetic. */
class CalibratedMainTeacherTemporalPairingTests {
	private val space = CoordinateSpace("canonical", "rh_y_up_neg_z_forward", 2)
	private val origin = RawSourceIdentity("mtp:main", RawSourceKind.RAW_BACKEND)
	private val zero = Vector3(0f, 0f, 0f)
	private val body = assertIs<HipBodyModelSnapshotResult.Available>(
		HipBodyModelSnapshot.create(.1f, .2f, .25f, .3f, .35f, .15f)).snapshot
	private val predictionEpoch = predictorInput(body).epoch()
	private fun predictorInput(snapshot: HipBodyModelSnapshot) = MainDecoupledHipInput(
		RawHmdPoseInput(RawSourceIdentity("hmd", RawSourceKind.RAW_HMD, isHmd = true),
			Vector3(0f, 1.7f, 0f), Quaternion.IDENTITY, space,
			ObservationSampleProvenance(4, 80, "hmd:1", "hmd-cal:1", null, space)),
		RawImuOrientationInput(RawSourceIdentity("imu", RawSourceKind.RAW_IMU),
			Quaternion.IDENTITY, space,
			ObservationSampleProvenance(4, 90, "imu:1", "imu-cal:1", 1, space)),
		snapshot, syntheticHeadAnchorCalibration("hmd", snapshot.identity.modelId), space, 3, 100, predictionSequence = 7)
	private val safe = setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
		PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION)
	private val policy = PositionTemporalPairingPolicy(10, 100, 100, 100)
	private fun calibration(id: String = "main-mount", session: String = "mount-session:1", offset: Vector3 = zero) =
		assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(MainTrackerMountCalibrationSnapshot.create(
			id, session, origin.sourceId, offset)).snapshot
	private fun raw(observed: Long = 100, at: Long = 85, mapping: Long? = 1) = PoseObservation(
		origin.sourceId, TrackerPosition.HIP, observed, position = Vector3(.1f, 1f, .2f),
		rotation = Quaternion(.70710677f, 0f, .70710677f, 0f),
		provenance = ObservationSampleProvenance(4, at, "main:1", "upstream-cal:1", mapping, space))
	private fun teacher(raw: PoseObservation = raw(), mount: MainTrackerMountCalibrationSnapshot = calibration()) =
		assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(raw, origin, mount)).teacher
	private fun prediction(epoch: PositionPredictionEpoch = predictionEpoch,
		body: PositionBodyReference = PositionBodyReference.HIP_CENTER,
		target: TrackerPosition = TrackerPosition.HIP, dependencies: Set<PositionPredictionDependency> = safe) =
		PositionPrediction.available(target, Vector3(.1f, 1f, .2f), epoch.coordinateSpace, body,
			PositionPredictionProvenance(7, 100, 80, 90, epoch, 4, 80, 4, 90), dependencies)
	private fun input(teacher: MainHipCenterPositionTeacher = teacher()) =
		PositionCorrectionInput(teacher, prediction(), space, predictionEpoch, 3, 100)
	// Explicit current source/assignment/calibration selection, never auto-adopt an input sample's epoch.
	private fun expected(mount: MainTrackerMountCalibrationSnapshot = calibration(), mapping: Long? = 1) =
		PositionTeacherEpoch(origin.sourceId, "main:1", "upstream-cal:1", mapping, space, 3,
			PositionBodyReference.HIP_CENTER, mount.identity)
	private fun pair(input: PositionCorrectionInput = input(), expected: PositionTeacherEpoch = expected()) =
		PositionTemporalPairing.check(input, expected, policy)
	private fun mismatch(input: PositionCorrectionInput = input(), expected: PositionTeacherEpoch) {
		assertTrue(PositionCorrectionTeacherEligibility.check(input).eligibleForPairing)
		val result = assertIs<PositionTemporalPairingResult.Rejected>(pair(input, expected))
		assertEquals(PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH, result.reason)
		assertNull(result.structuralReason)
	}
	private fun structural(input: PositionCorrectionInput, reason: String) {
		val result = assertIs<PositionTemporalPairingResult.Rejected>(pair(input,
			expected(calibration(session = "new-mount"))))
		assertEquals(PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, result.reason)
		assertEquals(reason, result.structuralReason)
	}

	@Test fun actualCalibratedPositionOnlyChainPairsAndPreservesRawObjectAndMountLineage() {
		val raw = raw()
		val mount = calibration(offset = Vector3(.2f, 0f, 0f))
		val teacher = teacher(raw, mount)
		assertEquals(.1f, teacher.position.x, .00001f)
		assertEquals(1f, teacher.position.y, .00001f)
		assertEquals(0f, teacher.position.z, .00001f)
		assertSame(raw.provenance, teacher.provenance)
		assertSame(mount.identity, teacher.mountCalibration)
		assertEquals(TrackerPosition.HIP, teacher.target)
		assertEquals(PositionBodyReference.HIP_CENTER, teacher.bodyReference)
		val result = assertIs<PositionTemporalPairingResult.Pairable>(pair(input(teacher), expected(mount)))
		assertEquals(expected(mount), result.teacherEpoch)
		assertEquals(predictionEpoch, result.predictionEpoch)
		assertEquals(85L, result.teacherSampleAtNanos)
		assertEquals(0L, result.teacherToPredictionInputDistanceNanos)
		assertSame(mount.identity, result.teacherEpoch.mountCalibration)
	}

	@Test fun changedMountSessionRejectsEvenWhenBothHipPositionsAreEqual() {
		val old = teacher()
		val currentMount = calibration(session = "mount-session:2")
		val current = teacher(mount = currentMount)
		assertEquals(old.position, current.position)
		assertEquals(old.provenance, current.provenance)
		assertNotEquals(old.mountCalibration, current.mountCalibration)
		mismatch(input(old), expected(currentMount))
		assertIs<PositionTemporalPairingResult.Pairable>(pair(input(current), expected(currentMount)))
	}

	@Test fun changedOffsetWithReusedSessionRejectsOldTeacher() {
		val before = calibration()
		val after = calibration(offset = Vector3(.01f, 0f, 0f))
		assertEquals(before.sessionEpoch, after.sessionEpoch)
		assertEquals(before.identity.calibrationId, after.identity.calibrationId)
		assertNotEquals(before.identity.epoch, after.identity.epoch)
		mismatch(input(teacher(mount = before)), expected(after))
		assertIs<PositionTemporalPairingResult.Pairable>(pair(input(teacher(mount = after)), expected(after)))
	}

	@Test fun changedCalibrationIdRejectsDespiteEqualEffectiveEpochAndPosition() {
		val after = calibration(id = "other-calibration")
		assertEquals(calibration().identity.epoch, after.identity.epoch)
		assertEquals(teacher().position, teacher(mount = after).position)
		mismatch(expected = expected(after))
	}

	@Test fun upstreamCalibrationAloneInvalidatesWithoutChangingMount() {
		val current = expected().copy(calibrationEpoch = "upstream-cal:2")
		assertEquals(expected().mountCalibration, current.mountCalibration)
		mismatch(expected = current)
	}

	@Test fun mountAloneInvalidatesWithoutChangingUpstreamCalibration() {
		val current = expected(calibration(session = "remount"))
		assertEquals(expected().calibrationEpoch, current.calibrationEpoch)
		assertEquals(expected().sourceEpoch, current.sourceEpoch)
		assertEquals(expected().mappingRevision, current.mappingRevision)
		mismatch(expected = current)
	}

	@Test fun reconnectAloneInvalidatesWithSamePhysicalMount() {
		val current = expected().copy(sourceEpoch = "main:reconnect")
		assertEquals(expected().mountCalibration, current.mountCalibration)
		mismatch(expected = current)
	}

	@ParameterizedTest
	@CsvSource("NULL,1", "1,2", "1,NULL")
	fun allNullableMappingTransitionsInvalidateIndependentlyOfMount(before: String, after: String) {
		val previous = before.toLongOrNull()
		val current = after.toLongOrNull()
		mismatch(input(teacher(raw(mapping = previous))), expected(mapping = current))
	}

	@Test fun eachExactExpectedSpaceComponentRejectsOldTeacher() {
		for (changed in listOf(space.copy(id = "other"), space.copy(convention = "other"), space.copy(revision = 3)))
			mismatch(expected = expected().copy(coordinateSpace = changed))
	}

	@Test fun sourceIdentityChangeRejectsEvenWithEqualNumbers() {
		mismatch(expected = expected().copy(sourceId = "mtp:new-main"))
	}

	@Test fun assignmentChangeRejectsOldTeacherAndOldPredictionInCurrentContext() {
		mismatch(expected = expected().copy(assignmentGeneration = 4))
		structural(input().copy(assignmentGeneration = 4,
			expectedPredictionEpoch = predictionEpoch.copy(assignmentGeneration = 4)), "prediction_epoch_mismatch")
		val epoch = predictionEpoch.copy(assignmentGeneration = 4)
		val current = input().copy(assignmentGeneration = 4, expectedPredictionEpoch = epoch, prediction = prediction(epoch))
		mismatch(current, expected())
		assertIs<PositionTemporalPairingResult.Pairable>(pair(current, expected().copy(assignmentGeneration = 4)))
	}

	@Test fun publicationTimeCannotRefreshTeacherOrAlterPhysicalPairing() {
		val original = raw(at = 70)
		val first = teacher(original)
		for (observed in listOf(0L, 100L, Long.MAX_VALUE)) {
			val normalized = teacher(original.copy(observedAtNanos = observed))
			assertSame(original.provenance, normalized.provenance)
			assertEquals(first, normalized)
			assertEquals(pair(input(first)), pair(input(normalized)))
			val result = assertIs<PositionTemporalPairingResult.Rejected>(PositionTemporalPairing.check(
				input(normalized), expected(), policy.copy(maxTeacherAgeNanos = 20)))
			assertEquals(PositionTemporalPairingRejectionReason.TEACHER_STALE, result.reason)
		}
	}

	@Test fun sameCalibrationAndNewPhysicalSamplePreserveContinuityWithoutRestamping() {
		val original = raw()
		val next = original.copy(provenance = original.provenance!!.copy(sequence = 5, sampleAtNanos = 86))
		val normalized = teacher(next)
		assertSame(next.provenance, normalized.provenance)
		assertEquals(PositionTeacherEpoch.from(input(teacher(original))), PositionTeacherEpoch.from(input(normalized)))
		assertEquals(86L, assertIs<PositionTemporalPairingResult.Pairable>(pair(input(normalized))).teacherSampleAtNanos)
	}

	@Test fun positionOnlyTeacherCarriesNoRawPoseRotationOrPublicationField() {
		assertFalse(PoseObservation::class.java.isInstance(teacher()))
		assertTrue(MainHipCenterPositionTeacher::class.java.declaredFields.none {
			it.type == Quaternion::class.java || it.type == PoseObservation::class.java || it.name.contains("observedAt")
		})
		assertEquals(listOf(MainHipCenterPositionTeacher::class.java), PositionCorrectionInput::class.java.declaredFields
			.filter { it.name == "mainTeacher" }.map { it.type })
	}

	@Test fun trackerRotationQualityIsNotAComparisonRequirementAfterNormalization() {
		val normalized = teacher()
		assertTrue(PositionCorrectionTeacherEligibility.check(input(normalized)).eligibleForPairing)
		assertIs<PositionTemporalPairingResult.Pairable>(pair(input(normalized)))
		assertIs<MainHipCenterTeacherResult.Unavailable>(MainTrackerMountToHipCenter.normalize(
			raw().copy(rotationQuality = ObservationQuality.STALE), origin, calibration()))
	}

	@Test fun rawPoseWithMissingRotationCannotEnterViaNormalization() {
		assertIs<MainHipCenterTeacherResult.Unavailable>(MainTrackerMountToHipCenter.normalize(
			raw().copy(rotation = null, rotationQuality = ObservationQuality.UNAVAILABLE), origin, calibration()))
	}

	@Test fun unusablePositionAndNonfiniteTeacherKeepStructuralPrecedence() {
		for (quality in listOf(ObservationQuality.LOST, ObservationQuality.STALE, ObservationQuality.UNAVAILABLE))
			structural(input(teacher().copy(positionQuality = quality)), "main_position_invalid")
		for (position in listOf(Vector3(Float.NaN, 0f, 0f), Vector3(0f, Float.POSITIVE_INFINITY, 0f)))
			structural(input(teacher().copy(position = position)), "main_position_invalid")
	}

	@Test fun malformedSpaceCannotThrowOrOverrideStructuralPrecedence() {
		val normalized = teacher()
		structural(input(normalized.copy(provenance = normalized.provenance.copy(space = null))), "main_space_unknown")
		for (changed in listOf(space.copy(id = "other"), space.copy(convention = "other"), space.copy(revision = 3)))
			structural(input(normalized.copy(provenance = normalized.provenance.copy(space = changed))), "space_mismatch")
	}

	@Test fun feedbackOriginsAndSourceDisagreementCannotPair() {
		val normalized = teacher()
		for (bad in listOf(origin.copy(kind = RawSourceKind.DERIVED_OUTPUT), origin.copy(isComputed = true),
			origin.copy(isInternal = true), origin.copy(isHmd = true), origin.copy(sourceId = "other")))
			structural(input(normalized.copy(rawOrigin = bad)), "main_not_raw_backend")
		for (prefix in listOf("monaka-direct:", "monaka-solver:", "monaka-private:", "human://")) {
			val bad = origin.copy(sourceId = "${prefix}main")
			structural(input(normalized.copy(sourceId = bad.sourceId, rawOrigin = bad)), "main_not_raw_backend")
		}
	}

	@Test fun predictionFeedbackAndWrongReferenceRemainIneligible() {
		for (dependency in PositionCorrectionTeacherEligibility.forbiddenDependencies)
			structural(input().copy(prediction = prediction(dependencies = safe + dependency)), "feedback_dependency")
		structural(input().copy(prediction = prediction(body = PositionBodyReference.TRACKER_MOUNT)), "body_reference_mismatch")
		structural(input().copy(prediction = prediction(target = TrackerPosition.LEFT_FOOT)), "main_position_invalid")
	}

	@Test fun mountChangesNeverAlterPredictionEpochOrFixedCalibration() {
		val previous = input()
		val current = input(teacher(mount = calibration(session = "new-session")))
		assertEquals(previous.expectedPredictionEpoch, current.expectedPredictionEpoch)
		assertEquals(previous.prediction.provenance!!.epoch, current.prediction.provenance!!.epoch)
		assertNotEquals(PositionTeacherEpoch.from(previous), PositionTeacherEpoch.from(current))
	}

	@Test fun equalCalibrationSelectionCanBeRecreatedWithoutChangingIdentity() {
		assertEquals(calibration().identity, calibration().identity)
		assertIs<PositionTemporalPairingResult.Pairable>(pair(input(teacher(mount = calibration())), expected(calibration())))
	}

	@ParameterizedTest
	@ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun bodyContentChangeRejectsOldPredictionWithSameNumbersAndSameTeacher(index: Int) {
		val values = mutableListOf(body.headShift, body.neckLength, body.upperChestLength,
			body.chestLength, body.waistLength, body.hipLength)
		values[index] += .125f
		val changed = assertIs<HipBodyModelSnapshotResult.Available>(HipBodyModelSnapshot.create(
			values[0], values[1], values[2], values[3], values[4], values[5])).snapshot
		val oldInput = predictorInput(body)
		val newInput = oldInput.copy(bodyModel = changed)
		val old = input()
		val current = old.copy(expectedPredictionEpoch = newInput.epoch(), prediction = prediction(newInput.epoch()))
		assertEquals(oldInput.rawHmd, newInput.rawHmd)
		assertEquals(oldInput.rawImu, newInput.rawImu)
		assertEquals(old.prediction.position, current.prediction.position)
		assertSame(old.mainTeacher, current.mainTeacher)
		assertEquals(PositionTeacherEpoch.from(old), PositionTeacherEpoch.from(current))
		assertEquals(oldInput.fixedCalibration, newInput.fixedCalibration)
		assertNotEquals(old.expectedPredictionEpoch, current.expectedPredictionEpoch)
		assertIs<PositionTemporalPairingResult.Pairable>(pair(old))
		structural(old.copy(expectedPredictionEpoch = newInput.epoch()), "prediction_epoch_mismatch")
		assertIs<PositionTemporalPairingResult.Pairable>(pair(current))
	}

	@Test fun modelIdChangeWithSameContentEpochRejectsOldPredictionAndKeepsTeacherLineage() {
		// Factory fixes the v1 model ID; exercise future schema identity at the epoch DTO.
		val old = input()
		val changed = predictionEpoch.copy(bodyModelId = "synthetic:other-body-model")
		assertEquals(predictionEpoch.bodyModelEpoch, changed.bodyModelEpoch)
		val current = old.copy(expectedPredictionEpoch = changed, prediction = prediction(changed))
		assertEquals(old.prediction.position, current.prediction.position)
		assertEquals(PositionTeacherEpoch.from(old), PositionTeacherEpoch.from(current))
		assertNotEquals(predictionEpoch, changed)
		structural(old.copy(expectedPredictionEpoch = changed), "prediction_epoch_mismatch")
		assertIs<PositionTemporalPairingResult.Pairable>(pair(current))
	}
}
