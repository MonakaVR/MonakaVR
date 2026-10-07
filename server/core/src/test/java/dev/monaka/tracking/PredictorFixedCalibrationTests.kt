package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import kotlin.test.*

/** Synthetic values only. No physical calibration, runtime predictor or Strong Trusted claim. */
class PredictorFixedCalibrationTests {
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 2)
	private val zero = Vector3(0f, 0f, 0f)
	private val hmdId = "synthetic:hmd"
	private fun body(hip: Float = .15f) = assertIs<HipBodyModelSnapshotResult.Available>(
		HipBodyModelSnapshot.create(.1f, .2f, .25f, .3f, .35f, hip)).snapshot
	private fun calibration(id: String = "synthetic-head-anchor", session: String = "fit:1",
		source: String = hmdId, model: String = HipBodyModelSnapshot.MODEL_ID,
		offset: Vector3 = zero, q: Quaternion = Quaternion.IDENTITY) =
		syntheticHeadAnchorCalibration(source, model, id, session, offset, q)
	private fun input(fixed: PredictorFixedCalibrationSnapshot = calibration()) = MainDecoupledHipInput(
		RawHmdPoseInput(RawSourceIdentity(hmdId, RawSourceKind.RAW_HMD, isHmd = true),
			Vector3(0f, 1.7f, 0f), Quaternion.IDENTITY, space,
			ObservationSampleProvenance(4, 80, "hmd:1", "provider:1", null, space)),
		RawImuOrientationInput(RawSourceIdentity("synthetic:imu", RawSourceKind.RAW_IMU),
			Quaternion.IDENTITY, space, ObservationSampleProvenance(4, 90, "imu:1", "reset:1", null, space)),
		body(), fixed, space, 3, 100, predictionSequence = 7)
	private fun components(q: Quaternion) = listOf(q.w, q.x, q.y, q.z)
	private fun bits(q: Quaternion) = components(q).map(Float::toRawBits)
	private fun vector(values: List<Float>) = Vector3(values[0], values[1], values[2])
	private fun quaternion(values: List<Float>) = Quaternion(values[0], values[1], values[2], values[3])
	private fun raw(offset: Vector3 = zero, q: Quaternion = Quaternion.IDENTITY) =
		PredictorFixedCalibrationSnapshot.create("head-anchor", "fit:1", hmdId,
			HipBodyModelSnapshot.MODEL_ID, offset, q)

	@Test fun explicitIdentityCalibrationIsAvailableAndFactoryOwnsContentEpoch() {
		val a = assertIs<PredictorFixedCalibrationSnapshotResult.Available>(raw()).snapshot
		assertEquals(zero, a.hmdToHeadAnchorLocalOffset)
		assertEquals(Quaternion.IDENTITY, a.hmdToHeadAnchorOrientation)
		assertEquals("head-anchor", a.identity.calibrationId)
		assertEquals("fit:1", a.sessionEpoch)
		assertTrue(a.identity.epoch.startsWith("predictor-fixed-v1;"))
		assertEquals(a.identity, assertIs<PredictorFixedCalibrationSnapshotResult.Available>(raw()).snapshot.identity)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun blankIdentitiesFailClosed(index: Int) {
		val reasons = listOf(PredictorFixedCalibrationRejectionReason.CALIBRATION_ID_BLANK,
			PredictorFixedCalibrationRejectionReason.SESSION_EPOCH_BLANK,
			PredictorFixedCalibrationRejectionReason.HMD_SOURCE_ID_BLANK,
			PredictorFixedCalibrationRejectionReason.BODY_MODEL_ID_BLANK)
		for (blank in listOf("", " \t\n")) {
			val values = mutableListOf("anchor", "fit", hmdId, HipBodyModelSnapshot.MODEL_ID)
			values[index] = blank
			assertEquals(reasons[index], assertIs<PredictorFixedCalibrationSnapshotResult.Unavailable>(
				PredictorFixedCalibrationSnapshot.create(values[0], values[1], values[2], values[3], zero,
					Quaternion.IDENTITY)).reason)
		}
	}

	@Test fun finiteTranslationRetainsExactBitsWithoutClamping() {
		val values = listOf(-Float.MAX_VALUE, Float.MIN_VALUE, -0f)
		val offset = calibration(offset = vector(values)).hmdToHeadAnchorLocalOffset
		assertEquals(values.map(Float::toRawBits), listOf(offset.x, offset.y, offset.z).map(Float::toRawBits))
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun eachNonfiniteTranslationComponentRejects(index: Int) {
		for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			val offset = vector(MutableList(3) { 0f }.also { it[index] = invalid })
			assertEquals(PredictorFixedCalibrationRejectionReason.OFFSET_NONFINITE,
				assertIs<PredictorFixedCalibrationSnapshotResult.Unavailable>(raw(offset)).reason)
		}
	}

	@Test fun finiteNonunitExtremeAndSubnormalQuaternionsNormalizeSafely() {
		for (q in listOf(Quaternion(1f, -2f, 3f, -4f), Quaternion(Float.MAX_VALUE, -Float.MAX_VALUE, 0f, 0f),
			Quaternion(Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE),
			Quaternion(0f, 0f, Float.MIN_VALUE, 0f), Quaternion(Float.MAX_VALUE, Float.MIN_VALUE, 0f, 0f))) {
			val a = calibration(q = q)
			assertTrue(components(a.hmdToHeadAnchorOrientation).all(Float::isFinite))
			assertEquals(1f, a.hmdToHeadAnchorOrientation.lenSq(), .000001f)
			val b = calibration(q = -q)
			assertEquals(bits(a.hmdToHeadAnchorOrientation), bits(b.hmdToHeadAnchorOrientation))
			assertEquals(a.identity, b.identity)
		}
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun eachNonfiniteQuaternionComponentRejects(index: Int) {
		for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			val q = quaternion(MutableList(4) { 1f }.also { it[index] = invalid })
			assertEquals(PredictorFixedCalibrationRejectionReason.ORIENTATION_INVALID,
				assertIs<PredictorFixedCalibrationSnapshotResult.Unavailable>(raw(q = q)).reason)
		}
	}

	@Test fun zeroQuaternionIncludingSignedZerosRejectsWithoutSubstitution() {
		for (q in listOf(Quaternion.NULL, Quaternion(-0f, 0f, -0f, 0f)))
			assertEquals(PredictorFixedCalibrationRejectionReason.ORIENTATION_INVALID,
				assertIs<PredictorFixedCalibrationSnapshotResult.Unavailable>(raw(q = q)).reason)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun firstNonzeroWxyzSignAndAllRotationZerosAreCanonical(index: Int) {
		val values = MutableList(4) { if (it < index) -0f else -(it + 1f) }
		val a = calibration(q = quaternion(values))
		val b = calibration(q = -quaternion(values))
		assertEquals(a.identity, b.identity)
		assertEquals(bits(a.hmdToHeadAnchorOrientation), bits(b.hmdToHeadAnchorOrientation))
		val stored = components(a.hmdToHeadAnchorOrientation)
		assertTrue(stored.first { it != 0f } > 0f)
		assertTrue(stored.filter { it == 0f }.all { it.toRawBits() == 0 })
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
	fun eachNumericalComponentChangesContentEpochAndRevertRestoresIt(index: Int) {
		val fields = listOf(.1f, -.2f, .3f, 1f, 2f, 3f, 4f)
		fun from(values: List<Float>) = calibration(offset = vector(values), q = quaternion(values.drop(3)))
		val a = from(fields)
		val b = from(fields.toMutableList().also { it[index] += .125f })
		assertEquals(a.identity.calibrationId, b.identity.calibrationId)
		assertNotEquals(a.identity.epoch, b.identity.epoch)
		assertEquals(a.identity, from(fields).identity)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun translationSignedZeroRemainsDistinctContent(index: Int) {
		val a = calibration(offset = zero)
		val b = calibration(offset = vector(MutableList(3) { 0f }.also { it[index] = -0f }))
		assertNotEquals(a.identity, b.identity)
	}

	@Test fun sessionSourceAndModelChangesBindEvenIdenticalNumericalRelations() {
		val a = calibration()
		for (b in listOf(calibration(session = "fit:2"), calibration(source = "other-hmd"), calibration(model = "model:v2"))) {
			assertEquals(a.hmdToHeadAnchorLocalOffset, b.hmdToHeadAnchorLocalOffset)
			assertEquals(a.hmdToHeadAnchorOrientation, b.hmdToHeadAnchorOrientation)
			assertNotEquals(a.identity.epoch, b.identity.epoch)
		}
	}

	@Test fun opaqueStringLengthPrefixesPreventDelimiterCollisions() {
		val a = calibration(source = "h;model=1:m", model = "m", session = "s;session=1:x")
		val b = calibration(source = "h", model = "m;model=1:m", session = "s;session=1:x")
		val c = calibration(source = "h;model=1:m", model = "m;session=1:s", session = "x")
		assertEquals(3, setOf(a.identity.epoch, b.identity.epoch, c.identity.epoch).size)
	}

	@Test fun inputRetainsSnapshotAndCompleteIdentityWithoutRecomputing() {
		val fixed = calibration(offset = Vector3(.01f, .02f, -.03f), q = Quaternion(1f, 2f, 3f, 4f))
		val source = input(fixed)
		assertSame(fixed, source.fixedCalibration)
		assertEquals(fixed.identity.calibrationId, source.epoch().fixedCalibrationId)
		assertEquals(fixed.identity.epoch, source.epoch().fixedCalibrationEpoch)
		val other = calibration(id = "other-anchor", offset = fixed.hmdToHeadAnchorLocalOffset, q = Quaternion(1f, 2f, 3f, 4f))
		assertEquals(fixed.identity.epoch, other.identity.epoch)
		assertNotEquals(fixed.identity, other.identity)
		assertNotEquals(source.epoch(), source.copy(fixedCalibration = other).epoch())
		for (blank in listOf("", " ")) {
			assertFailsWith<IllegalArgumentException> { source.epoch().copy(fixedCalibrationId = blank) }
			assertFailsWith<IllegalArgumentException> { source.epoch().copy(fixedCalibrationEpoch = blank) }
		}
	}

	@Test fun sourceBindingRejectsOldSnapshotAfterHmdSelectionChange() {
		val old = input()
		val hmd = old.rawHmd.copy(source = old.rawHmd.source.copy(sourceId = "other-hmd"))
		assertFailsWith<IllegalArgumentException> { old.copy(rawHmd = hmd) }
		val rebound = old.copy(rawHmd = hmd, fixedCalibration = calibration(source = "other-hmd"))
		assertNotEquals(old.epoch(), rebound.epoch())
	}

	@Test fun bodyModelBindingRejectsMismatchedSchemaWithoutReusingCalibration() {
		val old = input()
		// Current body factory owns a single v1 model ID; no synthetic mutable v2 body is fabricated.
		val futureModelCalibration = calibration(model = "synthetic:model:v2")
		assertFailsWith<IllegalArgumentException> { old.copy(fixedCalibration = futureModelCalibration) }
		assertNotEquals(old.fixedCalibration.identity, futureModelCalibration.identity)
	}

	@Test fun bodyContentChangeReusesFixedSnapshotButChangesBodyPredictionLineage() {
		val old = input()
		val changed = old.copy(bodyModel = body(.625f))
		assertSame(old.fixedCalibration, changed.fixedCalibration)
		assertEquals(old.fixedCalibration.identity, changed.fixedCalibration.identity)
		assertEquals(old.bodyModel.identity.modelId, changed.bodyModel.identity.modelId)
		assertNotEquals(old.epoch().bodyModelEpoch, changed.epoch().bodyModelEpoch)
		assertNotEquals(old.epoch(), changed.epoch())
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun providerImuAndSpaceLineagesRemainIndependent(index: Int) {
		val old = input()
		val revised = space.copy(revision = 3)
		val changed = when (index) {
			0 -> old.copy(rawHmd = old.rawHmd.copy(provenance = old.rawHmd.provenance.copy(sourceEpoch = "reconnect:2")))
			1 -> old.copy(rawHmd = old.rawHmd.copy(provenance = old.rawHmd.provenance.copy(calibrationEpoch = "provider:2")))
			2 -> old.copy(rawHmd = old.rawHmd.copy(provenance = old.rawHmd.provenance.copy(mappingRevision = 2)))
			3 -> old.copy(rawImu = old.rawImu.copy(provenance = old.rawImu.provenance.copy(sourceEpoch = "imu:2")))
			4 -> old.copy(rawImu = old.rawImu.copy(provenance = old.rawImu.provenance.copy(calibrationEpoch = "reset:2")))
			else -> old.copy(space = revised,
				rawHmd = old.rawHmd.copy(space = revised, provenance = old.rawHmd.provenance.copy(space = revised)),
				rawImu = old.rawImu.copy(space = revised, provenance = old.rawImu.provenance.copy(space = revised)))
		}
		assertSame(old.fixedCalibration, changed.fixedCalibration)
		assertEquals(old.epoch().fixedCalibrationEpoch, changed.epoch().fixedCalibrationEpoch)
		assertNotEquals(old.epoch(), changed.epoch())
	}

	@Test fun sampleNumericPoseAndPredictionProgressionNeverAlterFixedIdentity() {
		val old = input()
		val changed = old.copy(nowNanos = 101,
			rawHmd = old.rawHmd.copy(position = Vector3(.5f, 1.8f, -.3f), orientation = Quaternion.J,
				provenance = old.rawHmd.provenance.copy(sequence = 5, sampleAtNanos = 81)),
			rawImu = old.rawImu.copy(orientation = Quaternion.I,
				provenance = old.rawImu.provenance.copy(sequence = 5, sampleAtNanos = 91)))
		assertSame(old.fixedCalibration, changed.fixedCalibration)
		assertEquals(old.epoch(), changed.epoch())
		val p = PositionPredictionProvenance(7, 100, 80, 90, old.epoch())
		assertEquals(p.epoch, p.copy(predictionSequence = 8, generatedAtNanos = 101,
			inputEarliestAtNanos = 81, inputLatestAtNanos = 91, epoch = changed.epoch()).epoch)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun oldCalibrationPredictionRejectsWhileTeacherMountAndRawImuStayUnchanged(index: Int) {
		val old = input()
		val changed = old.copy(fixedCalibration = when (index) {
			0 -> calibration(session = "fit:2")
			1 -> calibration(offset = Vector3(.01f, 0f, 0f))
			2 -> calibration(q = Quaternion.J)
			else -> calibration(id = "other-anchor")
		})
		val origin = RawSourceIdentity("synthetic:main", RawSourceKind.RAW_BACKEND)
		val mount = assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(
			MainTrackerMountCalibrationSnapshot.create("main-mount", "mount:1", origin.sourceId, zero)).snapshot
		val teacher = assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(
			PoseObservation(origin.sourceId, TrackerPosition.HIP, 100, position = Vector3(.1f, 1f, .2f),
				rotation = Quaternion.IDENTITY, provenance = ObservationSampleProvenance(4, 85, "main:1", "upstream:1", null, space)),
			origin, mount)).teacher
		fun prediction(epoch: PositionPredictionEpoch) = PositionPrediction.available(TrackerPosition.HIP,
			teacher.position, space, PositionBodyReference.HIP_CENTER,
			PositionPredictionProvenance(7, 100, 80, 90, epoch), setOf(PositionPredictionDependency.RAW_HMD,
				PositionPredictionDependency.RAW_IMU, PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION))
		val before = PositionCorrectionInput(teacher, prediction(old.epoch()), space, old.epoch(), 3, 100)
		val current = before.copy(prediction = prediction(changed.epoch()), expectedPredictionEpoch = changed.epoch())
		assertEquals(before.prediction.position, current.prediction.position)
		assertEquals(PositionTeacherEpoch.from(before), PositionTeacherEpoch.from(current))
		assertSame(mount.identity, current.mainTeacher.mountCalibration)
		assertSame(old.rawImu, changed.rawImu)
		assertSame(old.rawHmd, changed.rawHmd)
		assertEquals("reset:1", changed.rawImu.provenance.calibrationEpoch)
		val result = assertIs<PositionTemporalPairingResult.Rejected>(PositionTemporalPairing.check(
			before.copy(expectedPredictionEpoch = changed.epoch()), assertNotNull(PositionTeacherEpoch.from(current)),
			PositionTemporalPairingPolicy(10, 100, 100, 100)))
		assertEquals(PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, result.reason)
		assertEquals("prediction_epoch_mismatch", result.structuralReason)
		assertIs<PositionTemporalPairingResult.Pairable>(PositionTemporalPairing.check(current,
			assertNotNull(PositionTeacherEpoch.from(current)), PositionTemporalPairingPolicy(10, 100, 100, 100)))
	}

	@Test fun snapshotAndInputHaveNoMutableOrTeacherReferencesAndNoIdentityOnlyPath() {
		val forbidden = setOf(Tracker::class.java, SkeletonConfigManager::class.java, HumanSkeleton::class.java,
			HumanPoseManager::class.java, PoseObservation::class.java, EffectiveConstraint::class.java,
			ResolvedTrackingPose::class.java, MainHipCenterPositionTeacher::class.java,
			MainTrackerMountCalibrationSnapshot::class.java, MainTrackerMountCalibrationIdentity::class.java)
		for (type in listOf(PredictorFixedCalibrationSnapshot::class.java, MainDecoupledHipInput::class.java)) {
			assertTrue(type.declaredFields.none { it.type in forbidden })
			assertTrue(type.constructors.all { it.parameterTypes.none { type -> type in forbidden } })
		}
		val snapshot = PredictorFixedCalibrationSnapshot::class.java
		assertTrue(snapshot.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.all { Modifier.isFinal(it.modifiers) })
		assertTrue(snapshot.declaredConstructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
		assertTrue(snapshot.declaredMethods.none { it.name.startsWith("copy") })
		assertTrue(snapshot.declaredFields.none { it.type == CoordinateSpace::class.java || it.type == BodyModelIdentity::class.java })
		assertEquals(snapshot, MainDecoupledHipInput::class.java.getDeclaredField("fixedCalibration").type)
		assertTrue(MainDecoupledHipInput::class.java.constructors.all { FixedCalibrationIdentity::class.java !in it.parameterTypes })
		assertTrue(PositionTeacherEpoch::class.java.declaredFields.none {
			it.type == snapshot || it.type == FixedCalibrationIdentity::class.java || it.name.contains("fixedCalibration")
		})
	}

	@Test fun legacyHeadRootReferenceAxesPrecedeGeometricBoneRotationOffset() {
		val q = Quaternion.rotationAroundYAxis(.6f)
		val position = Vector3(.2f, 1.7f, -.3f)
		val head = Tracker(null, 901, "synthetic-head", trackerPosition = TrackerPosition.HEAD,
			hasPosition = true, hasRotation = true, isHmd = true, trackRotDirection = false).also {
			it.status = TrackerStatus.OK; it.position = position; it.setRotation(q)
		}
		val hpm = HumanPoseManager(listOf(head), mapOf(SkeletonConfigOffsets.HEAD to .12f))
		hpm.setLegTweaksEnabled(false)
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()
		val bone = hpm.skeleton.headBone
		assertEquals(position, bone.getPosition())
		val reference = bone.getGlobalRotation() * bone.rotationOffset.inv()
		assertTrue(kotlin.math.abs(q.dot(reference)) > .99999f)
		val expectedTail = position + q.sandwich(Vector3(0f, 0f, .12f))
		assertEquals(expectedTail.x, bone.getTailPosition().x, .000001f)
		assertEquals(expectedTail.y, bone.getTailPosition().y, .000001f)
		assertEquals(expectedTail.z, bone.getTailPosition().z, .000001f)
		assertNotEquals(Quaternion.IDENTITY, bone.rotationOffset)
	}
}
