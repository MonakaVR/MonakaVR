package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal class PositionCorrectionApplicationTests {
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val epoch = PositionPredictionEpoch("hmd", "h:1", "hc:1", 1, "imu", "i:1", "ic:1", 1,
		"body", "b:1", "fixed", "f:1", space, 3)
	private val teacherEpoch = PositionTeacherEpoch("main", "m:1", "mc:1", 1, space, 3,
		PositionBodyReference.HIP_CENTER, MainTrackerMountCalibrationIdentity("mount", "mt:1"))
	private val dependencies = setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
		PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION)
	private val zero = Vector3(0f, 0f, 0f)
	private fun prediction(position: Vector3 = Vector3(1f, 2f, 3f), generated: Long = 100,
		context: PositionPredictionEpoch = epoch, target: TrackerPosition = TrackerPosition.HIP,
		reference: PositionBodyReference = PositionBodyReference.HIP_CENTER,
		deps: Set<PositionPredictionDependency> = dependencies, predictionSpace: CoordinateSpace = context.coordinateSpace) =
		PositionPrediction.available(target, position, predictionSpace, reference,
			PositionPredictionProvenance(9, generated, 80, 90, context, 4, 80, 5, 90), deps)
	private fun state() = PositionCorrectionStateSnapshot(PositionCorrectionPhase.TRACKING, Vector3(.1f, -.2f, .3f),
		space, PositionCorrectionLearningLineage(teacherEpoch, epoch), null, null, 8, 100, 2, 80)
	private fun assignment() = TrackerBodyAssignments.Snapshot(3,
		mapOf(TrackerPosition.HIP to MainTrackerAssignment(TrackerReference("main"), TrackerReference("imu"))))
	private fun base() = EffectiveConstraint(TrackerPosition.HIP, rotation =
		ResolvedComponent(Quaternion.rotationAroundXAxis(.4f), "imu", ObservationQuality.DEGRADED, 85))
	private fun prepare(p: PositionPrediction = prediction(), s: PositionCorrectionStateSnapshot = state(),
		b: EffectiveConstraint = base(), a: TrackerBodyAssignments.Snapshot = assignment(),
		expected: CoordinateSpace = space, at: Long = 100) = PositionCorrectionApplication.prepare(p, s, b, a, expected, at)
	private fun ready(p: PositionPrediction = prediction(), s: PositionCorrectionStateSnapshot = state(),
		b: EffectiveConstraint = base(), a: TrackerBodyAssignments.Snapshot = assignment(), at: Long = 100) =
		assertIs<PositionCorrectionApplicationResult.Ready>(prepare(p, s, b, a, at = at)).candidate
	private fun rejected(reason: PositionCorrectionApplicationRejectionReason, result: PositionCorrectionApplicationResult) =
		assertEquals(reason, assertIs<PositionCorrectionApplicationResult.Rejected>(result).reason)
	private fun near(expected: Vector3, actual: Vector3) {
		assertEquals(expected.x, actual.x, 1e-6f); assertEquals(expected.y, actual.y, 1e-6f); assertEquals(expected.z, actual.z, 1e-6f)
	}

	@Test fun absoluteWorldAdditionHasCorrectSignAndNoGainOrIncrementalMutation() {
		val p = prediction(); val s = state(); val b = base(); val before = s.copy()
		val c = ready(p, s, b)
		near(Vector3(1.1f, 1.8f, 3.3f), c.correctedPosition)
		near(s.correctionWorld, c.correctedPosition - p.position!!)
		assertEquals(before, s); assertEquals(Vector3(1f, 2f, 3f), p.position); assertNull(b.position)
		assertNotSame(p.position, c.basePredictionPosition); assertNotSame(s.correctionWorld, c.correctionWorld)
		assertEquals(TrackerPosition.HIP, c.target); assertEquals(PositionBodyReference.HIP_CENTER, c.bodyReference)
		assertEquals(space, c.coordinateSpace); assertEquals(epoch, c.predictionEpoch)
		assertSame(p.provenance, c.predictionProvenance); assertEquals(9L, c.predictionSequence)
		assertSame(s.lineage, c.correctionLineage)
	}
	@Test fun repeatedPreparationIsIdempotentAndCorrectedConstraintCannotBeReapplied() {
		val p = prediction(); val s = state(); val b = base(); val a = assignment()
		val first = ready(p, s, b, a)
		assertEquals(first, ready(p, s, b, a))
		rejected(PositionCorrectionApplicationRejectionReason.BASE_POSITION_ALREADY_PRESENT,
			prepare(p, s, first.ikConstraint(), a))
	}
	@Test fun mainPositionIsNeverOverwrittenEvenWhenFallbackRotationMatches() {
		val b = base().copy(position = ResolvedComponent(Vector3(99f, 98f, 97f), "main", ObservationQuality.TRACKED, 90))
		rejected(PositionCorrectionApplicationRejectionReason.BASE_POSITION_ALREADY_PRESENT, prepare(b = b))
		assertEquals(Vector3(99f, 98f, 97f), b.position!!.value)
	}
	@Test fun rotationCorrectionNumericsAndAllMetadataAreExactlyPreservedWithoutNormalization() {
		val b = base().copy(rotation = base().rotation!!.copy(value = Quaternion(2f, .2f, -.3f, .4f)))
		val c = ready(b = b); val ik = c.ikConstraint()
		assertSame(b.rotation, c.baseRotation); assertSame(b.rotation, ik.rotation)
		val rotation = assertNotNull(ik.rotation)
		assertSame(b.rotation!!.value, rotation.value)
		assertEquals("imu", rotation.sourceId); assertEquals(ObservationQuality.DEGRADED, rotation.quality)
		assertEquals(85L, rotation.observedAtNanos)
	}
	@ParameterizedTest @EnumSource(value = PositionCorrectionPhase::class, names = ["UNINITIALIZED"], mode = EnumSource.Mode.EXCLUDE)
	fun everyActivePhaseUsesItsExactCurrentCorrectionAndDegradedOldestSupport(phase: PositionCorrectionPhase) {
		val s = state().copy(phase = phase, correctionWorld = if (phase == PositionCorrectionPhase.EXPIRED) zero else state().correctionWorld)
		val c = ready(s = s); val position = c.ikConstraint().position!!
		assertEquals(s.correctionWorld, c.correctionWorld); assertEquals(phase, c.correctionPhase)
		assertEquals(ObservationQuality.DEGRADED, position.quality); assertEquals(80L, position.observedAtNanos)
		assertEquals(100L, c.applicationAtNanos); assertEquals(80L, c.positionObservedAtNanos)
		if (phase == PositionCorrectionPhase.EXPIRED) assertEquals(prediction().position, position.value)
	}
	@Test fun privateDerivedPositionCannotBecomeRawAssignmentImuHmdOrTeacher() {
		val id = ready().positionSourceId
		assertEquals("monaka-private:position-correction-v1:HIP", id); assertTrue(FeedbackExclusion.isOutput(id))
		assertFailsWith<IllegalArgumentException> { TrackerReference(id) }
		assertFailsWith<IllegalArgumentException> { TrackerReference.slime(id) }
		assertFalse(RawSourceIdentity(id, RawSourceKind.RAW_IMU).isRawImu())
		assertFalse(RawSourceIdentity(id, RawSourceKind.RAW_HMD, isHmd = true).isRawHmd())
		assertFalse(RawSourceIdentity(id, RawSourceKind.RAW_BACKEND).isRawBackend())
	}
	@Test fun assignmentAndBaseStructuralGatesAreFailClosed() {
		val a = assignment(); val relation = a.targets.getValue(TrackerPosition.HIP)
		rejected(PositionCorrectionApplicationRejectionReason.ASSIGNMENT_MISSING, prepare(a = a.copy(targets = emptyMap())))
		rejected(PositionCorrectionApplicationRejectionReason.IK_CONSTRAINT_DISABLED,
			prepare(a = a.copy(targets = mapOf(TrackerPosition.HIP to relation.copy(useAsIkConstraint = false)))))
		rejected(PositionCorrectionApplicationRejectionReason.ROTATION_FALLBACK_UNASSIGNED,
			prepare(a = a.copy(targets = mapOf(TrackerPosition.HIP to relation.copy(rotationFallbackTracker = null)))))
		rejected(PositionCorrectionApplicationRejectionReason.BASE_TARGET_MISMATCH, prepare(b = base().copy(target = TrackerPosition.CHEST)))
		rejected(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_UNAVAILABLE, prepare(b = EffectiveConstraint(TrackerPosition.HIP)))
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun fallbackBaseAndPredictionTripleBindingRejectsEveryMismatchAndMainRotationOnly(which: Int) {
		val a = assignment(); val b = base(); val relation = a.targets.getValue(TrackerPosition.HIP)
		val result = when (which) {
			0 -> prepare(a = a.copy(targets = mapOf(TrackerPosition.HIP to relation.copy(rotationFallbackTracker = TrackerReference("other")))))
			1 -> prepare(b = b.copy(rotation = b.rotation!!.copy(sourceId = "other")))
			2 -> prepare(a = a.copy(targets = mapOf(TrackerPosition.HIP to relation.copy(rotationFallbackTracker = TrackerReference("other")))),
				b = b.copy(rotation = b.rotation!!.copy(sourceId = "other")))
			else -> prepare(b = b.copy(rotation = b.rotation!!.copy(sourceId = "main")))
		}
		rejected(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_SOURCE_MISMATCH, result)
	}
	@ParameterizedTest @ValueSource(strings = ["monaka-private:x", "monaka-solver:x", "human://x", "monaka-direct:x"])
	fun feedbackRotationRejectsBeforeSourceMismatch(id: String) {
		rejected(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_FEEDBACK_SOURCE,
			prepare(b = base().copy(rotation = base().rotation!!.copy(sourceId = id))))
	}
	@ParameterizedTest @EnumSource(ObservationQuality::class)
	fun rotationQualityUsesExistingUsability(quality: ObservationQuality) {
		val result = prepare(b = base().copy(rotation = base().rotation!!.copy(quality = quality)))
		if (quality.usable) assertIs<PositionCorrectionApplicationResult.Ready>(result)
		else rejected(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_QUALITY_UNUSABLE, result)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun invalidRotationIncludesNonfiniteComponentsOverflowedNormZeroAndTinyNorm(which: Int) {
		val q = when (which) {
			0 -> Quaternion(Float.NaN, 0f, 0f, 0f)
			1 -> Quaternion(1f, Float.POSITIVE_INFINITY, 0f, 0f)
			2 -> Quaternion(1f, 0f, Float.NEGATIVE_INFINITY, 0f)
			3 -> Quaternion(Float.MAX_VALUE, 0f, 0f, 0f)
			4 -> Quaternion(0f, 0f, 0f, 0f)
			else -> Quaternion(1e-6f, 0f, 0f, 0f)
		}
		rejected(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_INVALID,
			prepare(b = base().copy(rotation = base().rotation!!.copy(value = q))))
	}
	@ParameterizedTest @ValueSource(longs = [-1, 101])
	fun invalidRotationTimeRejects(at: Long) {
		rejected(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_TIME_INVALID,
			prepare(b = base().copy(rotation = base().rotation!!.copy(observedAtNanos = at))))
	}
	@Test fun noAdditionalRotationAgeThresholdIsIntroduced() {
		ready(b = base().copy(rotation = base().rotation!!.copy(observedAtNanos = 0)))
		ready(b = base().copy(rotation = base().rotation!!.copy(observedAtNanos = 100)))
	}
	@Test fun stateRequiresInitializationLineageAndExactCurrentAdvance() {
		rejected(PositionCorrectionApplicationRejectionReason.STATE_UNINITIALIZED, prepare(s = state().copy(phase = PositionCorrectionPhase.UNINITIALIZED)))
		rejected(PositionCorrectionApplicationRejectionReason.STATE_LINEAGE_UNAVAILABLE, prepare(s = state().copy(lineage = null)))
		for (at in listOf(null, 99L, 101L)) rejected(PositionCorrectionApplicationRejectionReason.STATE_NOT_ADVANCED_TO_APPLICATION_TIME,
			prepare(s = state().copy(lastStateAdvanceAtNanos = at)))
		rejected(PositionCorrectionApplicationRejectionReason.STATE_EXPIRED_CORRECTION_NONZERO, prepare(s = state().copy(phase = PositionCorrectionPhase.EXPIRED)))
	}
	@ParameterizedTest @ValueSource(longs = [99, 101])
	fun predictionGenerationMustEqualApplicationTickExactly(at: Long) {
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_NOT_CURRENT_FRAME, prepare(p = prediction(generated = at)))
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
	fun everyNonSpacePredictionEpochFieldRequiresExactLineageEquality(which: Int) {
		val next = when (which) {
			0 -> epoch.copy(hmdSourceId = "new"); 1 -> epoch.copy(hmdSourceEpoch = "new")
			2 -> epoch.copy(hmdCalibrationEpoch = "new"); 3 -> epoch.copy(hmdMappingRevision = null)
			4 -> epoch.copy(imuSourceId = "new"); 5 -> epoch.copy(imuSourceEpoch = "new")
			6 -> epoch.copy(imuCalibrationEpoch = "new"); 7 -> epoch.copy(imuMappingRevision = 2)
			8 -> epoch.copy(bodyModelId = "new"); 9 -> epoch.copy(bodyModelEpoch = "new")
			10 -> epoch.copy(fixedCalibrationId = "new"); 11 -> epoch.copy(fixedCalibrationEpoch = "new")
			else -> epoch.copy(assignmentGeneration = 4)
		}
		rejected(PositionCorrectionApplicationRejectionReason.STATE_PREDICTION_EPOCH_MISMATCH, prepare(p = prediction(context = next)))
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun allSpaceLocationsMustMatchExactly(which: Int) {
		val next = space.copy(revision = 8); val s = state(); val l = s.lineage!!
		val r = when (which) {
			0 -> prepare(expected = next)
			1 -> prepare(p = prediction(predictionSpace = next))
			2 -> prepare(s = s.copy(coordinateSpace = next))
			3 -> prepare(s = s.copy(lineage = l.copy(predictionEpoch = epoch.copy(coordinateSpace = next))))
			4 -> prepare(s = s.copy(lineage = l.copy(teacherEpoch = teacherEpoch.copy(coordinateSpace = next))))
			else -> prepare(p = prediction(context = epoch.copy(coordinateSpace = next)))
		}
		assertIs<PositionCorrectionApplicationResult.Rejected>(r)
	}
	@Test fun assignmentGenerationBindsPredictionStateAndTeacher() {
		rejected(PositionCorrectionApplicationRejectionReason.ASSIGNMENT_GENERATION_MISMATCH, prepare(a = assignment().copy(generation = 4)))
		rejected(PositionCorrectionApplicationRejectionReason.ASSIGNMENT_GENERATION_MISMATCH,
			prepare(s = state().copy(lineage = state().lineage!!.copy(teacherEpoch = teacherEpoch.copy(assignmentGeneration = 4)))))
	}
	@Test fun unavailableWrongTargetAndMountPredictionCannotSubstituteTeacherOrZero() {
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_UNAVAILABLE, prepare(p = PositionPrediction.unavailable(TrackerPosition.HIP, space)))
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_TARGET_MISMATCH, prepare(p = prediction(target = TrackerPosition.CHEST)))
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_BODY_REFERENCE_MISMATCH, prepare(p = prediction(reference = PositionBodyReference.TRACKER_MOUNT)))
	}
	@ParameterizedTest @EnumSource(value = PositionPredictionDependency::class, names = ["RAW_HMD", "RAW_IMU", "BODY_MODEL", "FIXED_CALIBRATION"], mode = EnumSource.Mode.EXCLUDE)
	fun everyForbiddenDependencyRejects(dependency: PositionPredictionDependency) {
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_STRUCTURALLY_INELIGIBLE, prepare(p = prediction(deps = dependencies + dependency)))
	}
	@ParameterizedTest @EnumSource(value = PositionPredictionDependency::class, names = ["RAW_HMD", "RAW_IMU", "BODY_MODEL", "FIXED_CALIBRATION"])
	fun eachRequiredDependencyMustExist(dependency: PositionPredictionDependency) {
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_STRUCTURALLY_INELIGIBLE, prepare(p = prediction(deps = dependencies - dependency)))
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun rawEpochSourcesCannotUsePrivateOutputNamespace(hmd: Boolean) {
		val next = if (hmd) epoch.copy(hmdSourceId = "monaka-private:x") else epoch.copy(imuSourceId = "monaka-solver:x")
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_FEEDBACK_SOURCE, prepare(p = prediction(context = next)))
	}
	@ParameterizedTest @ValueSource(floats = [Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY])
	fun correctionBoundaryRejectsNonfiniteSnapshot(bad: Float) {
		for (v in listOf(Vector3(bad, 0f, 0f), Vector3(0f, bad, 0f), Vector3(0f, 0f, bad)))
			rejected(PositionCorrectionApplicationRejectionReason.STATE_CORRECTION_NONFINITE, prepare(s = state().copy(correctionWorld = v)))
	}
	@Test fun floatAdditionOverflowRejectsWithoutClampAndExtremeFiniteResultRemainsReady() {
		val huge = Vector3(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE)
		rejected(PositionCorrectionApplicationRejectionReason.CORRECTED_POSITION_NONFINITE,
			prepare(p = prediction(position = huge), s = state().copy(correctionWorld = huge)))
		assertEquals(huge, ready(p = prediction(position = huge), s = state().copy(correctionWorld = zero)).correctedPosition)
		assertEquals(zero, ready(p = prediction(position = huge), s = state().copy(correctionWorld = huge * -1f)).correctedPosition)
	}
	@Test fun rejectionPrecedenceIsStableForTimeStructureStateAssignmentAndPositionPresence() {
		rejected(PositionCorrectionApplicationRejectionReason.APPLICATION_TIME_INVALID,
			prepare(p = PositionPrediction.unavailable(TrackerPosition.HIP), at = -1))
		rejected(PositionCorrectionApplicationRejectionReason.PREDICTION_UNAVAILABLE,
			prepare(p = PositionPrediction.unavailable(TrackerPosition.HIP), s = state().copy(lineage = null)))
		rejected(PositionCorrectionApplicationRejectionReason.STATE_UNINITIALIZED,
			prepare(s = state().copy(phase = PositionCorrectionPhase.UNINITIALIZED), a = assignment().copy(targets = emptyMap())))
		val b = base().copy(position = ResolvedComponent(zero, "main", ObservationQuality.TRACKED, 90), rotation = null)
		rejected(PositionCorrectionApplicationRejectionReason.BASE_POSITION_ALREADY_PRESENT, prepare(b = b))
	}
	@Test fun applicationHasOnlyValueInputsNoClockRuntimeObservationOrOutputConversion() {
		val method = PositionCorrectionApplication::class.java.declaredMethods.single { it.name == "prepare" }
		assertEquals(listOf(PositionPrediction::class.java, PositionCorrectionStateSnapshot::class.java, EffectiveConstraint::class.java,
			TrackerBodyAssignments.Snapshot::class.java, CoordinateSpace::class.java, Long::class.javaPrimitiveType), method.parameterTypes.toList())
		assertTrue(PositionCorrectionApplication::class.java.declaredFields.all { Modifier.isStatic(it.modifiers) })
		val c = PositionCorrectionApplicationCandidate::class.java
		assertFalse(PoseObservation::class.java.isAssignableFrom(c)); assertFalse(PositionPrediction::class.java.isAssignableFrom(c))
		assertTrue(c.declaredMethods.none { it.returnType.simpleName in setOf("PoseObservation", "PositionPrediction", "OutputPose") })
		val root = Path.of("src/main/java")
		val source = Files.readString(root.resolve("dev/monaka/tracking/PositionCorrectionApplication.kt"))
		for (forbidden in listOf("System.nanoTime", "System.currentTimeMillis", "Instant.now", "ObservationStore", "ConstraintPipeline", ".observe(", "advanceWithoutMeasurement("))
			assertFalse(source.contains(forbidden), forbidden)
		Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") && it.fileName.toString() != "PositionCorrectionApplication.kt" }.forEach {
			assertFalse(Files.readString(it).contains("PositionCorrectionApplication"), it.toString())
		} }
	}
	@Test fun realMeasurementLearningAndGapAdvanceApplyOnlyCurrentHeldOrDecayedState() {
		val law = PositionCorrectionLearningLaw(PositionCorrectionTuning(1.0, 2.0, 10.0, 5.0, 10.0, 2.0,
			2_000_000_000, 1_000_000_000, 1.0, 10.0, 10.0, 0, 2, .001))
		fun current(at: Long, sequence: Long) = PositionPrediction.available(TrackerPosition.HIP, Vector3(1f, 2f, 3f),
			space, PositionBodyReference.HIP_CENTER, PositionPredictionProvenance(sequence, at, at - 20, at - 10,
				epoch, sequence, at - 20, sequence, at - 10), dependencies)
		fun measure(at: Long, sequence: Long): PositionErrorSample {
			val p = current(at, sequence)
			val main = MainHipCenterPositionTeacher("main", Vector3(1.3f, 2.2f, 2.9f), ObservationQuality.TRACKED,
				ObservationSampleProvenance(sequence, at - 15, "m:1", "mc:1", 1, space),
				RawSourceIdentity("main", RawSourceKind.RAW_BACKEND), teacherEpoch.mountCalibration)
			return assertIs<PositionErrorMeasurementResult.Measured>(PositionErrorMeasurement.evaluate(
				PositionCorrectionInput(main, p, space, epoch, 3, at), teacherEpoch,
				PositionTemporalPairingPolicy(0, 20, 20, 0))).sample
		}
		val first = law.observe(measure(100, 1)).state
		assertEquals(PositionCorrectionPhase.REACQUIRING, first.phase)
		ready(p = current(100, 1), s = first)
		val tracked = law.observe(measure(1_000_000_100, 2)).state
		assertEquals(PositionCorrectionPhase.TRACKING, tracked.phase); assertTrue(tracked.correctionWorld.len() > 0f)
		val heldAt = 1_500_000_100L
		rejected(PositionCorrectionApplicationRejectionReason.STATE_NOT_ADVANCED_TO_APPLICATION_TIME,
			prepare(p = current(heldAt, 3), s = law.snapshot(), at = heldAt))
		val held = law.advanceWithoutMeasurement(heldAt, epoch).state
		assertEquals(PositionCorrectionPhase.HOLDING, held.phase)
		assertEquals(tracked.correctionWorld, ready(p = current(heldAt, 3), s = held, at = heldAt).correctionWorld)
		val decayedAt = 3_000_000_100L
		val decayed = law.advanceWithoutMeasurement(decayedAt, epoch).state
		assertEquals(PositionCorrectionPhase.DECAYING, decayed.phase)
		val before = law.snapshot()
		val candidate = ready(p = current(decayedAt, 4), s = decayed, at = decayedAt)
		assertEquals(decayed.correctionWorld, candidate.correctionWorld)
		assertTrue(decayed.correctionWorld.len() < held.correctionWorld.len()); assertEquals(before, law.snapshot())
		near(Vector3(1f, 2f, 3f) + decayed.correctionWorld, candidate.correctedPosition)
	}
	@Test fun actualWritebackPreservesFullProxyExcludesRawHipAndDoesNotDuplicateTopology() {
		val trackers = TestTrackerSet(); trackers.head.position = Vector3(0f, 1.7f, 0f)
		val manager = HumanPoseManager(listOf(trackers.head, trackers.hip)); manager.setLegTweaksEnabled(false)
		manager.skeleton.ikSolver.enabled = false; manager.update()
		val baseline = manager.skeleton.computedHipTracker!!.position
		val c = ready(p = prediction(position = baseline), s = state().copy(correctionWorld = Vector3(.1f, 0f, 0f)))
		ConstraintIkWriteback(manager.skeleton).use { writeback ->
			writeback.apply(mapOf(TrackerPosition.HIP to c.ikConstraint()), assignment())
			assertEquals(ConstraintIkWriteback.ComponentMask(true, true), writeback.masks().getValue(TrackerPosition.HIP))
			val proxy = assertNotNull(manager.skeleton.hipTracker)
			assertNotSame(trackers.hip, proxy); assertTrue(FeedbackExclusion.isOutput(proxy.name)); assertFalse(FeedbackExclusion.accepts(proxy))
			assertFalse(proxy.isInternal); assertTrue(proxy.hasPosition && proxy.hasRotation)
			assertEquals(c.correctedPosition, proxy.position); assertEquals(c.baseRotation.value, proxy.getRotation())
			// Actual positional extraction confirms one usable HIP proxy and no duplicate raw HIP.
			val viewMethod = ConstraintIkWriteback::class.java.getDeclaredMethod("inputView", List::class.java).also { it.isAccessible = true }
			val view = viewMethod.invoke(writeback, listOf(trackers.head, trackers.hip)) as dev.slimevr.tracking.processor.skeleton.SkeletonInputView
			assertEquals(listOf(proxy), view.constraints.filter { it.trackerPosition == TrackerPosition.HIP })
			assertEquals(listOf(proxy), view.rotations.filter { it.trackerPosition == TrackerPosition.HIP })
			val extraction = manager.skeleton.ikSolver.javaClass.getDeclaredMethod("extractPositionalConstraints", List::class.java).also { it.isAccessible = true }
			val positional = extraction.invoke(manager.skeleton.ikSolver, view.constraints) as List<*>
			assertTrue(proxy in positional)
			val rebuilds = writeback.topologyRebuilds
			writeback.apply(mapOf(TrackerPosition.HIP to c.ikConstraint()), assignment())
			assertEquals(rebuilds, writeback.topologyRebuilds); assertSame(proxy, manager.skeleton.hipTracker)
			manager.skeleton.ikSolver.enabled = true; repeat(5) { manager.update() }
			val solved = manager.skeleton.computedHipTracker!!.position
			assertTrue(listOf(solved.x, solved.y, solved.z).all(Float::isFinite)); assertTrue((solved - baseline).len() > .001f)
		}
		assertSame(trackers.hip, manager.skeleton.hipTracker)
	}
}
