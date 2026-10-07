package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.processor.config.SkeletonConfigToggles
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.PI
import kotlin.test.*

/** Synthetic HMD only; actual legacy propagation and dormant IMU/teacher/pairing boundaries. */
class PureMainDecoupledHipPredictorTests {
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val zero = Vector3(0f, 0f, 0f)
	private val position = Vector3(1f, 4f, -2f)
	private val geometry = listOf(.125f, .25f, .375f, .5f, .625f, .75f)
	private val x90 = Quaternion.rotationAroundXAxis((PI / 2).toFloat())
	private val y90 = Quaternion.rotationAroundYAxis((PI / 2).toFloat())
	private val z90 = Quaternion.rotationAroundZAxis((PI / 2).toFloat())
	private val policy = MainDecoupledHipPredictorPolicy(10, 20, 20)
	private val predictor = PureMainDecoupledHipPredictor(policy)
	private val dependencies = setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
		PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION)
	private fun body(values: List<Float> = geometry) = assertIs<HipBodyModelSnapshotResult.Available>(
		HipBodyModelSnapshot.create(values[0], values[1], values[2], values[3], values[4], values[5])).snapshot
	private fun input(hmdQ: Quaternion = Quaternion.IDENTITY, imuQ: Quaternion = Quaternion.IDENTITY,
		offset: Vector3 = zero, fixedQ: Quaternion = Quaternion.IDENTITY, values: List<Float> = geometry,
		hmdAt: Long = 80, imuAt: Long = 90, now: Long = 100, sequence: Long = 7): MainDecoupledHipInput {
		val model = body(values)
		return MainDecoupledHipInput(
			RawHmdPoseInput(RawSourceIdentity("synthetic:hmd", RawSourceKind.RAW_HMD, isHmd = true),
				position, hmdQ, space, ObservationSampleProvenance(1001, hmdAt, "hmd:1", "provider:1", 2, space)),
			RawImuOrientationInput(RawSourceIdentity("synthetic:imu", RawSourceKind.RAW_IMU), imuQ, space,
				ObservationSampleProvenance(3009, imuAt, "imu:1", "mount:1", null, space)),
			model, syntheticHeadAnchorCalibration("synthetic:hmd", model.identity.modelId,
				offset = offset, orientation = fixedQ), space, 3, now, sequence,
		)
	}
	private fun available(source: MainDecoupledHipInput = input(), p: MainDecoupledHipPredictorPolicy = policy): PositionPrediction {
		val result = PureMainDecoupledHipPredictor(p).predict(source)
		assertEquals(PredictionValidity.AVAILABLE, result.validity)
		assertNotNull(result.position)
		return result
	}
	private fun unavailable(source: MainDecoupledHipInput, p: MainDecoupledHipPredictorPolicy = policy) {
		val result = PureMainDecoupledHipPredictor(p).predict(source)
		assertEquals(PredictionValidity.UNAVAILABLE, result.validity)
		assertNull(result.position)
		assertNull(result.provenance)
		assertNull(result.bodyReference)
		assertEquals(emptySet(), result.dependencies)
		assertEquals(TrackerPosition.HIP, result.target)
		assertSame(source.space, result.space)
	}
	private fun near(expected: Vector3, actual: Vector3?, tolerance: Float = 2e-5f) {
		assertNotNull(actual)
		assertEquals(expected.x, actual.x, tolerance)
		assertEquals(expected.y, actual.y, tolerance)
		assertEquals(expected.z, actual.z, tolerance)
	}

	@Test fun identityBaselineUsesAllSixSignedVectors() {
		near(Vector3(1f, 1.5f, -1.875f), available().position)
	}

	@Test fun fixedTranslationUsesRawHmdAxesBeforeHeadCalibration() {
		near(Vector3(2f, -1.375f, -.25f), available(input(hmdQ = x90,
			offset = Vector3(1f, 2f, 3f))).position)
	}

	@Test fun noncommutingHeadCompositionHasAnalyticalGoldenAndRejectsReverseOrder() {
		val source = input(hmdQ = x90, offset = Vector3(1f, 2f, 3f), fixedQ = y90)
		near(Vector3(2.125f, -1.25f, -.25f), available(source).position)
		// Reverse order would point HEAD -Y and NECK -X, yielding this distinct vector.
		val reverse = Vector3(1.75f, -1.375f, 0f)
		assertTrue((available(source).position!! - reverse).len() > .1f)
	}

	@Test fun fixedOrientationChangesOnlyHeadAndNeckGeometry() {
		val before = available().position!!
		val after = available(input(fixedQ = x90)).position!!
		near(Vector3(0f, .125f, -.375f), after - before)
		val torsoOnly = input(values = listOf(0f, 0f, .375f, .5f, .625f, .75f))
		near(available(torsoOnly).position!!, available(torsoOnly.copy(fixedCalibration =
			syntheticHeadAnchorCalibration("synthetic:hmd", torsoOnly.bodyModel.identity.modelId, orientation = x90))).position)
	}

	@Test fun hmdPitchOwnsFixedTranslationHeadShiftAndNeckOnly() {
		val source = input(offset = Vector3(1f, 2f, 3f))
		val changed = source.copy(rawHmd = source.rawHmd.copy(orientation = x90))
		// Anchor delta (0,-5,-1) + HEAD/NECK delta (0,.125,-.375).
		near(Vector3(0f, -4.875f, -1.375f), available(changed).position!! - available(source).position!!)
	}

	@Test fun imuRollOwnsEverySpineSegmentAndLeavesHeadAnchorUntouched() {
		near(Vector3(3.25f, 3.75f, -1.875f), available(input(imuQ = z90)).position)
		val noSpine = input(values = listOf(.125f, .25f, 0f, 0f, 0f, 0f), offset = Vector3(1f, 2f, 3f), fixedQ = x90)
		near(available(noSpine).position!!, available(noSpine.copy(rawImu = noSpine.rawImu.copy(orientation = z90))).position)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun everyGeometryFieldParticipatesWithItsOwner(index: Int) {
		val source = input(hmdQ = x90, imuQ = z90)
		val changed = source.copy(bodyModel = body(geometry.toMutableList().also { it[index] += .125f }))
		val expected = when (index) {
			0 -> Vector3(0f, -.125f, 0f)
			1 -> Vector3(0f, 0f, -.125f)
			else -> Vector3(.125f, 0f, 0f)
		}
		near(expected, available(changed).position!! - available(source).position!!)
		assertNotEquals(source.epoch(), changed.epoch())
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun positiveZeroNegativeAndSignedZeroAreNeverClamped(index: Int) {
		for (value in listOf(.5f, 0f, -.5f, -0.0f)) {
			val source = input(values = List(6) { if (it == index) value else 0f })
			near(position + if (index == 0) Vector3(0f, 0f, value) else Vector3(0f, -value, 0f), available(source).position)
		}
		val plus = input(values = List(6) { 0f })
		val minus = input(values = List(6) { if (it == index) -0.0f else 0f })
		near(available(plus).position!!, available(minus).position)
		assertNotEquals(available(plus).provenance!!.epoch, available(minus).provenance!!.epoch)
	}

	@ParameterizedTest @ValueSource(floats = [2f, -3f, 1e-4f, 1e18f])
	fun nonunitAndSignEquivalentRawQuaternionsNormalizeWithoutMutation(scale: Float) {
		val source = input(hmdQ = x90, imuQ = z90, fixedQ = y90, offset = Vector3(.2f, -.3f, .4f))
		fun scaled(q: Quaternion) = Quaternion(q.w * scale, q.x * scale, q.y * scale, q.z * scale)
		val hmd = source.rawHmd.copy(orientation = scaled(source.rawHmd.orientation))
		val imu = source.rawImu.copy(orientation = scaled(source.rawImu.orientation))
		val changed = source.copy(rawHmd = hmd, rawImu = imu)
		near(available(source).position!!, available(changed).position)
		assertSame(hmd, changed.rawHmd)
		assertSame(imu, changed.rawImu)
		assertEquals(scaled(x90), hmd.orientation)
		assertEquals(scaled(z90), imu.orientation)
	}

	@Test fun sequenceIsCallerOwnedAndIndependentOfRawDomainsEpochAndMath() {
		val source = input(sequence = 0)
		val first = available(source)
		val next = available(source.copy(predictionSequence = Long.MAX_VALUE))
		assertEquals(0L, first.provenance!!.predictionSequence)
		assertEquals(Long.MAX_VALUE, next.provenance!!.predictionSequence)
		assertEquals(first.provenance.epoch, next.provenance.epoch)
		assertEquals(first.position, next.position)
		assertEquals(first.provenance, available(source).provenance)
		assertFailsWith<IllegalArgumentException> { source.copy(predictionSequence = -1) }
		assertTrue(PositionPredictionEpoch::class.java.declaredFields.none { it.name.contains("sequence", true) })
	}

	@Test fun rawSequenceProgressionDoesNotAllocatePredictionSequenceOrEpoch() {
		val source = input()
		val changed = source.copy(rawHmd = source.rawHmd.copy(provenance = source.rawHmd.provenance.copy(sequence = 99999)),
			rawImu = source.rawImu.copy(provenance = source.rawImu.provenance.copy(sequence = 1)))
		assertEquals(available(source).provenance, available(changed).provenance)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun supportWindowIsExactMinMaxAndGenerationUsesCallerTime(index: Int) {
		val source = when (index) { 0 -> input(); 1 -> input(hmdAt = 90, imuAt = 80); else -> input(hmdAt = 85, imuAt = 85) }
		val provenance = available(source).provenance!!
		assertEquals(minOf(source.rawHmd.provenance.sampleAtNanos, source.rawImu.provenance.sampleAtNanos), provenance.inputEarliestAtNanos)
		assertEquals(maxOf(source.rawHmd.provenance.sampleAtNanos, source.rawImu.provenance.sampleAtNanos), provenance.inputLatestAtNanos)
		assertEquals(source.nowNanos, provenance.generatedAtNanos)
		assertEquals(source.epoch(), provenance.epoch)
	}

	@Test fun exactOutputContractIsStructurallyEligibleWithoutFeedback() {
		val source = input()
		val result = available(source)
		assertEquals(TrackerPosition.HIP, result.target)
		assertEquals(PositionBodyReference.HIP_CENTER, result.bodyReference)
		assertSame(source.space, result.space)
		assertEquals(dependencies, result.dependencies)
		assertTrue(result.dependencies.none { it in PositionCorrectionTeacherEligibility.forbiddenDependencies })
		assertTrue(PositionCorrectionTeacherEligibility.check(result).eligibleForPairing)
		assertFalse(PoseObservation::class.java.isInstance(result))
	}

	@Test fun individualHmdAgeBoundaryDoesNotUseFreshImuToHideOldHmd() {
		val p = MainDecoupledHipPredictorPolicy(100, 20, 20)
		available(input(hmdAt = 80, imuAt = 100), p)
		unavailable(input(hmdAt = 79, imuAt = 100), p)
	}
	@Test fun individualImuAgeBoundaryDoesNotUseFreshHmdToHideOldImu() {
		val p = MainDecoupledHipPredictorPolicy(100, 20, 20)
		available(input(hmdAt = 100, imuAt = 80), p)
		unavailable(input(hmdAt = 100, imuAt = 79), p)
	}
	@ParameterizedTest @ValueSource(booleans = [false, true])
	fun skewBoundaryIsInclusiveInBothOrders(reverse: Boolean) {
		fun sample(early: Long) = if (reverse) input(hmdAt = 90, imuAt = early) else input(hmdAt = early, imuAt = 90)
		available(sample(80), policy.copy(maxHmdSampleAgeNanos = 100, maxImuSampleAgeNanos = 100))
		unavailable(sample(79), policy.copy(maxHmdSampleAgeNanos = 100, maxImuSampleAgeNanos = 100))
	}
	@Test fun zeroPolicyRequiresBothSamplesAtNow() {
		val exact = MainDecoupledHipPredictorPolicy(0, 0, 0)
		available(input(hmdAt = 100, imuAt = 100), exact)
		unavailable(input(hmdAt = 99, imuAt = 100), exact)
		unavailable(input(hmdAt = 100, imuAt = 99), exact)
		unavailable(input(hmdAt = 99, imuAt = 99), exact)
		val skewOnly = MainDecoupledHipPredictorPolicy(0, 100, 100)
		available(input(hmdAt = 99, imuAt = 99), skewOnly)
		unavailable(input(hmdAt = 99, imuAt = 100), skewOnly)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun negativePolicyLimitsRejectAndNoDefaultsExist(index: Int) {
		val limits = MutableList(3) { 0L }.also { it[index] = -1 }
		assertFailsWith<IllegalArgumentException> { MainDecoupledHipPredictorPolicy(limits[0], limits[1], limits[2]) }
		assertTrue(MainDecoupledHipPredictorPolicy::class.java.constructors.all { it.parameterCount == 3 })
	}
	@ParameterizedTest @ValueSource(booleans = [false, true])
	fun futureSamplesRejectAtInputConstructionWithoutReflection(imuFuture: Boolean) {
		assertFailsWith<IllegalArgumentException> { input(hmdAt = if (imuFuture) 100 else 101, imuAt = if (imuFuture) 101 else 100) }
	}
	@ParameterizedTest @ValueSource(booleans = [false, true])
	fun longBoundariesDoNotOverflowAgeOrSkew(reverse: Boolean) {
		val all = MainDecoupledHipPredictorPolicy(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
		val source = if (reverse) input(hmdAt = Long.MAX_VALUE, imuAt = 0, now = Long.MAX_VALUE)
			else input(hmdAt = 0, imuAt = Long.MAX_VALUE, now = Long.MAX_VALUE)
		val result = available(source, all).provenance!!
		assertEquals(0L, result.inputEarliestAtNanos)
		assertEquals(Long.MAX_VALUE, result.inputLatestAtNanos)
		unavailable(source, all.copy(maxInputSkewNanos = Long.MAX_VALUE - 1))
		unavailable(source, if (reverse) all.copy(maxImuSampleAgeNanos = Long.MAX_VALUE - 1)
			else all.copy(maxHmdSampleAgeNanos = Long.MAX_VALUE - 1))
		available(input(hmdAt = 0, imuAt = 0, now = 0), MainDecoupledHipPredictorPolicy(0, 0, 0))
		available(input(hmdAt = Long.MAX_VALUE, imuAt = Long.MAX_VALUE, now = Long.MAX_VALUE), MainDecoupledHipPredictorPolicy(0, 0, 0))
	}

	@Test fun anchorTranslationOverflowFailsClosed() {
		val source = input(offset = Vector3(Float.MAX_VALUE, 0f, 0f))
		unavailable(source.copy(rawHmd = source.rawHmd.copy(position = Vector3(Float.MAX_VALUE, 0f, 0f))))
	}
	@Test fun chainAccumulationOverflowFailsClosedEvenIfLaterTermsWouldCancel() {
		unavailable(input(values = listOf(0f, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 0f, 0f)))
	}
	@Test fun rotatedVectorOverflowFailsClosed() {
		// sandwich on Float.MAX_VALUE can overflow intermediate quaternion products with a tilted orientation.
		val source = input(offset = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE), hmdQ =
			Quaternion.rotationAroundZAxis((PI / 4).toFloat()))
		unavailable(source)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
	fun legacyCentralHipTailGoldenEquivalence(index: Int) {
		val source = when (index) {
			0 -> input()
			1 -> input(hmdQ = x90)
			2 -> input(imuQ = z90)
			3 -> input(hmdQ = x90, fixedQ = y90, offset = Vector3(.25f, -.5f, .75f), imuQ = z90)
			4 -> input(hmdQ = Quaternion.rotationAroundZAxis(.37f) * Quaternion.rotationAroundXAxis(-.62f),
				fixedQ = Quaternion.rotationAroundYAxis(.46f), imuQ = Quaternion.rotationAroundXAxis(.82f) * Quaternion.rotationAroundZAxis(-.3f))
			5 -> input(hmdQ = x90, imuQ = z90, values = geometry.map { -it })
			6 -> input(hmdQ = z90, imuQ = x90, values = listOf(-.125f, 0f, -.375f, .5f, -0.0f, .75f))
			else -> input(values = List(6) { -0.0f })
		}
		val qWH = source.rawHmd.orientation.unit()
		val fixed = source.fixedCalibration
		val head = legacyTracker(1, TrackerPosition.HEAD, true).also {
			it.position = source.rawHmd.position + qWH.sandwich(fixed.hmdToHeadAnchorLocalOffset)
			it.setRotation((qWH * fixed.hmdToHeadAnchorOrientation).unit())
		}
		val hip = legacyTracker(2, TrackerPosition.HIP, false).also { it.setRotation(source.rawImu.orientation.unit()) }
		val manager = HumanPoseManager(listOf(head, hip))
		for (toggle in listOf(SkeletonConfigToggles.EXTENDED_SPINE_MODEL, SkeletonConfigToggles.EXTENDED_PELVIS_MODEL,
			SkeletonConfigToggles.EXTENDED_KNEE_MODEL, SkeletonConfigToggles.ENFORCE_CONSTRAINTS,
			SkeletonConfigToggles.CORRECT_CONSTRAINTS, SkeletonConfigToggles.FLOOR_CLIP,
			SkeletonConfigToggles.SKATING_CORRECTION, SkeletonConfigToggles.FOOT_PLANT,
			SkeletonConfigToggles.TOE_SNAP, SkeletonConfigToggles.SELF_LOCALIZATION)) manager.setToggle(toggle, false)
		val skeleton = manager.skeleton
		skeleton.setIKSolverEnabled(false)
		val model = source.bodyModel
		val fields = listOf(model.headShift, model.neckLength, model.upperChestLength, model.chestLength, model.waistLength, model.hipLength)
		val offsets = listOf(SkeletonConfigOffsets.HEAD, SkeletonConfigOffsets.NECK, SkeletonConfigOffsets.UPPER_CHEST,
			SkeletonConfigOffsets.CHEST, SkeletonConfigOffsets.WAIST, SkeletonConfigOffsets.HIP)
		for ((offset, value) in offsets.zip(fields)) manager.setOffset(offset, value)
		// Deliberately nonzero virtual placement: it must not affect the central-chain golden.
		manager.setOffset(SkeletonConfigOffsets.HIP_OFFSET, .43f)
		manager.setOffset(SkeletonConfigOffsets.SKELETON_OFFSET, .29f)
		assertTrue(skeleton.hasSpineTracker)
		assertNull(skeleton.neckTracker)
		assertNull(skeleton.upperChestTracker)
		assertNull(skeleton.chestTracker)
		assertNull(skeleton.waistTracker)
		assertFalse(skeleton.hasKneeTrackers)
		assertEquals(source.rawImu.orientation.unit(), hip.getRotation())
		skeleton.updatePose()
		near(skeleton.hipBone.getTailPosition(), available(source).position)
	}
	private fun legacyTracker(id: Int, target: TrackerPosition, hmd: Boolean) = Tracker(
		device = null, id = id, name = "golden:$target", trackerPosition = target,
		hasPosition = hmd, hasRotation = true, isHmd = hmd,
		allowReset = false, allowMounting = false, allowFiltering = false, trackRotDirection = false,
	).also { it.status = TrackerStatus.OK }

	@Test fun dormantProductionPredictorToCalibratedTeacherToTemporalPairing() {
		val tracker = Tracker(UDPDevice(InetSocketAddress("127.0.0.1", 20000), InetAddress.getLoopbackAddress(), "fixture"),
			101, "physical-hip", trackerPosition = TrackerPosition.HIP, hasPosition = false, hasRotation = true,
			imuType = IMUType.UNKNOWN, allowReset = true, allowMounting = true, allowFiltering = false, trackRotDirection = false)
		tracker.status = TrackerStatus.OK
		tracker.resetsHandler.mountingOrientation = Quaternion.rotationAroundZAxis(.2f)
		tracker.setRotation(Quaternion.rotationAroundXAxis(.4f))
		val capture = SlimeIndependentImuOrientationCapture { tracker.correctionOrientationSample()!!.receivedAtSystemNanos + 10 }
		val rawImu = assertIs<SlimeRawImuInputResult.Available>(SlimeRawImuProductionBoundary(capture).adapt(tracker, 100,
			SlimeRawImuCoordinateSpaceBinding("slime:physical-hip", space, true))).input
		val source = input(hmdQ = x90, fixedQ = y90, offset = Vector3(.2f, .3f, -.1f)).copy(rawImu = rawImu)
		val prediction = predictor.predict(source)
		assertEquals(PredictionValidity.AVAILABLE, prediction.validity)
		val origin = RawSourceIdentity("mtp:main", RawSourceKind.RAW_BACKEND)
		val mount = assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(MainTrackerMountCalibrationSnapshot.create(
			"main-mount", "fit:1", origin.sourceId, Vector3(.1f, -.2f, .3f))).snapshot
		// Deliberately different position: Pairable proves compatibility, not position equality or learning.
		val rawMain = PoseObservation(origin.sourceId, TrackerPosition.HIP, 100, position = Vector3(.5f, 1f, -.5f),
			rotation = z90, provenance = ObservationSampleProvenance(42, 85, "main:1", "upstream:1", 2, space))
		val teacher = assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(rawMain, origin, mount)).teacher
		val comparison = PositionCorrectionInput(teacher, prediction, space, source.epoch(), 3, 100)
		assertTrue(PositionCorrectionTeacherEligibility.check(prediction).eligibleForPairing)
		assertTrue(PositionCorrectionTeacherEligibility.check(comparison).eligibleForPairing)
		val expected = PositionTeacherEpoch(origin.sourceId, "main:1", "upstream:1", 2, space, 3,
			PositionBodyReference.HIP_CENTER, mount.identity)
		val paired = assertIs<PositionTemporalPairingResult.Pairable>(PositionTemporalPairing.check(
			comparison, expected, PositionTemporalPairingPolicy(0, 20, 20, 0)))
		assertEquals(80L, paired.predictionInputEarliestAtNanos)
		assertEquals(90L, paired.predictionInputLatestAtNanos)
		assertEquals(0L, paired.teacherToPredictionInputDistanceNanos)
		assertEquals(source.epoch(), paired.predictionEpoch)
		assertSame(rawMain.provenance, teacher.provenance)
		assertNotEquals(teacher.position, prediction.position)
		assertEquals(dependencies, prediction.dependencies)
		assertEquals(prediction.position, predictor.predict(source).position)
		assertTrue(PositionTemporalPairingResult.Pairable::class.java.declaredFields.none {
			it.name.contains("learning", true) || it.name.contains("correction", true) || it.name.contains("error", true)
		})
	}

	@Test fun predictorHasOnlyImmutablePolicyAndCanEvaluateOutOfOrderDeterministically() {
		val fields = PureMainDecoupledHipPredictor::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
		assertEquals(listOf(MainDecoupledHipPredictorPolicy::class.java), fields.map { it.type })
		assertTrue(fields.all { Modifier.isFinal(it.modifiers) })
		val source = input()
		val first = predictor.predict(source)
		predictor.predict(source.copy(predictionSequence = 1))
		unavailable(input(hmdAt = 0))
		val again = predictor.predict(source)
		assertEquals(first.position, again.position)
		assertEquals(first.provenance, again.provenance)
	}
}
