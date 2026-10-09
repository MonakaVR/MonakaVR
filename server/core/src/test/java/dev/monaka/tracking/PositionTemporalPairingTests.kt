package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.*

class PositionTemporalPairingTests {
	@Test fun teacherPredictionWorldEpochsMatchExactlyBeforeAnyTemporalComparison() {
		val legacy = input()
		for ((teacherWorld, predictionWorld) in listOf(null to null, "W1" to "W1", "W1" to "W2",
			null to "W1", "W1" to null)) {
			val expected = epoch.copy(commonWorldEpoch = predictionWorld)
			val value = legacy.copy(mainTeacher = legacy.mainTeacher.copy(
				provenance = legacy.mainTeacher.provenance.copy(commonWorldEpoch = teacherWorld)),
				prediction = prediction(predictionEpoch = expected), expectedPredictionEpoch = expected)
			val teacher = teacherEpoch(value)
			assertEquals(teacherWorld, teacher.commonWorldEpoch)
			if (teacherWorld == predictionWorld) {
				val paired = pairable(value, teacher)
				assertEquals(teacherWorld, paired.teacherEpoch.commonWorldEpoch)
				assertEquals(predictionWorld, paired.predictionEpoch.commonWorldEpoch)
				val measured = assertIs<PositionErrorMeasurementResult.Measured>(PositionErrorMeasurement.evaluate(value, teacher, policy))
				assertEquals(teacherWorld, measured.sample.teacherEpoch.commonWorldEpoch)
			} else {
				rejected(value, PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, teacher,
					structuralReason = "common_world_epoch_mismatch")
				assertIs<PositionErrorMeasurementResult.Rejected>(PositionErrorMeasurement.evaluate(value, teacher, policy))
			}
		}
		assertFailsWith<IllegalArgumentException> { teacherEpoch().copy(commonWorldEpoch = " ") }
		rejected(legacy, PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH,
			teacherEpoch().copy(commonWorldEpoch = "W1"))
	}

