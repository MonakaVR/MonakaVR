package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

class HipRotationCorrectionTests {
	private val space = CoordinateSpace("test-world", "rh_y_up_neg_z_forward", 2)
	private val mainId = "mtp:main"
	private val imuId = "slime:imu"
	private val identity = Quaternion.IDENTITY
	private val halfYaw = Quaternion.rotationAroundYAxis(.5f)
	private fun tuning(maxResidual: Double = 3.2, maxRate: Double = 1000.0, maxDt: Long = 100_000_000) =
		RotationCorrectionTuning(2_000_000, 1_000_000, .0001, .0001, maxResidual, .1, maxRate, 1, maxDt, 100_000_000)
	private fun controller(t: RotationCorrectionTuning = tuning(), mainMount: Quaternion = identity,
		imuMount: Quaternion = identity) = HipRotationCorrection(RotationCorrectionFrames(mainMount, imuMount, space, true), t)
	private fun sample(id: String, seq: Long, at: Long, q: Quaternion?, full: Boolean = false,
		sourceEpoch: String = "session", calibrationEpoch: String = "mount", mapping: Long = 1,
		spaceValue: CoordinateSpace = space, quality: ObservationQuality = ObservationQuality.TRACKED) = PoseObservation(
		id, TrackerPosition.HIP, at, position = if (full) Vector3(1f, 2f, 3f) else null,
		rotation = q, positionQuality = if (full) quality else ObservationQuality.UNAVAILABLE,
		rotationQuality = if (q == null) ObservationQuality.UNAVAILABLE else quality,
		modality = if (full) TrackingModality.FULL else if (q != null) TrackingModality.ROTATION_ONLY else TrackingModality.NONE,
		provenance = ObservationSampleProvenance(seq, at, sourceEpoch, calibrationEpoch, mapping, spaceValue),
		correctionRotation = q,
	)
	private fun step(c: HipRotationCorrection, seq: Long, at: Long, qMain: Quaternion = halfYaw,
		qImu: Quaternion = identity, full: Boolean = true, owner: String = mainId): EffectiveConstraint? =
		c.update(sample(mainId, seq, at, qMain, full), sample(imuId, seq, at, qImu), owner, at, 1, space)
	private fun ready(c: HipRotationCorrection) {
		step(c, 1, 1_000_000)
		step(c, 2, 4_000_000)
		assertTrue(c.ready)
		assertEquals(RotationCorrectionState.TRACKING, c.state)
	}
	private fun sameRotation(a: Quaternion, b: Quaternion) =
		assertTrue(abs(a.unit().dot(b.unit())) > .9999f, "expected equivalent orientations: $a and $b")

	@Test fun fullLearningIsIndependentOfResolverOwnerAndMainIsNeverCorrected() {
		val c = controller()
		assertNull(step(c, 1, 1_000_000))
		assertNull(step(c, 2, 4_000_000))
		assertTrue(c.ready)
		sameRotation(halfYaw, c.correction)
		assertNull(step(c, 3, 5_000_000, owner = mainId))
	}

	@Test fun sameAcceptedSampleAndServerTicksNeverAdvanceLearningOrRecovery() {
		val c = controller()
		step(c, 1, 1_000_000)
		repeat(10) { c.update(sample(mainId, 1, 1_000_000, halfYaw, true),
			sample(imuId, 1, 1_000_000, identity), mainId, 50_000_000L + it, 1, space) }
		assertFalse(c.ready)
		assertEquals(identity, c.correction)
		step(c, 2, 4_000_000)
		assertTrue(c.ready)
		assertTrue((c.rejections["same_sample"] ?: 0) >= 10)
	}

	@Test fun bothSourcesMustAdvanceAndRollbackIsRejected() {
		val c = controller()
		step(c, 1, 1_000_000)
		c.update(sample(mainId, 2, 2_000_000, halfYaw, true), sample(imuId, 1, 1_000_000, identity),
			mainId, 2_000_000, 1, space)
		assertFalse(c.ready)
		step(c, 2, 4_000_000)
		assertTrue(c.ready)
		c.update(sample(mainId, 1, 5_000_000, halfYaw, true), sample(imuId, 1, 5_000_000, identity),
			mainId, 5_000_000, 1, space)
		assertEquals(1, c.rejections["sequence_rollback"])
		assertEquals(4_000_000, c.lastLearnedAtNanos)
	}

