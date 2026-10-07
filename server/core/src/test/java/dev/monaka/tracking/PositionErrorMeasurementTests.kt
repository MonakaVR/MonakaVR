package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.*

class PositionErrorMeasurementTests {
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val origin = RawSourceIdentity("synthetic:main", RawSourceKind.RAW_BACKEND)
	private val mount = MainTrackerMountCalibrationIdentity("main-mount", "fit:1;content:abc")
	private val dependencies = setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
		PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION)
	private val epoch = PositionPredictionEpoch("synthetic:hmd", "hmd:1", "provider:2", 4,
		"slime:hip", "imu:3", "mount:4", null, "body", "body:5", "fixed", "fixed:6", space, 3)
	private val policy = PositionTemporalPairingPolicy(10, 100, 100, 100)
	private val zero = Vector3(0f, 0f, 0f)

	private fun prediction(position: Vector3 = Vector3(1f, 0f, 0f), earliest: Long = 80,
		latest: Long = 90, generated: Long = 95, sequence: Long = 73,
		predictionEpoch: PositionPredictionEpoch = epoch) = PositionPrediction.available(
		TrackerPosition.HIP, position, space, PositionBodyReference.HIP_CENTER,
		PositionPredictionProvenance(sequence, generated, earliest, latest, predictionEpoch), dependencies)

	private fun input(teacher: Vector3 = Vector3(2f, 0f, 0f), predicted: Vector3 = Vector3(1f, 0f, 0f),
		teacherAt: Long = 85, earliest: Long = 80, latest: Long = 90, generated: Long = 95,
		now: Long = 100, teacherSequence: Long = 42, predictionSequence: Long = 73) = PositionCorrectionInput(
		MainHipCenterPositionTeacher(origin.sourceId, teacher, ObservationQuality.TRACKED,
			ObservationSampleProvenance(teacherSequence, teacherAt, "main:1", "upstream:2", 5, space), origin, mount),
		prediction(predicted, earliest, latest, generated, predictionSequence), space, epoch, 3, now)

	private fun teacherEpoch(value: PositionCorrectionInput) = assertNotNull(PositionTeacherEpoch.from(value))
	private fun measured(value: PositionCorrectionInput = input(), expected: PositionTeacherEpoch = teacherEpoch(value),
		pairingPolicy: PositionTemporalPairingPolicy = policy) = assertIs<PositionErrorMeasurementResult.Measured>(
		PositionErrorMeasurement.evaluate(value, expected, pairingPolicy)).sample

	private fun rejected(value: PositionCorrectionInput, expected: PositionTeacherEpoch = teacherEpoch(input()),
		pairingPolicy: PositionTemporalPairingPolicy = policy,
		reason: PositionTemporalPairingRejectionReason, structural: String? = null) {
		val pairing = assertIs<PositionTemporalPairingResult.Rejected>(PositionTemporalPairing.check(value, expected, pairingPolicy))
		val result = assertIs<PositionErrorMeasurementResult.Rejected>(PositionErrorMeasurement.evaluate(value, expected, pairingPolicy))
		assertEquals(PositionErrorMeasurementRejectionReason.PAIRING_REJECTED, result.reason)
		assertEquals(reason, result.pairingReason)
		assertEquals(structural, result.structuralReason)
		assertEquals(pairing.reason, result.pairingReason)
		assertEquals(pairing.structuralReason, result.structuralReason)
	}

	@ParameterizedTest
	@CsvSource("2,1,1", "-2,1,-3", "1.75,-2.5,4.25", "0,0,0")
	fun signConventionAndExactReconstruction(teacher: Float, prediction: Float, error: Float) {
		val sample = measured(input(Vector3(teacher, 0f, 0f), Vector3(prediction, 0f, 0f)))
		assertEquals(Vector3(error, 0f, 0f), sample.errorWorld)
		assertEquals(sample.teacherPosition, sample.predictionPosition + sample.errorWorld)
	}

	@Test fun nontrivialThreeAxisErrorReconstructsTeacher() {
		val sample = measured(input(Vector3(2.3f, -1.7f, .05f), Vector3(-.2f, 3.1f, -.8f)))
		val reconstructed = sample.predictionPosition + sample.errorWorld
		assertEquals(2.5f, sample.errorWorld.x, 1e-6f)
		assertEquals(-4.8f, sample.errorWorld.y, 1e-6f)
		assertEquals(.85f, sample.errorWorld.z, 1e-6f)
		assertEquals(sample.teacherPosition.x, reconstructed.x, 1e-6f)
		assertEquals(sample.teacherPosition.y, reconstructed.y, 1e-6f)
		assertEquals(sample.teacherPosition.z, reconstructed.z, 1e-6f)
	}

	@Test fun zeroResidualIsValidMeasurement() {
		assertEquals(zero, measured(input(Vector3(1f, 2f, 3f), Vector3(1f, 2f, 3f))).errorWorld)
	}

	@Test fun hugeFiniteResidualHasNoThresholdOrClamp() {
		val sample = measured(input(Vector3(Float.MAX_VALUE, -Float.MAX_VALUE, 1e30f), zero))
		assertEquals(sample.teacherPosition, sample.errorWorld)
	}

	@ParameterizedTest
	@ValueSource(ints = [0, 1, 2])
	fun finiteSourceSubtractionOverflowFailsClosedOnEveryAxis(axis: Int) {
		for (sign in listOf(1f, -1f)) {
			val teacher = FloatArray(3).also { it[axis] = sign * Float.MAX_VALUE }
			val predicted = FloatArray(3).also { it[axis] = -sign * Float.MAX_VALUE }
			val value = input(Vector3(teacher[0], teacher[1], teacher[2]), Vector3(predicted[0], predicted[1], predicted[2]))
			assertIs<PositionTemporalPairingResult.Pairable>(PositionTemporalPairing.check(value, teacherEpoch(value), policy))
			val result = assertIs<PositionErrorMeasurementResult.Rejected>(PositionErrorMeasurement.evaluate(value, teacherEpoch(value), policy))
			assertEquals(PositionErrorMeasurementRejectionReason.ERROR_NONFINITE, result.reason)
			assertNull(result.pairingReason)
			assertNull(result.structuralReason)
		}
	}

	@Test fun signedZeroUsesOrdinaryFloatSubtractionBits() {
		val sample = measured(input(Vector3(-0f, 0f, -0f), Vector3(0f, -0f, -0f)))
		assertEquals((-0f - 0f).toRawBits(), sample.errorWorld.x.toRawBits())
		assertEquals((0f - -0f).toRawBits(), sample.errorWorld.y.toRawBits())
		assertEquals((-0f - -0f).toRawBits(), sample.errorWorld.z.toRawBits())
	}

	@Test fun exactSampleIdentityTimeFrameAndFullEpochsAreRetained() {
		val value = input(teacherSequence = Long.MAX_VALUE, predictionSequence = Long.MAX_VALUE - 1)
		val sample = measured(value)
		assertEquals(TrackerPosition.HIP, sample.target)
		assertEquals(PositionBodyReference.HIP_CENTER, sample.bodyReference)
		assertSame(space, sample.coordinateSpace)
		assertEquals(Long.MAX_VALUE, sample.teacherSequence)
		assertEquals(Long.MAX_VALUE - 1, sample.predictionSequence)
		assertEquals(85L, sample.teacherSampleAtNanos)
		assertEquals(80L, sample.predictionInputEarliestAtNanos)
		assertEquals(90L, sample.predictionInputLatestAtNanos)
		assertEquals(95L, sample.predictionGeneratedAtNanos)
		assertEquals(value.nowNanos, sample.evaluatedAtNanos)
		assertEquals(0L, sample.teacherToPredictionInputDistanceNanos)
		assertEquals(PositionTeacherEpoch(origin.sourceId, "main:1", "upstream:2", 5, space, 3,
			PositionBodyReference.HIP_CENTER, mount), sample.teacherEpoch)
		assertEquals(epoch, sample.predictionEpoch)
		assertEquals(3L, sample.assignmentGeneration)
		assertEquals(sample.assignmentGeneration, sample.teacherEpoch.assignmentGeneration)
		assertEquals(sample.assignmentGeneration, sample.predictionEpoch.assignmentGeneration)
	}

	@ParameterizedTest
	@CsvSource("70,10", "80,0", "85,0", "90,0", "100,10")
	fun retainsRealPairingIntervalDistance(teacherAt: Long, distance: Long) {
		assertEquals(distance, measured(input(teacherAt = teacherAt)).teacherToPredictionInputDistanceNanos)
	}

	@Test fun sequenceZeroAndLongTimeExtremesRemainExactFacts() {
		val value = input(teacherAt = Long.MAX_VALUE, earliest = 0, latest = Long.MAX_VALUE,
			generated = Long.MAX_VALUE, now = Long.MAX_VALUE, teacherSequence = 0, predictionSequence = 0)
		val sample = measured(value, pairingPolicy = PositionTemporalPairingPolicy(0, 0, 0, 0))
		assertEquals(0L, sample.teacherSequence)
		assertEquals(0L, sample.predictionSequence)
		assertEquals(0L, sample.predictionInputEarliestAtNanos)
		assertEquals(Long.MAX_VALUE, sample.teacherSampleAtNanos)
		assertEquals(Long.MAX_VALUE, sample.predictionInputLatestAtNanos)
		assertEquals(Long.MAX_VALUE, sample.predictionGeneratedAtNanos)
		assertEquals(Long.MAX_VALUE, sample.evaluatedAtNanos)
	}

	@Test fun repeatedAndOutOfOrderEvaluationIsDeterministicWithoutDedupe() {
		val value = input()
		val first = measured(value)
		measured(input(teacherSequence = 0, predictionSequence = 0))
		rejected(value.copy(prediction = PositionPrediction.unavailable(TrackerPosition.HIP)),
			reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, structural = "prediction_unavailable")
		assertEquals(first, measured(value))
	}

	@Test fun sameEpochNewSamplesProduceDistinctMeasurementFacts() {
		val first = measured()
		val second = measured(input(Vector3(3f, 0f, 0f), teacherAt = 86, earliest = 81, latest = 91,
			generated = 96, now = 101, teacherSequence = 43, predictionSequence = 74))
		assertEquals(first.teacherEpoch, second.teacherEpoch)
		assertEquals(first.predictionEpoch, second.predictionEpoch)
		assertNotEquals(first, second)
		assertEquals(43L, second.teacherSequence)
		assertEquals(74L, second.predictionSequence)
		assertEquals(86L, second.teacherSampleAtNanos)
		assertEquals(Vector3(2f, 0f, 0f), second.errorWorld)
	}

	@ParameterizedTest
	@ValueSource(strings = ["teacher", "input", "generation", "skew"])
	fun pairingTemporalRejectionsPropagateExactly(kind: String) {
		val (p, reason) = when (kind) {
			"teacher" -> policy.copy(maxTeacherAgeNanos = 14) to PositionTemporalPairingRejectionReason.TEACHER_STALE
			"input" -> policy.copy(maxPredictionInputAgeNanos = 9) to PositionTemporalPairingRejectionReason.PREDICTION_INPUT_STALE
			"generation" -> policy.copy(maxPredictionGenerationAgeNanos = 4) to PositionTemporalPairingRejectionReason.PREDICTION_GENERATION_STALE
			else -> policy.copy(maxTeacherToPredictionInputSkewNanos = 9) to PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED
		}
		rejected(input(teacherAt = if (kind == "skew") 70 else 85), pairingPolicy = p, reason = reason)
	}

	@Test fun structuralFailureWinsOverEpochStalenessAndNumericOverflow() {
		val value = input(Vector3(Float.MAX_VALUE, 0f, 0f), Vector3(-Float.MAX_VALUE, 0f, 0f), teacherAt = 0)
		rejected(value.copy(prediction = PositionPrediction.unavailable(TrackerPosition.HIP)),
			expected = teacherEpoch(value).copy(sourceEpoch = "new-main"), pairingPolicy = PositionTemporalPairingPolicy(0, 0, 0, 0),
			reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, structural = "prediction_unavailable")
	}

	@Test fun teacherEpochRejectWinsOverNumericOverflow() {
		val value = input(Vector3(Float.MAX_VALUE, 0f, 0f), Vector3(-Float.MAX_VALUE, 0f, 0f))
		rejected(value, teacherEpoch(value).copy(sourceEpoch = "new-main"), reason = PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH)
	}

	@Test fun nonfiniteTeacherUsesExistingStructuralDiagnostic() {
		rejected(input(Vector3(Float.NaN, 0f, 0f)), reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE,
			structural = "main_position_invalid")
		assertFailsWith<IllegalArgumentException> { prediction(Vector3(Float.POSITIVE_INFINITY, 0f, 0f)) }
	}

	@Test fun oldMainMountRejectsEvenAtEqualPositions() {
		val value = input(zero, zero)
		rejected(value, teacherEpoch(value).copy(mountCalibration = mount.copy(epoch = "fit:2;content:abc")),
			reason = PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH)
	}

	@ParameterizedTest
	@ValueSource(strings = ["body", "fixed", "imu", "hmd-source", "hmd-frame"])
	fun oldPredictionLineageRetainsStructuralEpochMismatch(kind: String) {
		val current = when (kind) {
			"body" -> epoch.copy(bodyModelEpoch = "body:next")
			"fixed" -> epoch.copy(fixedCalibrationEpoch = "fixed:next")
			"imu" -> epoch.copy(imuSourceEpoch = "reconnect:next")
			"hmd-source" -> epoch.copy(hmdSourceEpoch = "hmd:next")
			else -> epoch.copy(hmdCalibrationEpoch = "frame:next")
		}
		// PREDICTION_EPOCH_MISMATCH is defensive/unreachable after existing preflight;
		// do not alter 5U precedence to manufacture that reason. HMD here is synthetic.
		rejected(input(zero, zero).copy(expectedPredictionEpoch = current),
			reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, structural = "prediction_epoch_mismatch")
	}

	@Test fun spaceRevisionAndAssignmentChangeRejectOldPair() {
		val value = input(zero, zero)
		rejected(value.copy(expectedSpace = space.copy(revision = 8)),
			reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, structural = "space_mismatch")
		rejected(value.copy(assignmentGeneration = 4), reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE,
			structural = "prediction_epoch_mismatch")
		val nextEpoch = epoch.copy(assignmentGeneration = 4)
		val progressed = value.copy(assignmentGeneration = 4, expectedPredictionEpoch = nextEpoch,
			prediction = prediction(zero, predictionEpoch = nextEpoch))
		rejected(progressed, teacherEpoch(value), reason = PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH)
	}

	@Test fun futureAndBodyReferenceFailuresPropagateStructuralReasons() {
		rejected(input(teacherAt = 101), reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, structural = "future_sample")
		val mountPrediction = PositionPrediction.available(TrackerPosition.HIP, zero, space, PositionBodyReference.TRACKER_MOUNT,
			prediction().provenance, dependencies)
		rejected(input().copy(prediction = mountPrediction), reason = PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE,
			structural = "body_reference_mismatch")
	}

	@Test fun sourceSnapshotsAreSeparateImmutableVectorsAndInputsAreUnchanged() {
		val value = input()
		val before = value.copy()
		val predicted = value.prediction
		val sample = measured(value)
		assertEquals(before, value)
		assertEquals(Vector3(2f, 0f, 0f), value.mainTeacher.position)
		assertEquals(Vector3(1f, 0f, 0f), predicted.position)
		assertEquals(PositionPredictionProvenance(73, 95, 80, 90, epoch), predicted.provenance)
		assertEquals(dependencies, predicted.dependencies)
		assertEquals(value.mainTeacher.position, sample.teacherPosition)
		assertEquals(predicted.position, sample.predictionPosition)
		assertNotSame(value.mainTeacher.position, sample.teacherPosition)
		assertNotSame(predicted.position, sample.predictionPosition)
		assertTrue(Vector3::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.all { Modifier.isFinal(it.modifiers) })
		assertTrue(Vector3::class.java.methods.none { it.name in setOf("setX", "setY", "setZ") })
	}

	@Test fun boundaryHasNoDetachedPairableOrPersistentStateAndRejectedHasNoNumericSample() {
		assertTrue(PositionErrorMeasurement::class.java.declaredFields.all { Modifier.isStatic(it.modifiers) })
		val evaluate = PositionErrorMeasurement::class.java.declaredMethods.single { it.name == "evaluate" }
		assertEquals(listOf(PositionCorrectionInput::class.java, PositionTeacherEpoch::class.java,
			PositionTemporalPairingPolicy::class.java), evaluate.parameterTypes.toList())
		assertEquals(setOf("reason", "pairingReason", "structuralReason"),
			PositionErrorMeasurementResult.Rejected::class.java.declaredFields.map { it.name }.toSet())
		assertTrue(PositionErrorSample::class.java.declaredFields.all { Modifier.isFinal(it.modifiers) })
		assertFalse(PoseObservation::class.java.isAssignableFrom(PositionErrorSample::class.java))
	}

	@Test fun dormantRealImuPredictorAndCalibratedMainChainMeasuresNonzeroErrorWithoutMutation() {
		val tracker = Tracker(UDPDevice(InetSocketAddress("127.0.0.1", 20000), InetAddress.getLoopbackAddress(), "fixture"),
			101, "physical-hip", trackerPosition = TrackerPosition.HIP, hasPosition = false, hasRotation = true,
			imuType = IMUType.UNKNOWN, allowReset = true, allowMounting = true, allowFiltering = false, trackRotDirection = false)
		tracker.status = TrackerStatus.OK
		tracker.resetsHandler.mountingOrientation = Quaternion.rotationAroundZAxis(.2f)
		tracker.setRotation(Quaternion.rotationAroundXAxis(.4f))
		val capture = SlimeIndependentImuOrientationCapture { tracker.correctionOrientationSample()!!.receivedAtSystemNanos + 10 }
		val imu = assertIs<SlimeRawImuInputResult.Available>(SlimeRawImuProductionBoundary(capture).adapt(tracker, 100,
			SlimeRawImuCoordinateSpaceBinding("slime:physical-hip", space, true))).input
		val body = assertIs<HipBodyModelSnapshotResult.Available>(HipBodyModelSnapshot.create(.1f, .2f, .3f, .4f, .5f, .6f)).snapshot
		val hmd = RawHmdPoseInput(RawSourceIdentity("synthetic:hmd", RawSourceKind.RAW_HMD, isHmd = true),
			Vector3(1f, 4f, -2f), Quaternion.rotationAroundXAxis(.3f), space,
			ObservationSampleProvenance(1001, 80, "hmd:1", "frame:1", 2, space))
		val fixed = syntheticHeadAnchorCalibration(hmd.source.sourceId, body.identity.modelId, offset = Vector3(.2f, .3f, -.1f))
		val source = MainDecoupledHipInput(hmd, imu, body, fixed, space, 3, 100, 73)
		val predictor = PureMainDecoupledHipPredictor(MainDecoupledHipPredictorPolicy(10, 20, 20))
		val predicted = predictor.predict(source)
		assertEquals(PredictionValidity.AVAILABLE, predicted.validity)
		val mountSnapshot = assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(MainTrackerMountCalibrationSnapshot.create(
			"main-mount", "fit:1", origin.sourceId, Vector3(.1f, -.2f, .3f))).snapshot
		val rawMain = PoseObservation(origin.sourceId, TrackerPosition.HIP, 100, position = Vector3(.5f, 1f, -.5f),
			rotation = Quaternion.rotationAroundZAxis(.5f), provenance = ObservationSampleProvenance(42, 85, "main:1", "upstream:2", 5, space))
		val teacher = assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(rawMain, origin, mountSnapshot)).teacher
		val comparison = PositionCorrectionInput(teacher, predicted, space, source.epoch(), 3, 100)
		val expected = PositionTeacherEpoch(origin.sourceId, "main:1", "upstream:2", 5, space, 3,
			PositionBodyReference.HIP_CENTER, mountSnapshot.identity)
		val sample = measured(comparison, expected, PositionTemporalPairingPolicy(0, 20, 20, 0))
		assertNotEquals(zero, sample.errorWorld)
		assertEquals(teacher.position - predicted.position!!, sample.errorWorld)
		assertEquals(80L, sample.predictionInputEarliestAtNanos)
		assertEquals(90L, sample.predictionInputLatestAtNanos)
		assertEquals(expected, sample.teacherEpoch)
		assertEquals(source.epoch(), sample.predictionEpoch)
		assertSame(rawMain.provenance, teacher.provenance)
		assertEquals(dependencies, predicted.dependencies)
		assertEquals(predicted.position, predictor.predict(source).position)
		assertEquals(predicted.provenance, predictor.predict(source).provenance)
		assertEquals(sample, measured(comparison, expected, PositionTemporalPairingPolicy(0, 20, 20, 0)))
	}
}
