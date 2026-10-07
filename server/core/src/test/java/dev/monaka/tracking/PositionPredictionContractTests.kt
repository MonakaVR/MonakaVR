package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class PositionPredictionContractTests {
	private val space = CoordinateSpace("canonical", "rh_y_up_neg_z_forward", 2)
	private val safe = setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
		PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION)
	private val hmdIdentity = RawSourceIdentity("steamvr:hmd", RawSourceKind.RAW_HMD,
		isComputed = true, isHmd = true) // External SteamVR HMDs may be marked computed.
	private val imuIdentity = RawSourceIdentity("slime:waist", RawSourceKind.RAW_IMU)
	private val mainIdentity = RawSourceIdentity("mtp:main", RawSourceKind.RAW_BACKEND)
	private fun provenance(at: Long, sourceEpoch: String = "source:1", calibration: String = "cal:1",
		spaceValue: CoordinateSpace = space) = ObservationSampleProvenance(4, at, sourceEpoch, calibration, 1, spaceValue)
	private fun bodyModel(hipLength: Float = .15f) =
		assertIs<HipBodyModelSnapshotResult.Available>(
			HipBodyModelSnapshot.create(.1f, .2f, .25f, .3f, .35f, hipLength)).snapshot
	private fun input(spaceValue: CoordinateSpace = space) = MainDecoupledHipInput(
		RawHmdPoseInput(hmdIdentity, Vector3(0f, 1.7f, 0f), Quaternion.IDENTITY, spaceValue,
			provenance(80, spaceValue = spaceValue)),
		RawImuOrientationInput(imuIdentity, Quaternion.IDENTITY, spaceValue,
			provenance(90, spaceValue = spaceValue)),
		bodyModel(), syntheticHeadAnchorCalibration(hmdIdentity.sourceId, HipBodyModelSnapshot.MODEL_ID),
		spaceValue, 3, 100, predictionSequence = 7)
	private fun prediction(source: MainDecoupledHipInput = input(),
		dependencies: Set<PositionPredictionDependency> = safe,
		body: PositionBodyReference = PositionBodyReference.HIP_CENTER,
		spaceValue: CoordinateSpace = source.space) = PositionPrediction.available(
		TrackerPosition.HIP, Vector3(.1f, 1f, .2f), spaceValue, body,
		PositionPredictionProvenance(7, 100, 80, 90, source.epoch()), dependencies)
	private fun mainTeacher() = MainHipCenterPositionTeacher(mainIdentity.sourceId, Vector3(.1f, 1f, .2f),
		ObservationQuality.TRACKED, provenance(90), mainIdentity, MainTrackerMountCalibrationIdentity("mount", "mount:1"))
	private fun teacher(p: PositionPrediction = prediction(), expectedSpace: CoordinateSpace = space,
		origin: RawSourceIdentity = mainIdentity, main: MainHipCenterPositionTeacher = mainTeacher()) = PositionCorrectionInput(
		main.copy(rawOrigin = origin), p, expectedSpace, input().epoch(), 3, 100)

	@Test fun rawInputsAndModelLineageAreStructurallyEligibleButNotYetPaired() {
		val source = input()
		assertEquals(TrackerPosition.HIP, source.target)
		assertTrue(source.rawHmd.source.isRawHmd())
		assertTrue(source.rawImu.source.isRawImu())
		val fake = MainDecoupledHipPredictor { prediction(it) }
		val predicted = fake.predict(source)
		assertFalse(PoseObservation::class.java.isInstance(predicted))
		assertEquals("eligible_for_pairing", PositionCorrectionTeacherEligibility.check(predicted).reason)
		assertTrue(PositionCorrectionTeacherEligibility.check(teacher(predicted)).eligibleForPairing)
		assertEquals(Vector3(.1f, 1f, .2f), predicted.position)
	}

	@Test fun predictorBoundaryHasNoSolverMainOrMutableTrackerReference() {
		val forbidden = setOf(dev.slimevr.tracking.processor.config.SkeletonConfigManager::class.java,
			dev.slimevr.tracking.processor.skeleton.HumanSkeleton::class.java,
			dev.slimevr.tracking.processor.HumanPoseManager::class.java,
			dev.slimevr.tracking.trackers.Tracker::class.java, PoseObservation::class.java,
			EffectiveConstraint::class.java, ResolvedTrackingPose::class.java, OutputPose::class.java,
			BackgroundIkResult::class.java)
		assertTrue(MainDecoupledHipInput::class.java.declaredFields.none { it.type in forbidden })
		assertTrue(PositionPrediction::class.java.declaredFields.none { it.type in forbidden })
	}

	@Test fun everyForbiddenDependencyFailsClosedIncludingMainDerivedRotationCorrection() {
		for (dependency in PositionCorrectionTeacherEligibility.forbiddenDependencies) {
			val result = PositionCorrectionTeacherEligibility.check(prediction(dependencies = safe + dependency))
			assertFalse(result.eligibleForPairing, dependency.name)
			assertEquals("feedback_dependency", result.reason, dependency.name)
		}
		val mutable = safe.toMutableSet()
		val captured = prediction(dependencies = mutable)
		mutable += PositionPredictionDependency.COMPUTED_TRACKER
		assertTrue(PositionCorrectionTeacherEligibility.check(captured).eligibleForPairing)
		assertFalse(PositionPredictionDependency.COMPUTED_TRACKER in captured.dependencies)
	}

	@Test fun availablePredictionRequiresFiniteValueSpaceReferenceProvenanceAndLineage() {
		val source = input(); val goodProvenance = PositionPredictionProvenance(7, 100, 80, 90, source.epoch())
		for (bad in listOf(Vector3(Float.NaN, 0f, 0f), Vector3(0f, Float.POSITIVE_INFINITY, 0f)))
			assertFailsWith<IllegalArgumentException> { PositionPrediction.available(TrackerPosition.HIP, bad,
				space, PositionBodyReference.HIP_CENTER, goodProvenance, safe) }
		assertFailsWith<IllegalArgumentException> { PositionPrediction.available(TrackerPosition.HIP, Vector3(0f, 0f, 0f),
			null, PositionBodyReference.HIP_CENTER, goodProvenance, safe) }
		assertFailsWith<IllegalArgumentException> { PositionPrediction.available(TrackerPosition.HIP, Vector3(0f, 0f, 0f),
			space, null, goodProvenance, safe) }
		assertFailsWith<IllegalArgumentException> { PositionPrediction.available(TrackerPosition.HIP, Vector3(0f, 0f, 0f),
			space, PositionBodyReference.UNKNOWN, goodProvenance, safe) }
		assertFailsWith<IllegalArgumentException> { PositionPrediction.available(TrackerPosition.HIP, Vector3(0f, 0f, 0f),
			space, PositionBodyReference.HIP_CENTER, null, safe) }
		assertFailsWith<IllegalArgumentException> { PositionPrediction.available(TrackerPosition.HIP, Vector3(0f, 0f, 0f),
			space, PositionBodyReference.HIP_CENTER, goodProvenance, emptySet()) }
		val unavailable = PositionPrediction.unavailable(TrackerPosition.HIP)
		assertNull(unavailable.position)
		assertFalse(PositionCorrectionTeacherEligibility.check(unavailable).eligibleForPairing)
		// Zero is a legitimate finite value when AVAILABLE, never an invalidity sentinel.
		assertEquals(Vector3(0f, 0f, 0f), PositionPrediction.available(TrackerPosition.HIP, Vector3(0f, 0f, 0f),
			space, PositionBodyReference.HIP_CENTER, goodProvenance, safe).position)
	}

	@Test fun predictionInputWindowAndGenerationTimeAreDistinctFromPhysicalSampleTime() {
		val source = input()
		val p = prediction(source).provenance!!
		assertEquals(80, p.inputEarliestAtNanos)
		assertEquals(90, p.inputLatestAtNanos)
		assertEquals(100, p.generatedAtNanos)
		assertNotEquals(p.generatedAtNanos, p.inputLatestAtNanos)
		assertFailsWith<IllegalArgumentException> { PositionPredictionProvenance(1, 100, 91, 90, source.epoch()) }
		assertFailsWith<IllegalArgumentException> { PositionPredictionProvenance(1, 89, 80, 90, source.epoch()) }
		assertFailsWith<IllegalArgumentException> { PositionPredictionProvenance(1, 100, -1, 90, source.epoch()) }
	}

	@Test fun exactSpaceBodyReferenceAndRawMainAreRequiredForFutureComparison() {
		assertTrue(PositionCorrectionTeacherEligibility.check(teacher()).eligibleForPairing)
		for (changed in listOf(space.copy(revision = 3), space.copy(convention = "other"))) {
			assertEquals("space_mismatch", PositionCorrectionTeacherEligibility.check(teacher(expectedSpace = changed)).reason)
		}
		assertEquals("body_reference_mismatch", PositionCorrectionTeacherEligibility.check(
			teacher(prediction(body = PositionBodyReference.TRACKER_MOUNT))).reason)
		assertEquals("main_space_unknown", PositionCorrectionTeacherEligibility.check(
			teacher(main = mainTeacher().copy(provenance = provenance(90).copy(space = null)))).reason)
		assertEquals("main_position_invalid", PositionCorrectionTeacherEligibility.check(
			teacher(main = mainTeacher().copy(position = Vector3(Float.NaN, 0f, 0f)))).reason)
	}

	@Test fun onlyHipCenterPairsAreDirectlyComparableWhileTrackerMountRemainsRepresentable() {
		val mount = prediction(body = PositionBodyReference.TRACKER_MOUNT)
		assertEquals(PositionBodyReference.TRACKER_MOUNT, mount.bodyReference)
		assertTrue(PositionCorrectionTeacherEligibility.check(mount).eligibleForPairing)
		assertTrue(PositionCorrectionTeacherEligibility.check(teacher()).eligibleForPairing)
		assertEquals(PositionBodyReference.HIP_CENTER, teacher().mainTeacher.bodyReference)
		val result = PositionCorrectionTeacherEligibility.check(teacher(mount))
		assertFalse(result.eligibleForPairing)
		assertEquals("body_reference_mismatch", result.reason)
	}

	@Test fun hmdCalibrationChangeInvalidatesPreviousPredictionEvenWhenPoseIsIdentical() {
		val source = input()
		val changed = source.copy(rawHmd = source.rawHmd.copy(
			provenance = source.rawHmd.provenance.copy(calibrationEpoch = "hmd-recenter:2")))
		assertEquals(source.rawHmd.position, changed.rawHmd.position)
		assertNotEquals(source.epoch(), changed.epoch())
		assertEquals("hmd-recenter:2", changed.epoch().hmdCalibrationEpoch)
		val result = PositionCorrectionTeacherEligibility.check(
			teacher(prediction(source)).copy(expectedPredictionEpoch = changed.epoch()))
		assertFalse(result.eligibleForPairing)
		assertEquals("prediction_epoch_mismatch", result.reason)
		assertTrue(PositionCorrectionTeacherEligibility.check(
			teacher(prediction(changed)).copy(expectedPredictionEpoch = changed.epoch())).eligibleForPairing)
	}

	@Test fun bothRawMappingRevisionsAreNullableEpochIdentityAndRequireExactMatch() {
		val source = input()
		for ((before, after) in listOf(null to 1L, 1L to 2L, 1L to null)) {
			for (hmd in listOf(true, false)) {
				fun withRevision(revision: Long?) = if (hmd) source.copy(rawHmd = source.rawHmd.copy(
					provenance = source.rawHmd.provenance.copy(mappingRevision = revision)))
				else source.copy(rawImu = source.rawImu.copy(
					provenance = source.rawImu.provenance.copy(mappingRevision = revision)))
				val previous = withRevision(before)
				val current = withRevision(after)
				val context = "hmd=$hmd mapping=$before->$after"
				assertEquals(before, if (hmd) previous.epoch().hmdMappingRevision else previous.epoch().imuMappingRevision)
				assertEquals(after, if (hmd) current.epoch().hmdMappingRevision else current.epoch().imuMappingRevision)
				assertNotEquals(previous.epoch(), current.epoch(), context)
				val result = PositionCorrectionTeacherEligibility.check(
					teacher(prediction(previous)).copy(expectedPredictionEpoch = current.epoch()))
				assertFalse(result.eligibleForPairing, context)
				assertEquals("prediction_epoch_mismatch", result.reason, context)
			}
		}
	}

	@Test fun sampleProgressionGenerationWindowAndNumericPoseDoNotChangeEpochIdentity() {
		val source = input()
		val original = prediction(source).provenance!!
		for (progressed in listOf(
			original.copy(predictionSequence = 8),
			original.copy(generatedAtNanos = 101),
			original.copy(inputEarliestAtNanos = 81, inputLatestAtNanos = 91),
		)) assertEquals(original.epoch, progressed.epoch)
		for (changed in listOf(
			source.copy(rawHmd = source.rawHmd.copy(position = Vector3(.5f, 1.8f, -.3f))),
			source.copy(rawImu = source.rawImu.copy(orientation = Quaternion(0f, 1f, 0f, 0f))),
			source.copy(rawHmd = source.rawHmd.copy(provenance = source.rawHmd.provenance.copy(sequence = 5, sampleAtNanos = 81))),
			source.copy(rawImu = source.rawImu.copy(provenance = source.rawImu.provenance.copy(sequence = 5, sampleAtNanos = 91))),
			source.copy(nowNanos = 101),
		)) assertEquals(source.epoch(), changed.epoch())
		val numericChange = PositionPrediction.available(TrackerPosition.HIP, Vector3(.3f, 1.1f, -.2f),
			space, PositionBodyReference.HIP_CENTER, original, safe)
		assertEquals(original.epoch, numericChange.provenance!!.epoch)
	}

	@Test fun reconnectCalibrationBodyModelSpaceAndAssignmentChangesCreateDistinctEpochs() {
		val source = input(); val original = source.epoch()
		val changes = listOf(
			source.copy(rawHmd = source.rawHmd.copy(provenance = source.rawHmd.provenance.copy(sourceEpoch = "hmd:2"))),
			source.copy(rawImu = source.rawImu.copy(provenance = source.rawImu.provenance.copy(sourceEpoch = "imu:2"))),
			source.copy(rawImu = source.rawImu.copy(provenance = source.rawImu.provenance.copy(calibrationEpoch = "mount:2"))),
			source.copy(bodyModel = bodyModel(hipLength = .2f)),
			source.copy(fixedCalibration = syntheticHeadAnchorCalibration(source.rawHmd.source.sourceId, source.bodyModel.identity.modelId, sessionEpoch = "synthetic-fit:2")),
			source.copy(assignmentGeneration = 4),
			input(space.copy(revision = 3)),
			input(space.copy(convention = "other")),
			input(space.copy(id = "other")),
		)
		for (changed in changes) {
			assertNotEquals(original, changed.epoch())
			val staleEpochPrediction = prediction(changed)
			assertFalse(PositionCorrectionTeacherEligibility.check(teacher(staleEpochPrediction)).eligibleForPairing)
		}
	}

	@Test fun outputNamespacesAndComputedIdentitiesCannotEnterRawPredictorInputs() {
		for (prefix in listOf("monaka-direct:", "monaka-solver:", "monaka-private:", "human://")) {
			assertFailsWith<IllegalArgumentException> { input().copy(rawHmd = input().rawHmd.copy(
				source = hmdIdentity.copy(sourceId = "${prefix}head"))) }
			assertFailsWith<IllegalArgumentException> { input().copy(rawImu = input().rawImu.copy(
				source = imuIdentity.copy(sourceId = "${prefix}imu"))) }
			assertEquals("main_not_raw_backend", PositionCorrectionTeacherEligibility.check(teacher(
				origin = mainIdentity.copy(sourceId = "${prefix}main"),
				main = teacher().mainTeacher.copy(sourceId = "${prefix}main"))).reason)
		}
		assertFailsWith<IllegalArgumentException> { input().copy(rawHmd = input().rawHmd.copy(
			source = hmdIdentity.copy(kind = RawSourceKind.COMPUTED_TRACKER))) }
		assertFailsWith<IllegalArgumentException> { input().copy(rawImu = input().rawImu.copy(
			source = imuIdentity.copy(isComputed = true))) }
		assertEquals("main_not_raw_backend", PositionCorrectionTeacherEligibility.check(teacher(
			origin = mainIdentity.copy(isComputed = true))).reason)
	}

	@Test fun rawInputContractRejectsFutureSampleAndSpaceMismatchWithoutUsingSkeletonObjects() {
		val source = input()
		assertFailsWith<IllegalArgumentException> { source.copy(rawHmd = source.rawHmd.copy(
			provenance = source.rawHmd.provenance.copy(sampleAtNanos = 101))) }
		assertFailsWith<IllegalArgumentException> { source.copy(rawImu = source.rawImu.copy(
			space = space.copy(revision = 3), provenance = source.rawImu.provenance.copy(space = space.copy(revision = 3)))) }
		assertEquals("future_sample", PositionCorrectionTeacherEligibility.check(teacher(
			main = teacher().mainTeacher.copy(provenance = provenance(101)))).reason)
	}
}