	@Test fun fallbackOwnerGetsExactlyOneWorldSideCorrectionInDegradedAndNone() {
		val c = controller()
		ready(c)
		val degraded = step(c, 3, 5_000_000, full = false, owner = imuId)
		assertEquals(RotationCorrectionState.DEGRADED, c.state)
		assertNotNull(degraded?.rotation)
		sameRotation(halfYaw, degraded.rotation!!.value)
		assertEquals(imuId, degraded.rotation.sourceId)
		val none = c.update(null, sample(imuId, 3, 5_000_000, identity), imuId, 6_000_000, 1, space)
		assertEquals(RotationCorrectionState.IMU_ONLY, c.state)
		assertNotNull(none?.rotation)
		sameRotation(halfYaw, none.rotation!!.value)
		assertEquals(halfYaw, c.correction) // loss did not reset to identity
		assertNull(c.update(null, sample(imuId, 3, 5_000_000, identity, quality = ObservationQuality.STALE),
			imuId, 6_000_000, 1, space))
	}

	@Test fun rawMainRemainsTeacherWhenResolverSelectedFallback() {
		val c = controller()
		ready(c)
		val newMain = Quaternion.rotationAroundYAxis(.7f)
		val corrected = step(c, 3, 5_000_000, qMain = newMain, full = false, owner = imuId)
		assertNotNull(corrected?.rotation)
		sameRotation(newMain, corrected.rotation!!.value)
		assertTrue(c.residualRadians!! > .1) // Main vs corrected IMU, not IMU vs itself.
	}

	@Test fun sourceSessionMappingSpaceAndCalibrationEpochsInvalidateReadiness() {
		for (change in listOf("source", "session", "mapping", "space", "calibration", "assignment")) {
			val c = controller()
			ready(c)
			val baseMain = sample(mainId, 3, 5_000_000, halfYaw, true)
			val baseImu = sample(imuId, 3, 5_000_000, identity)
			val main = when (change) {
				"source" -> baseMain.copy(sourceId = "mtp:new-main")
				"session" -> baseMain.copy(provenance = baseMain.provenance!!.copy(sourceEpoch = "new-session"))
				"mapping" -> baseMain.copy(provenance = baseMain.provenance!!.copy(mappingRevision = 2))
				"space" -> baseMain.copy(provenance = baseMain.provenance!!.copy(space = space.copy(revision = 3)))
				else -> baseMain
			}
			val imu = if (change == "calibration") baseImu.copy(provenance = baseImu.provenance!!.copy(calibrationEpoch = "new-mount")) else baseImu
			assertNull(c.update(main, imu, imu.sourceId, 5_000_000, if (change == "assignment") 2 else 1, space), change)
			assertFalse(c.ready, change)
			assertEquals(identity, c.correction, change)
		}
	}

	@Test fun quaternionSignMountingOrderAndNormalizationAreExplicit() {
		val mount = Quaternion.rotationAroundYAxis(.2f)
		val c = controller(mainMount = mount)
		step(c, 1, 1_000_000, qMain = Quaternion.rotationAroundYAxis(.3f))
		step(c, 2, 4_000_000, qMain = -Quaternion.rotationAroundYAxis(.3f))
		assertTrue(c.ready)
		sameRotation(Quaternion.rotationAroundYAxis(.5f), c.correction)
		assertTrue(abs(c.correction.lenSq() - 1f) < .0001f)
		val out = step(c, 3, 5_000_000, qMain = Quaternion.rotationAroundYAxis(.3f), full = false, owner = imuId)
		sameRotation(Quaternion.rotationAroundYAxis(.5f), out!!.rotation!!.value)
	}

	@Test fun invalidQuaternionOutlierAndRateLimitFailClosed() {
		for (bad in listOf(Quaternion(0f, 0f, 0f, 0f), Quaternion(Float.NaN, 0f, 0f, 0f),
			Quaternion(Float.POSITIVE_INFINITY, 0f, 0f, 0f))) {
			val c = controller()
			c.update(sample(mainId, 1, 1_000_000, bad, true), sample(imuId, 1, 1_000_000, identity),
				mainId, 1_000_000, 1, space)
			assertFalse(c.ready)
			assertEquals(1, c.rejections["rotation_invalid"])
		}
		val outlier = controller(tuning(maxResidual = .1))
		step(outlier, 1, 1_000_000)
		step(outlier, 2, 4_000_000)
		assertEquals(identity, outlier.correction)
		assertEquals(1, outlier.rejections["residual_outlier"])
		val bounded = controller(tuning(maxRate = 1.0))
		step(bounded, 1, 1_000_000)
		step(bounded, 2, 4_000_000)
		assertFalse(bounded.ready)
		assertTrue(abs(bounded.correction.y) < .01f)
	}