	private val space = CoordinateSpace("canonical", "rh_y_up_neg_z_forward", 2)
	private val origin = RawSourceIdentity("synthetic:main", RawSourceKind.RAW_BACKEND)
	private val mount = MainTrackerMountCalibrationIdentity("synthetic-mount", "synthetic-mount:1")
	private val safe = setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
		PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION)
	// Synthetic HIP_CENTER and input lineage only; no MTP mount relabeling or production predictor.
	private val body = assertIs<HipBodyModelSnapshotResult.Available>(
		HipBodyModelSnapshot.create(.1f, .2f, .25f, .3f, .35f, .15f)).snapshot
	private val fixed = syntheticHeadAnchorCalibration("synthetic:hmd", body.identity.modelId)
	private val epoch = PositionPredictionEpoch("synthetic:hmd", "hmd:1", "hmd-cal:1", null,
		"synthetic:imu", "imu:1", "imu-cal:1", 1, body.identity.modelId, body.identity.epoch, fixed.identity.calibrationId, fixed.identity.epoch, space, 3)
	private val policy = PositionTemporalPairingPolicy(10, 100, 100, 100)
	private val unlimited = PositionTemporalPairingPolicy(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)

	private fun prediction(earliest: Long = 80, latest: Long = 90, generated: Long = 100,
		predictionEpoch: PositionPredictionEpoch = epoch, sequence: Long = 7,
		body: PositionBodyReference = PositionBodyReference.HIP_CENTER,
		predictionSpace: CoordinateSpace = space,
		dependencies: Set<PositionPredictionDependency> = safe) = PositionPrediction.available(
		TrackerPosition.HIP, Vector3(.1f, 1f, .2f), predictionSpace, body,
		PositionPredictionProvenance(sequence, generated, earliest, latest, predictionEpoch, 4, earliest, 4, latest), dependencies)

	private fun input(teacherAt: Long = 85, earliest: Long = 80, latest: Long = 90,
		generated: Long = 100, now: Long = 100): PositionCorrectionInput = PositionCorrectionInput(
		MainHipCenterPositionTeacher(origin.sourceId, Vector3(.1f, 1f, .2f), ObservationQuality.TRACKED,
			ObservationSampleProvenance(4, teacherAt, "main:1", "main-cal:1", 1, space), origin, mount),
		prediction(earliest, latest, generated), space, epoch, 3, now)

	private fun teacherEpoch(input: PositionCorrectionInput = input()) = assertNotNull(PositionTeacherEpoch.from(input))
	private fun pair(input: PositionCorrectionInput = input(), expected: PositionTeacherEpoch = teacherEpoch(),
		pairingPolicy: PositionTemporalPairingPolicy = policy) = PositionTemporalPairing.check(input, expected, pairingPolicy)
	private fun pairable(input: PositionCorrectionInput = input(), expected: PositionTeacherEpoch = teacherEpoch(),
		pairingPolicy: PositionTemporalPairingPolicy = policy) =
		assertIs<PositionTemporalPairingResult.Pairable>(pair(input, expected, pairingPolicy))
	private fun rejected(input: PositionCorrectionInput, reason: PositionTemporalPairingRejectionReason,
		expected: PositionTeacherEpoch = teacherEpoch(), pairingPolicy: PositionTemporalPairingPolicy = policy,
		structuralReason: String? = null) {
		val result = assertIs<PositionTemporalPairingResult.Rejected>(pair(input, expected, pairingPolicy))
		assertEquals(reason, result.reason)
		assertEquals(structuralReason, result.structuralReason)
	}
	private fun structural(input: PositionCorrectionInput, reason: String) = rejected(input,
		PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, structuralReason = reason)

	@Test fun pairableCapturesOnlyImmutableComparisonFacts() {
		val value = input()
		val result = pairable(value)
		assertEquals(85L, result.teacherSampleAtNanos)
		assertEquals(80L, result.predictionInputEarliestAtNanos)
		assertEquals(90L, result.predictionInputLatestAtNanos)
		assertEquals(0L, result.teacherToPredictionInputDistanceNanos)
		assertEquals(teacherEpoch(value), result.teacherEpoch)
		assertEquals(epoch, result.predictionEpoch)
		assertFalse(PoseObservation::class.java.isInstance(result))
		assertEquals(setOf("teacherSampleAtNanos", "predictionInputEarliestAtNanos", "predictionInputLatestAtNanos",
			"teacherToPredictionInputDistanceNanos", "teacherEpoch", "predictionEpoch"),
			PositionTemporalPairingResult.Pairable::class.java.declaredFields.map { it.name }.toSet())
	}

	@ParameterizedTest
	@CsvSource("69,11,false", "70,10,true", "80,0,true", "85,0,true", "90,0,true", "100,10,true", "101,11,false")
	fun supportWindowDistanceHasInclusiveSkewLimit(teacherAt: Long, distance: Long, allowed: Boolean) {
		val value = input(teacherAt, now = 110)
		if (allowed) assertEquals(distance, pairable(value).teacherToPredictionInputDistanceNanos)
		else rejected(value, PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED)
	}

	@ParameterizedTest
	@CsvSource("19,true", "20,true", "21,false")
	fun teacherAgeUsesInclusivePhysicalSampleBoundary(age: Long, allowed: Boolean) {
		val value = input(teacherAt = 100 - age)
		val limit = unlimited.copy(maxTeacherAgeNanos = 20)
		if (allowed) pairable(value, pairingPolicy = limit)
		else rejected(value, PositionTemporalPairingRejectionReason.TEACHER_STALE, pairingPolicy = limit)
	}

	@ParameterizedTest
	@CsvSource("19,true", "20,true", "21,false")
	fun predictionInputAgeUsesInclusiveLatestSupportBoundary(age: Long, allowed: Boolean) {
		val value = input(earliest = 0, latest = 100 - age)
		val limit = unlimited.copy(maxPredictionInputAgeNanos = 20)
		if (allowed) pairable(value, pairingPolicy = limit)
		else rejected(value, PositionTemporalPairingRejectionReason.PREDICTION_INPUT_STALE, pairingPolicy = limit)
	}

	@ParameterizedTest
	@CsvSource("19,true", "20,true", "21,false")
	fun predictionGenerationAgeHasItsOwnInclusiveBoundary(age: Long, allowed: Boolean) {
		val value = input(earliest = 0, latest = 0, generated = 100 - age)
		val limit = unlimited.copy(maxPredictionGenerationAgeNanos = 20)
		if (allowed) pairable(value, pairingPolicy = limit)
		else rejected(value, PositionTemporalPairingRejectionReason.PREDICTION_GENERATION_STALE, pairingPolicy = limit)
	}

	@Test fun negativePolicyLimitsAreRejectedIndividually() {
		assertFailsWith<IllegalArgumentException> { policy.copy(maxTeacherToPredictionInputSkewNanos = -1) }
		assertFailsWith<IllegalArgumentException> { policy.copy(maxTeacherAgeNanos = -1) }
		assertFailsWith<IllegalArgumentException> { policy.copy(maxPredictionInputAgeNanos = -1) }
		assertFailsWith<IllegalArgumentException> { policy.copy(maxPredictionGenerationAgeNanos = -1) }
	}

	@Test fun allZeroPolicyRequiresExactFreshnessAndAllowsAnInclusiveWindow() {
		val exact = PositionTemporalPairingPolicy(0, 0, 0, 0)
		pairable(input(100, 0, 100, 100), pairingPolicy = exact)
		pairable(input(0, 0, 0, 0, 0), pairingPolicy = exact)
		rejected(input(99, 0, 100, 100), PositionTemporalPairingRejectionReason.TEACHER_STALE, pairingPolicy = exact)
		rejected(input(100, 0, 99, 100), PositionTemporalPairingRejectionReason.PREDICTION_INPUT_STALE, pairingPolicy = exact)
		rejected(input(100, 0, 99, 99), PositionTemporalPairingRejectionReason.PREDICTION_GENERATION_STALE,
			pairingPolicy = unlimited.copy(maxPredictionGenerationAgeNanos = 0))
	}

	@Test fun zeroSkewAcceptsOnlyTeacherInsideSupportWindow() {
		val exactSkew = unlimited.copy(maxTeacherToPredictionInputSkewNanos = 0)
		for (time in listOf(80L, 85L, 90L)) pairable(input(time), pairingPolicy = exactSkew)
		for (time in listOf(79L, 91L)) rejected(input(time),
			PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED, pairingPolicy = exactSkew)
	}

	@Test fun comparisonContractHasNoPublicationTimeOrRawObservationField() {
		assertTrue(PositionCorrectionInput::class.java.declaredFields.none {
			it.type == PoseObservation::class.java || it.name.contains("observedAt")
		})
		rejected(input(teacherAt = 70), PositionTemporalPairingRejectionReason.TEACHER_STALE,
			pairingPolicy = unlimited.copy(maxTeacherAgeNanos = 20))
	}

	@Test fun freshGenerationCannotRefreshStalePredictionPhysicalSupport() {
		val value = input(teacherAt = 100, earliest = 0, latest = 0, generated = 100)
		rejected(value, PositionTemporalPairingRejectionReason.PREDICTION_INPUT_STALE,
			pairingPolicy = unlimited.copy(maxPredictionInputAgeNanos = 99))
	}

	@Test fun skewUsesPhysicalWindowEvenWhenTeacherEqualsGenerationTime() {
		rejected(input(teacherAt = 100, earliest = 0, latest = 1),
			PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED)
	}

	@Test fun futureTeacherRetainsStructuralPreflightReason() {
		structural(input(teacherAt = 101), "future_sample")
	}

	@Test fun futureEarliestSupportRetainsStructuralPreflightReason() {
		structural(input(earliest = 101, latest = 102, generated = 103), "future_sample")
	}

	@Test fun futureLatestSupportRetainsStructuralPreflightReason() {
		structural(input(earliest = 80, latest = 101, generated = 102), "future_sample")
	}

	@Test fun futureGenerationRetainsStructuralPreflightReason() {
		structural(input(generated = 101), "future_sample")
	}

	@Test fun negativeNowFailsClosedBeforeSubtraction() {
		structural(input().copy(nowNanos = -1), "future_sample")
	}

	@Test fun teacherFactoryMapsOnlyExistingContinuityFields() {
		val value = input()
		assertEquals(PositionTeacherEpoch(origin.sourceId, "main:1", "main-cal:1", 1, space, 3,
			PositionBodyReference.HIP_CENTER, mount), teacherEpoch(value))
		val changed = value.copy(mainTeacher = value.mainTeacher.copy(
			provenance = value.mainTeacher.provenance.copy(mappingRevision = null)))
		assertNull(teacherEpoch(changed).mappingRevision)
	}

	@Test fun teacherFactoryFailsClosedOnUnavailableOrInconsistentIdentity() {
		val value = input()
		for (invalid in listOf(
			value.copy(mainTeacher = value.mainTeacher.copy(provenance = value.mainTeacher.provenance.copy(space = null))),
			value.copy(assignmentGeneration = -1),
			value.copy(mainTeacher = value.mainTeacher.copy(rawOrigin = origin.copy(sourceId = "other"))),
			value.copy(mainTeacher = value.mainTeacher.copy(sourceId = " ")),
		)) assertNull(PositionTeacherEpoch.from(invalid))
	}

	@Test fun teacherEpochValidatesRequiredIdentityFields() {
		val expected = teacherEpoch()
		assertFailsWith<IllegalArgumentException> { expected.copy(sourceId = " ") }
		assertFailsWith<IllegalArgumentException> { expected.copy(sourceEpoch = " ") }
		assertFailsWith<IllegalArgumentException> { expected.copy(calibrationEpoch = " ") }
		assertFailsWith<IllegalArgumentException> { expected.copy(assignmentGeneration = -1) }
		assertFailsWith<IllegalArgumentException> { expected.copy(bodyReference = PositionBodyReference.UNKNOWN) }
	}

	@Test fun everyTeacherEpochComponentMustMatchCurrentExpectedContext() {
		val current = teacherEpoch()
		for (expected in listOf(
			current.copy(sourceId = "other:main"),
			current.copy(sourceEpoch = "main:2"),
			current.copy(calibrationEpoch = "main-cal:2"),
			current.copy(mappingRevision = 2),
			current.copy(coordinateSpace = space.copy(id = "other")),
			current.copy(coordinateSpace = space.copy(convention = "other")),
			current.copy(coordinateSpace = space.copy(revision = 3)),
			current.copy(assignmentGeneration = 4),
			current.copy(bodyReference = PositionBodyReference.TRACKER_MOUNT),
			current.copy(mountCalibration = mount.copy(epoch = "synthetic-mount:2")),
		)) rejected(input(), PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH, expected)
	}

	@Test fun nullableTeacherMappingTransitionsCannotPairOldSamples() {
		val value = input()
		for ((before, after) in listOf(null to 1L, 1L to 2L, 1L to null)) {
			val old = value.copy(mainTeacher = value.mainTeacher.copy(
				provenance = value.mainTeacher.provenance.copy(mappingRevision = before)))
			val expected = teacherEpoch(old).copy(mappingRevision = after)
			rejected(old, PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH, expected)
		}
	}

	@Test fun allPredictionEpochComponentsRetainStructuralMismatchPrecedence() {
		for (expected in listOf(
			epoch.copy(hmdSourceId = "other:hmd"), epoch.copy(hmdSourceEpoch = "hmd:2"),
			epoch.copy(hmdCalibrationEpoch = "hmd-cal:2"), epoch.copy(hmdMappingRevision = 1),
			epoch.copy(imuSourceId = "other:imu"), epoch.copy(imuSourceEpoch = "imu:2"),
			epoch.copy(imuCalibrationEpoch = "imu-cal:2"), epoch.copy(imuMappingRevision = 2),
			epoch.copy(bodyModelEpoch = "body:2"), epoch.copy(fixedCalibrationId = "other-head-anchor"),
			epoch.copy(fixedCalibrationEpoch = "fixed:2"),
			epoch.copy(coordinateSpace = space.copy(id = "other")),
			epoch.copy(coordinateSpace = space.copy(convention = "other")),
			epoch.copy(coordinateSpace = space.copy(revision = 3)), epoch.copy(assignmentGeneration = 4),
		)) structural(input().copy(expectedPredictionEpoch = expected), "prediction_epoch_mismatch")
	}

	@Test fun bothNullablePredictionMappingTransitionsRejectOldPrediction() {
		for ((before, after) in listOf(null to 1L, 1L to 2L, 1L to null)) {
			for (hmd in listOf(true, false)) {
				val old = if (hmd) epoch.copy(hmdMappingRevision = before) else epoch.copy(imuMappingRevision = before)
				val current = if (hmd) epoch.copy(hmdMappingRevision = after) else epoch.copy(imuMappingRevision = after)
				structural(input().copy(prediction = prediction(predictionEpoch = old), expectedPredictionEpoch = current),
					"prediction_epoch_mismatch")
			}
		}
	}

	@Test fun exactMainPredictionAndExpectedSpacesCannotBeRepairedByTimeProximity() {
		val value = input()
		for (changed in listOf(space.copy(id = "other"), space.copy(convention = "other"), space.copy(revision = 3))) {
			structural(value.copy(mainTeacher = value.mainTeacher.copy(
				provenance = value.mainTeacher.provenance.copy(space = changed))), "space_mismatch")
			structural(value.copy(expectedSpace = changed), "space_mismatch")
			structural(value.copy(prediction = prediction(predictionSpace = changed)), "prediction_space_epoch_mismatch")
			structural(value.copy(prediction = prediction(predictionSpace = changed,
				predictionEpoch = epoch.copy(coordinateSpace = changed))), "space_mismatch")
		}
	}

	@Test fun assignmentChangesInvalidateOldContextAndFreshContextCanPair() {
		val value = input()
		structural(value.copy(assignmentGeneration = 4), "prediction_epoch_mismatch")
		val changed = value.copy(assignmentGeneration = 4, expectedPredictionEpoch = epoch.copy(assignmentGeneration = 4),
			prediction = prediction(predictionEpoch = epoch.copy(assignmentGeneration = 4)))
		rejected(changed, PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH)
		pairable(changed, expected = teacherEpoch(changed))
	}

	@Test fun structuralFailuresWinOverTeacherEpochSkewAndStaleness() {
		val value = input(teacherAt = 0)
		val badExpected = teacherEpoch().copy(sourceEpoch = "other")
		val failures = listOf(
			value.copy(mainTeacher = value.mainTeacher.copy(rawOrigin = origin.copy(kind = RawSourceKind.DERIVED_OUTPUT))) to "main_not_raw_backend",
			value.copy(mainTeacher = value.mainTeacher.copy(positionQuality = ObservationQuality.UNAVAILABLE)) to "main_position_invalid",
			value.copy(mainTeacher = value.mainTeacher.copy(position = Vector3(Float.NaN, 0f, 0f))) to "main_position_invalid",
			value.copy(mainTeacher = value.mainTeacher.copy(positionQuality = ObservationQuality.STALE)) to "main_position_invalid",
			value.copy(prediction = PositionPrediction.unavailable(TrackerPosition.HIP)) to "prediction_unavailable",
			value.copy(prediction = prediction(body = PositionBodyReference.TRACKER_MOUNT)) to "body_reference_mismatch",
			value.copy(mainTeacher = value.mainTeacher.copy(provenance = value.mainTeacher.provenance.copy(space = null))) to "main_space_unknown",
		)
		for ((invalid, diagnostic) in failures) rejected(invalid, PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE,
			expected = badExpected, pairingPolicy = PositionTemporalPairingPolicy(0, 0, 0, 0), structuralReason = diagnostic)
		for (forbidden in PositionCorrectionTeacherEligibility.forbiddenDependencies)
			structural(value.copy(prediction = prediction(dependencies = safe + forbidden)), "feedback_dependency")
		structural(value.copy(prediction = prediction(dependencies = safe - PositionPredictionDependency.RAW_HMD)), "input_lineage_incomplete")
	}

	@Test fun trackerMountPredictionCannotCompareToFixedHipCenterTeacher() {
		structural(input().copy(prediction = prediction(body = PositionBodyReference.TRACKER_MOUNT)),
			"body_reference_mismatch")
		assertEquals(PositionBodyReference.HIP_CENTER, input().mainTeacher.bodyReference)
	}

	@Test fun numericEqualityNeverBypassesEitherEpochMismatch() {
		val value = input()
		assertEquals(value.mainTeacher.position, value.prediction.position)
		rejected(value, PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH,
			expected = teacherEpoch().copy(calibrationEpoch = "new-upstream"))
		structural(value.copy(expectedPredictionEpoch = epoch.copy(bodyModelEpoch = "new-body")), "prediction_epoch_mismatch")
	}

	@Test fun sequencePhysicalTimeAndNumericProgressionDoNotChangeTeacherEpoch() {
		val value = input()
		val progressed = value.copy(mainTeacher = value.mainTeacher.copy(
			position = Vector3(.2f, 1.1f, .3f),
			provenance = value.mainTeacher.provenance.copy(sequence = Long.MAX_VALUE, sampleAtNanos = 86)),
			prediction = prediction(sequence = Long.MAX_VALUE))
		assertEquals(teacherEpoch(value), teacherEpoch(progressed))
		assertEquals(epoch, pairable(progressed).predictionEpoch)
		assertEquals(86L, pairable(progressed).teacherSampleAtNanos)
	}

	@Test fun orderedNonnegativeLongExtremesNeverOverflowAgeIntoFreshness() {
		val max = Long.MAX_VALUE
		val teacherOld = input(0, max, max, max, max)
		rejected(teacherOld, PositionTemporalPairingRejectionReason.TEACHER_STALE,
			pairingPolicy = unlimited.copy(maxTeacherAgeNanos = max - 1))
		val inputOld = input(max, 0, 0, max, max)
		rejected(inputOld, PositionTemporalPairingRejectionReason.PREDICTION_INPUT_STALE,
			pairingPolicy = unlimited.copy(maxPredictionInputAgeNanos = max - 1))
		val generationOld = input(max, 0, 0, 0, max)
		rejected(generationOld, PositionTemporalPairingRejectionReason.PREDICTION_GENERATION_STALE,
			pairingPolicy = unlimited.copy(maxPredictionGenerationAgeNanos = max - 1))
		for (value in listOf(teacherOld, inputOld, generationOld))
			assertEquals(max, pairable(value, pairingPolicy = unlimited).teacherToPredictionInputDistanceNanos)
	}

	@Test fun intervalDistanceAtBothLongExtremesHasInclusiveSkewLimit() {
		val max = Long.MAX_VALUE
		for (value in listOf(input(0, max, max, max, max), input(max, 0, 0, max, max))) {
			assertEquals(max, pairable(value, pairingPolicy = unlimited).teacherToPredictionInputDistanceNanos)
			rejected(value, PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED,
				pairingPolicy = unlimited.copy(maxTeacherToPredictionInputSkewNanos = max - 1))
		}
		pairable(input(max, 0, max, max, max), pairingPolicy = PositionTemporalPairingPolicy(0, 0, 0, 0))
	}

	@Test fun deterministicRejectionOrderIsTeacherThenInputThenGenerationThenSkew() {
		val value = input(0, 10, 20, 30, 100)
		rejected(value, PositionTemporalPairingRejectionReason.TEACHER_STALE,
			pairingPolicy = PositionTemporalPairingPolicy(0, 0, 0, 0))
		rejected(value, PositionTemporalPairingRejectionReason.PREDICTION_INPUT_STALE,
			pairingPolicy = PositionTemporalPairingPolicy(0, 100, 0, 0))
		rejected(value, PositionTemporalPairingRejectionReason.PREDICTION_GENERATION_STALE,
			pairingPolicy = PositionTemporalPairingPolicy(0, 100, 80, 0))
		rejected(value, PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED,
			pairingPolicy = PositionTemporalPairingPolicy(0, 100, 80, 70))
	}

	@Test fun callsHaveNoRetainedPairStateOrInputMutation() {
		val value = input()
		val original = value.copy()
		val first = pair(value)
		rejected(value, PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH,
			expected = teacherEpoch().copy(sourceEpoch = "reconnected"))
		structural(input(teacherAt = 101), "future_sample")
		rejected(input(teacherAt = 0), PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED)
		assertEquals(first, pair(value))
		assertEquals(original, value)
		assertTrue(PositionTemporalPairing::class.java.declaredFields.all {
			java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name == "INSTANCE"
		})
	}
}