	@Test fun nonpositiveAndLargeDtCannotCreateRecovery() {
		val c = controller(tuning(maxDt = 5_000_000))
		step(c, 1, 1_000_000)
		step(c, 2, 1_000_000)
		assertEquals(1, c.rejections["timestamp_rollback"])
		assertFalse(c.ready)
		step(c, 3, 20_000_000)
		assertEquals(1, c.rejections["large_dt"])
		assertFalse(c.ready)
		assertEquals(identity, c.correction)
	}

	@Test fun lossDuringReacquisitionKeepsNumericalCorrectionButNotPrematureReadiness() {
		val c = controller(tuning(maxRate = 1.0))
		step(c, 1, 1_000_000)
		step(c, 2, 4_000_000)
		assertFalse(c.ready)
		val held = c.correction
		c.update(null, sample(imuId, 2, 4_000_000, identity), imuId, 5_000_000, 1, space)
		assertEquals(RotationCorrectionState.IMU_ONLY, c.state)
		assertEquals(held, c.correction)
		step(c, 3, 6_000_000)
		assertEquals(RotationCorrectionState.REACQUIRING, c.state)
		assertFalse(c.ready)
	}

	@Test fun perSourceTimestampRollbackAndFeedbackAreRejected() {
		val c = controller()
		step(c, 1, 1_000_000)
		val main = sample(mainId, 2, 900_000, halfYaw, true)
		val imu = sample(imuId, 2, 1_800_000, identity)
		assertNull(c.update(main, imu, imuId, 1_800_000, 1, space))
		assertEquals(1, c.rejections["timestamp_rollback"])
		assertNull(c.lastLearnedAtNanos)
		assertNull(c.update(sample("monaka-direct:hip", 2, 2_000_000, halfYaw, true), imu,
			imuId, 2_000_000, 1, space))
		assertEquals(1, c.rejections["feedback_excluded"])
	}

	@Test fun imuReconnectWhileMainAbsentRevokesOldCorrection() {
		val c = controller()
		ready(c)
		val oldImu = sample(imuId, 3, 5_000_000, identity)
		assertNotNull(c.update(null, oldImu, imuId, 5_000_000, 1, space)?.rotation)
		val reconnected = oldImu.copy(provenance = oldImu.provenance!!.copy(sourceEpoch = "reconnected"))
		assertNull(c.update(null, reconnected, imuId, 6_000_000, 1, space))
		assertFalse(c.ready)
	}

	@Test fun nonCommutingQuaternionOrderUsesMainTimesInverseImuAndWorldLeftCorrection() {
		val main = Quaternion.rotationAroundXAxis(.3f)
		val imu = Quaternion.rotationAroundYAxis(.4f)
		val c = controller()
		step(c, 1, 1_000_000, main, imu)
		step(c, 2, 4_000_000, main, imu)
		assertTrue(c.ready)
		sameRotation(main, (c.correction * imu).unit())
		assertTrue(abs((imu * c.correction).unit().dot(main.unit())) < .999f)
	}

	@Test fun readyCorrectionDoesNotTeachOrApplyStaleOrFutureImuButResumesOnFreshSample() {
		val c = controller()
		ready(c)
		val learned = c.correction
		val learnedAt = c.lastLearnedAtNanos
		assertNull(c.update(sample(mainId, 3, 200_000_000, halfYaw, true),
			sample(imuId, 2, 4_000_000, identity), imuId, 200_000_000, 1, space))
		assertEquals(learnedAt, c.lastLearnedAtNanos)
		assertEquals(learned, c.correction)
		assertTrue(c.ready)
		assertEquals(1, c.rejections["imu_sample_stale"])
		assertNull(c.update(null, sample(imuId, 3, 202_000_000, identity), imuId, 201_000_000, 1, space))
		assertEquals(2, c.rejections["imu_sample_stale"])
		val resumed = c.update(null, sample(imuId, 3, 201_000_000, identity), imuId, 201_000_000, 1, space)
		assertNotNull(resumed?.rotation)
		sameRotation(learned, resumed.rotation!!.value)
	}
}
