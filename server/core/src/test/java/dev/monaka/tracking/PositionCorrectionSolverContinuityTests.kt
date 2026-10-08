package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.SkeletonInputView
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal class PositionCorrectionSolverContinuityTests {
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val epoch = PositionPredictionEpoch("hmd", "h:1", "hc:1", 1, "imu", "i:1", "ic:1", 1,
		"body", "b:1", "fixed", "f:1", space, 3)
	private val teacher = PositionTeacherEpoch("main", "m:1", "mc:1", 1, space, 3,
		PositionBodyReference.HIP_CENTER, MainTrackerMountCalibrationIdentity("mount", "mt:1"))
	private val lineage = PositionCorrectionLearningLineage(teacher, epoch)
	private val zero = Vector3(0f, 0f, 0f)
	private val anchor = Vector3(1f, 2f, 3f)
	private fun controller(duration: Long = 100) = PositionCorrectionSolverContinuity(PositionCorrectionReacquisitionTuning(duration))
	private fun assignment() = TrackerBodyAssignments.Snapshot(3,
		mapOf(TrackerPosition.HIP to MainTrackerAssignment(TrackerReference("main"), TrackerReference("imu"))))
	private fun state(now: Long) = PositionCorrectionStateSnapshot(PositionCorrectionPhase.HOLDING, zero,
		space, lineage, null, null, 9, now, 0, null)
	private fun rotation(owner: String, at: Long = 80) =
		ResolvedComponent(Quaternion(2f, .2f, -.3f, .4f), owner, ObservationQuality.TRACKED, at)
	private fun main(p: Vector3 = Vector3(5f, 6f, 7f), at: Long = 90) = EffectiveConstraint(TrackerPosition.HIP,
		ResolvedComponent(p, "main", ObservationQuality.TRACKED, at), rotation("main"))
	private fun fallback(now: Long = 100, p: Vector3 = anchor): PositionCorrectionSolverContinuityInput {
		val b = EffectiveConstraint(TrackerPosition.HIP, rotation = rotation("imu"))
		val s = state(now)
		val provenance = PositionPredictionProvenance(9, now, 70, 80, epoch, 4, 70, 5, 80)
		// Already prepared value bundle; no application/predictor/learner execution in this fixture.
		val candidate = PositionCorrectionApplicationCandidate(TrackerPosition.HIP, PositionBodyReference.HIP_CENTER,
			space, p, zero, p, 9, epoch, provenance, s.phase, lineage, now, 70,
			"monaka-private:position-correction-v1:HIP", b.rotation!!)
		return PositionCorrectionSolverContinuityInput(now, now, space, assignment(), b, s,
			PositionCorrectionApplicationResult.Ready(candidate))
	}
	private fun mainInput(now: Long = 110, b: EffectiveConstraint = main()) =
		PositionCorrectionSolverContinuityInput(now, now, space, assignment(), b, state(now), null,
			(MainEffectiveHipTarget.project(b, assignment(), now, null) as? MainEffectiveHipTargetResult.Available)?.target)
	private fun unavailable(now: Long) = fallback(now).copy(fallbackApplication = null)
	private fun invalid(c: PositionCorrectionSolverContinuity, i: PositionCorrectionSolverContinuityInput,
		reason: PositionCorrectionSolverContinuityReason = PositionCorrectionSolverContinuityReason.MALFORMED_TICK) {
		val r = c.select(i)
		assertEquals(PositionCorrectionSolverContinuityPhase.UNAVAILABLE, r.phase)
		assertEquals(reason, r.reason); assertNull(r.constraint.position); assertNull(r.constraint.rotation)
	}
	private fun near(expected: Vector3, actual: Vector3) {
		assertEquals(expected.x, actual.x, 1e-6f); assertEquals(expected.y, actual.y, 1e-6f); assertEquals(expected.z, actual.z, 1e-6f)
	}

	@Test fun coldMainPassesThroughWithoutLearningLineageOrAdvance() {
		val i = mainInput().let { it.copy(correctionState = it.correctionState.copy(
			phase = PositionCorrectionPhase.UNINITIALIZED, lineage = null, coordinateSpace = null, lastStateAdvanceAtNanos = null)) }
		val c = controller(); val r = c.select(i)
		assertEquals(i.baseHipConstraint.solverConstraint(), r.constraint); assertEquals(PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, r.phase)
		assertEquals(PositionCorrectionSolverContinuityReason.COLD_MAIN_DIRECT, r.reason); assertNull(c.snapshot().lastFallbackAnchor)
	}
	@Test fun selectedFallbackIsExactAndOnlyLatestSelectedPositionBecomesCopiedAnchor() {
		val c = controller(); val a = fallback(); val b = fallback(101, Vector3(2f, 3f, 4f))
		val unused = fallback(102, Vector3(99f, 99f, 99f))
		assertNull(c.snapshot().lastFallbackAnchor)
		for (i in listOf(a, b)) {
			val r = c.select(i); val candidate = i.fallbackApplication!!.candidate
			assertEquals(candidate.solverConstraint(), r.constraint); assertSame(candidate.baseRotation, r.constraint.rotation)
			assertEquals(PositionCorrectionSolverContinuityPhase.FALLBACK_ACTIVE, r.phase)
			val snapshot = c.snapshot().lastFallbackAnchor!!
			assertEquals(r.constraint.position!!.value, snapshot.position); assertNotSame(r.constraint.position.value, snapshot.position)
			assertEquals(space, snapshot.coordinateSpace); assertEquals(3L, snapshot.assignmentGeneration); assertEquals(lineage, snapshot.lineage)
		}
		assertNotEquals(unused.fallbackApplication!!.candidate.correctedPosition, c.snapshot().lastFallbackAnchor!!.position)
		assertEquals(b.fallbackApplication!!.candidate.correctedPosition, c.select(mainInput()).constraint.position!!.value)
	}
	@Test fun firstReturnHasZeroJumpAndExactCurrentMainRotationThenAnalyticalMovingTargetAndExactCompletion() {
		val c = controller(); c.select(fallback())
		val first = mainInput(110); val r = c.select(first)
		assertEquals(PositionCorrectionSolverContinuityPhase.REACQUIRING, r.phase)
		assertEquals(PositionCorrectionSolverContinuityReason.MAIN_REACQUIRE_STARTED, r.reason)
		assertEquals(anchor, r.constraint.position!!.value); assertSame(first.baseHipConstraint.rotation, r.constraint.rotation)
		assertEquals(110L, c.snapshot().reacquireStartedAtNanos)
		near(Vector3(3f, 4f, 5f), c.select(mainInput(160)).constraint.position!!.value)
		val moving = mainInput(185, main(Vector3(9f, 10f, 11f)))
		near(Vector3(7f, 8f, 9f), c.select(moving).constraint.position!!.value)
		assertSame(moving.baseHipConstraint.rotation, c.select(moving).constraint.rotation)
		val boundary = mainInput(210, main(Vector3(.1234567f, -.2345678f, .3456789f), 200))
		val completed = c.select(boundary)
		assertEquals(PositionCorrectionSolverContinuityReason.MAIN_REACQUIRE_COMPLETE, completed.reason)
		assertEquals(boundary.baseHipConstraint.solverConstraint(), completed.constraint); assertNull(c.snapshot().lastFallbackAnchor)
		assertNull(c.snapshot().reacquireStartedAtNanos)
		val later = mainInput(211); assertEquals(later.baseHipConstraint.solverConstraint(), c.select(later).constraint)
	}
	@Test fun durationOvershootReturnsExactMainRatherThanWrappedLerp() {
		val c = controller(); c.select(fallback()); c.select(mainInput())
		val i = mainInput(500); assertEquals(i.baseHipConstraint.solverConstraint(), c.select(i).constraint)
	}
	@ParameterizedTest @ValueSource(longs = [60, 70, 90])
	fun derivedMetadataIsPrivateDegradedOldestSupportAndRotationExact(mainAt: Long) {
		val c = controller(); c.select(fallback())
		val i = mainInput(b = main(at = mainAt)); val r = c.select(i)
		val p = r.constraint.position!!
		assertEquals("monaka-private:position-correction-reacquire-v1:HIP", p.sourceId)
		assertTrue(FeedbackExclusion.isOutput(p.sourceId)); assertEquals(ObservationQuality.DEGRADED, p.quality)
		assertEquals(minOf(70L, mainAt), p.observedAtNanos); assertSame(i.baseHipConstraint.rotation, r.constraint.rotation)
		assertFailsWith<IllegalArgumentException> { TrackerReference(p.sourceId) }
		assertFalse(RawSourceIdentity(p.sourceId, RawSourceKind.RAW_IMU).isRawImu())
		assertFalse(RawSourceIdentity(p.sourceId, RawSourceKind.RAW_HMD, isHmd = true).isRawHmd())
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun mainRelossWithReadyUsesExactNewFallbackAndRestartsFromItsOutput(reacquiring: Boolean) {
		val c = controller(); c.select(fallback()); c.select(mainInput())
		if (!reacquiring) c.select(mainInput(210))
		val f = fallback(220, Vector3(8f, 9f, 10f)); val r = c.select(f)
		assertEquals(PositionCorrectionSolverContinuityReason.MAIN_RELOSS, r.reason)
		assertEquals(f.fallbackApplication!!.candidate.solverConstraint(), r.constraint)
		assertNull(c.snapshot().reacquireStartedAtNanos)
		assertEquals(r.constraint.position!!.value, c.select(mainInput(230)).constraint.position!!.value)
		assertEquals(230L, c.snapshot().reacquireStartedAtNanos)
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun unavailableGapClearsAnchorAndLaterMainIsDirect(reacquiring: Boolean) {
		val c = controller(); c.select(fallback())
		if (reacquiring) c.select(mainInput())
		val gap = unavailable(120); val r = c.select(gap)
		assertEquals(PositionCorrectionSolverContinuityPhase.UNAVAILABLE, r.phase)
		assertEquals(gap.baseHipConstraint.solverConstraint(), r.constraint); assertNull(r.constraint.position); assertNull(c.snapshot().lastFallbackAnchor)
		val main = mainInput(130); assertEquals(main.baseHipConstraint.solverConstraint(), c.select(main).constraint)
		assertEquals(PositionCorrectionSolverContinuityReason.NO_SAFE_FALLBACK_ANCHOR, c.select(main).reason)
	}
	@Test fun unavailableAfterMainDirectDoesNotManufacturePosition() {
		val c = controller(); c.select(mainInput()); assertNull(c.select(unavailable(120)).constraint.position)
		assertEquals(PositionCorrectionSolverContinuityPhase.UNAVAILABLE, c.snapshot().phase)
	}
	@Test fun coldUnavailableAndRecoveryToFallback() {
		val c = controller(); assertNull(c.select(unavailable(100)).constraint.position)
		assertEquals(PositionCorrectionSolverContinuityPhase.FALLBACK_ACTIVE, c.select(fallback(110)).phase)
	}
	private fun changed(i: PositionCorrectionSolverContinuityInput, which: Int): PositionCorrectionSolverContinuityInput {
		val l = i.correctionState.lineage!!
		return when (which) {
			0 -> {
				val next = space.copy(revision = 8)
				i.copy(expectedSpace = next, correctionState = i.correctionState.copy(coordinateSpace = next,
					lineage = l.copy(teacherEpoch = teacher.copy(coordinateSpace = next), predictionEpoch = epoch.copy(coordinateSpace = next))))
			}
			1 -> i.copy(assignment = assignment().copy(generation = 4), correctionState = i.correctionState.copy(
				lineage = l.copy(teacherEpoch = teacher.copy(assignmentGeneration = 4), predictionEpoch = epoch.copy(assignmentGeneration = 4))))
			2 -> i.copy(correctionState = i.correctionState.copy(lineage = l.copy(teacherEpoch = teacher.copy(sourceEpoch = "m:2"))))
			3 -> i.copy(correctionState = i.correctionState.copy(lineage = l.copy(predictionEpoch = epoch.copy(imuSourceEpoch = "i:2"))))
			4 -> i.copy(correctionState = i.correctionState.copy(phase = PositionCorrectionPhase.UNINITIALIZED,
				lineage = null, coordinateSpace = null, lastStateAdvanceAtNanos = null))
			else -> i.copy(correctionState = i.correctionState.copy(lineage = null))
		}
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun contextChangeAndHardInvalidationDiscardAnchorDuringFallbackAndReacquisition(which: Int) {
		for (reacquire in listOf(false, true)) {
			val c = controller(); c.select(fallback()); if (reacquire) c.select(mainInput())
			val i = changed(mainInput(120), which); val r = c.select(i)
			assertEquals(i.baseHipConstraint.solverConstraint(), r.constraint); assertEquals(PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, r.phase)
			assertNull(c.snapshot().lastFallbackAnchor); assertNull(c.snapshot().reacquireStartedAtNanos)
		}
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun currentFallbackCanSeedFreshAnchorAfterContextChange(which: Int) {
		val c = controller(); c.select(fallback()); c.select(mainInput())
		val changed = changed(fallback(120, Vector3(8f, 9f, 10f)), which)
		val s = changed.correctionState; val l = s.lineage!!
		val candidate = changed.fallbackApplication!!.candidate.copy(coordinateSpace = changed.expectedSpace,
			correctionLineage = l, predictionEpoch = l.predictionEpoch,
			predictionProvenance = changed.fallbackApplication.candidate.predictionProvenance.copy(epoch = l.predictionEpoch))
		val i = changed.copy(fallbackApplication = PositionCorrectionApplicationResult.Ready(candidate))
		assertEquals(candidate.solverConstraint(), c.select(i).constraint)
		assertEquals(l, c.snapshot().lastFallbackAnchor!!.lineage)
		val returning = i.copy(nowNanos = 130, resolvedAtNanos = 130, baseHipConstraint = main(),
			correctionState = s.copy(lastStateAdvanceAtNanos = 130), fallbackApplication = null,
			mainEffectiveHipTarget = assertIs<MainEffectiveHipTargetResult.Available>(MainEffectiveHipTarget.project(main(), i.assignment, 130, null)).target)
		assertEquals(candidate.correctedPosition, c.select(returning).constraint.position!!.value)
	}
	@ParameterizedTest @ValueSource(strings = ["other", "monaka-private:x", "monaka-solver:x", "monaka-direct:x", "human://x"])
	fun mainPositionAndRotationMustBothBelongToAssignedMain(source: String) {
		for (position in listOf(true, false)) {
			val b = main(); val bad = if (position) b.copy(position = b.position!!.copy(sourceId = source))
			else b.copy(rotation = b.rotation!!.copy(sourceId = source))
			invalid(controller(), mainInput(b = bad))
		}
	}
	@ParameterizedTest @EnumSource(ObservationQuality::class)
	fun mainPositionAndRotationRequireUsableQuality(quality: ObservationQuality) {
		for (position in listOf(true, false)) {
			val b = main(); val next = if (position) b.copy(position = b.position!!.copy(quality = quality))
			else b.copy(rotation = b.rotation!!.copy(quality = quality))
			val c = controller(); val i = mainInput(b = next)
			if (quality.usable) assertEquals(next.solverConstraint(), c.select(i).constraint) else invalid(c, i)
		}
	}
	@ParameterizedTest @ValueSource(longs = [-1, 111])
	fun mainPositionAndRotationRejectNegativeAndFutureSampleTimes(at: Long) {
		val b = main(); invalid(controller(), mainInput(b = b.copy(position = b.position!!.copy(observedAtNanos = at))))
		invalid(controller(), mainInput(b = b.copy(rotation = b.rotation!!.copy(observedAtNanos = at))))
	}
	@ParameterizedTest @ValueSource(floats = [Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY])
	fun nonfiniteMainPositionsFailClosedAndDiscardOldAnchor(bad: Float) {
		for (p in listOf(Vector3(bad, 0f, 0f), Vector3(0f, bad, 0f), Vector3(0f, 0f, bad))) {
			val c = controller(); c.select(fallback()); c.select(mainInput())
			invalid(c, mainInput(120, main(p)), PositionCorrectionSolverContinuityReason.NUMERIC_INVALID)
			assertNull(c.snapshot().lastFallbackAnchor)
			val valid = mainInput(130); assertEquals(valid.baseHipConstraint.solverConstraint(), c.select(valid).constraint)
		}
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
	fun missingOrInvalidMainRotationNeverSnapsToPositionOnly(which: Int) {
		val q = when (which) {
			0 -> null; 1 -> Quaternion(0f, 0f, 0f, 0f); 2 -> Quaternion(1e-6f, 0f, 0f, 0f)
			3 -> Quaternion(Float.NaN, 0f, 0f, 0f); 4 -> Quaternion(1f, Float.POSITIVE_INFINITY, 0f, 0f)
			5 -> Quaternion(Float.MAX_VALUE, 0f, 0f, 0f); else -> Quaternion(1f, 0f, Float.NEGATIVE_INFINITY, 0f)
		}
		val b = main(); val c = controller(); c.select(fallback())
		invalid(c, mainInput(b = b.copy(rotation = q?.let { b.rotation!!.copy(value = it) })))
	}
	@ParameterizedTest @ValueSource(longs = [-1, 0])
	fun tuningRejectsNonpositiveDurationWithoutDefaults(duration: Long) {
		assertFailsWith<IllegalArgumentException> { PositionCorrectionReacquisitionTuning(duration) }
		assertTrue(PositionCorrectionReacquisitionTuning::class.java.declaredConstructors.all { it.parameterCount == 1 })
	}
	@Test fun sameTimeRepeatIsIdempotentAcrossAllPhases() {
		val c = controller()
		for (i in listOf(fallback(), mainInput(), mainInput(160), mainInput(210), unavailable(220))) {
			val first = c.select(i); val s = c.snapshot()
			repeat(3) { assertEquals(first, c.select(i)); assertEquals(s, c.snapshot()) }
		}
	}
	@Test fun rollbackIsRejectedWithoutRewindingOrDestroyingCurrentProgress() {
		val c = controller(); c.select(fallback()); c.select(mainInput()); c.select(mainInput(160))
		val before = c.snapshot(); invalid(c, mainInput(159), PositionCorrectionSolverContinuityReason.TIME_ROLLBACK)
		assertEquals(before, c.snapshot()); near(Vector3(4f, 5f, 6f), c.select(mainInput(185)).constraint.position!!.value)
	}
	@Test fun longMaxClockUsesSubtractionWithoutEndTimeOverflow() {
		val c = controller(); c.select(fallback(Long.MAX_VALUE - 60))
		c.select(mainInput(Long.MAX_VALUE - 50)); near(Vector3(3f, 4f, 5f), c.select(mainInput(Long.MAX_VALUE)).constraint.position!!.value)
		val complete = controller(40); complete.select(fallback(Long.MAX_VALUE - 60)); complete.select(mainInput(Long.MAX_VALUE - 50))
		val i = mainInput(Long.MAX_VALUE); assertEquals(i.baseHipConstraint.solverConstraint(), complete.select(i).constraint)
	}
	@Test fun extremeFiniteEndpointsCannotOverflowFloatSubtractionAndFirstTickRemainsExact() {
		val a = Vector3(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE)
		val target = a * -1f; val c = controller(); c.select(fallback(p = a))
		assertEquals(a, c.select(mainInput(b = main(target))).constraint.position!!.value)
		assertEquals(zero, c.select(mainInput(160, main(target))).constraint.position!!.value)
		val i = mainInput(210, main(target)); assertEquals(i.baseHipConstraint.solverConstraint(), c.select(i).constraint)
	}
	@Test fun defensiveNonfiniteIntermediateGuardFailsClosedWithoutMainSnap() {
		val c = controller(); c.select(fallback()); c.select(mainInput())
		// Public selection cannot create a nonfinite anchor. Corrupt private state only
		// in this test to exercise the defensive interpolation-output guard itself.
		val field = PositionCorrectionSolverContinuity::class.java.getDeclaredField("anchor").also { it.isAccessible = true }
		val corrupted = c.snapshot().lastFallbackAnchor!!.copy(position = Vector3(Float.NaN, 2f, 3f))
		field.set(c, corrupted)
		invalid(c, mainInput(120), PositionCorrectionSolverContinuityReason.NUMERIC_INVALID)
		assertNull(c.snapshot().lastFallbackAnchor); assertNull(c.snapshot().reacquireStartedAtNanos)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
	fun malformedTickAndCurrentStateRulesRejectMixedBundles(which: Int) {
		val i = mainInput()
		val bad = when (which) {
			0 -> i.copy(nowNanos = -1, resolvedAtNanos = -1)
			1 -> i.copy(resolvedAtNanos = 109)
			2 -> i.copy(baseHipConstraint = i.baseHipConstraint.copy(target = TrackerPosition.CHEST))
			3 -> i.copy(correctionState = i.correctionState.copy(lastStateAdvanceAtNanos = null))
			4 -> i.copy(correctionState = i.correctionState.copy(lastStateAdvanceAtNanos = 109))
			5 -> i.copy(correctionState = i.correctionState.copy(lastStateAdvanceAtNanos = 111))
			6 -> i.copy(fallbackApplication = fallback(110).fallbackApplication)
			else -> i.copy(assignment = assignment().copy(targets = emptyMap()))
		}
		invalid(controller(), bad)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18])
	fun readyIsNotAuthorityForWrongTickBaseOrContext(which: Int) {
		val i = fallback(); val c = i.fallbackApplication!!.candidate
		val bad = when (which) {
			0 -> c.copy(applicationAtNanos = 99); 1 -> c.copy(applicationAtNanos = 101)
			2 -> c.copy(target = TrackerPosition.CHEST); 3 -> c.copy(bodyReference = PositionBodyReference.TRACKER_MOUNT)
			4 -> c.copy(coordinateSpace = space.copy(revision = 8))
			5 -> c.copy(correctionLineage = lineage.copy(teacherEpoch = teacher.copy(sourceEpoch = "new")))
			6 -> c.copy(predictionEpoch = epoch.copy(imuSourceEpoch = "new"))
			7 -> c.copy(correctionPhase = PositionCorrectionPhase.DECAYING)
			8 -> c.copy(correctionWorld = Vector3(1f, 0f, 0f))
			9 -> c.copy(positionSourceId = "raw")
			10 -> c.copy(positionObservedAtNanos = -1); 11 -> c.copy(positionObservedAtNanos = 101)
			12 -> c.copy(predictionProvenance = c.predictionProvenance.copy(generatedAtNanos = 99))
			13 -> c.copy(predictionProvenance = c.predictionProvenance.copy(inputLatestAtNanos = 101,
				inputImuSampleAtNanos = 101, generatedAtNanos = 101))
			14 -> c.copy(predictionSequence = 10)
			15 -> c.copy(baseRotation = c.baseRotation.copy(sourceId = "other"))
			16 -> c.copy(baseRotation = c.baseRotation.copy(observedAtNanos = 81))
			17 -> c.copy(predictionProvenance = c.predictionProvenance.copy(epoch = epoch.copy(hmdSourceEpoch = "new")))
			else -> c.copy(positionObservedAtNanos = 71)
		}
		val controller = controller(); controller.select(fallback(99))
		invalid(controller, i.copy(fallbackApplication = PositionCorrectionApplicationResult.Ready(bad)))
		assertNull(controller.snapshot().lastFallbackAnchor)
	}
	@Test fun nonfiniteFallbackCannotCreateAnchorOrBecomeSnapTarget() {
		val c = controller(); invalid(c, fallback(p = Vector3(Float.NaN, 0f, 0f)), PositionCorrectionSolverContinuityReason.NUMERIC_INVALID)
		assertNull(c.snapshot().lastFallbackAnchor)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11])
	fun everyPredictionIdentityChangeDiscardsAnchor(which: Int) {
		val next = when (which) {
			0 -> epoch.copy(hmdSourceId = "new"); 1 -> epoch.copy(hmdSourceEpoch = "new")
			2 -> epoch.copy(hmdCalibrationEpoch = "new"); 3 -> epoch.copy(hmdMappingRevision = null)
			4 -> epoch.copy(imuSourceId = "new"); 5 -> epoch.copy(imuSourceEpoch = "new")
			6 -> epoch.copy(imuCalibrationEpoch = "new"); 7 -> epoch.copy(imuMappingRevision = 2)
			8 -> epoch.copy(bodyModelId = "new"); 9 -> epoch.copy(bodyModelEpoch = "new")
			10 -> epoch.copy(fixedCalibrationId = "new"); else -> epoch.copy(fixedCalibrationEpoch = "new")
		}
		val c = controller(); c.select(fallback()); c.select(mainInput())
		val i = mainInput(120).let { it.copy(correctionState = it.correctionState.copy(lineage = lineage.copy(predictionEpoch = next))) }
		assertEquals(i.baseHipConstraint.solverConstraint(), c.select(i).constraint); assertNull(c.snapshot().lastFallbackAnchor)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun everyTeacherIdentityChangeDiscardsAnchor(which: Int) {
		val next = when (which) {
			0 -> teacher.copy(sourceId = "new"); 1 -> teacher.copy(sourceEpoch = "new")
			2 -> teacher.copy(calibrationEpoch = "new"); 3 -> teacher.copy(mappingRevision = null)
			4 -> teacher.copy(mountCalibration = MainTrackerMountCalibrationIdentity("mount", "mt:2"))
			else -> teacher.copy(bodyReference = PositionBodyReference.TRACKER_MOUNT)
		}
		val c = controller(); c.select(fallback()); c.select(mainInput())
		val i = mainInput(120).let { it.copy(correctionState = it.correctionState.copy(lineage = lineage.copy(teacherEpoch = next))) }
		assertEquals(i.baseHipConstraint.solverConstraint(), c.select(i).constraint); assertNull(c.snapshot().lastFallbackAnchor)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8])
	fun fallbackRejectsMissingAssignmentOrInvalidStateAndContext(which: Int) {
		val i = fallback(); val relation = i.assignment.targets.getValue(TrackerPosition.HIP)
		val bad = when (which) {
			0 -> i.copy(assignment = assignment().copy(targets = emptyMap()))
			1 -> i.copy(assignment = assignment().copy(targets = mapOf(TrackerPosition.HIP to relation.copy(useAsIkConstraint = false))))
			2 -> i.copy(assignment = assignment().copy(targets = mapOf(TrackerPosition.HIP to relation.copy(rotationFallbackTracker = null))))
			3 -> i.copy(correctionState = i.correctionState.copy(lineage = null))
			4 -> i.copy(correctionState = i.correctionState.copy(phase = PositionCorrectionPhase.UNINITIALIZED))
			5 -> i.copy(correctionState = i.correctionState.copy(lastStateAdvanceAtNanos = 99))
			6 -> i.copy(expectedSpace = space.copy(revision = 8))
			7 -> i.copy(assignment = assignment().copy(generation = 4))
			else -> i.copy(correctionState = i.correctionState.copy(coordinateSpace = space.copy(revision = 8)))
		}
		invalid(controller(), bad)
	}
	@Test fun hardInvalidationPhaseDiscardsAnchorEvenIfMalformedLineageRemains() {
		val c = controller(); c.select(fallback()); c.select(mainInput())
		val i = mainInput(120).let { it.copy(correctionState = it.correctionState.copy(phase = PositionCorrectionPhase.UNINITIALIZED)) }
		assertEquals(i.baseHipConstraint.solverConstraint(), c.select(i).constraint); assertNull(c.snapshot().lastFallbackAnchor)
	}
	@Test fun feedbackEpochCannotGrantFallbackAuthority() {
		val i = fallback(); val e = epoch.copy(hmdSourceId = "monaka-private:x")
		val l = lineage.copy(predictionEpoch = e)
		val candidate = i.fallbackApplication!!.candidate.let { it.copy(predictionEpoch = e, correctionLineage = l,
			predictionProvenance = it.predictionProvenance.copy(epoch = e)) }
		invalid(controller(), i.copy(correctionState = i.correctionState.copy(lineage = l),
			fallbackApplication = PositionCorrectionApplicationResult.Ready(candidate)))
	}
	@Test fun unavailableGapAlsoPreventsReusingAnOldReadyBundle() {
		val c = controller(); val f = fallback(); c.select(f); c.select(unavailable(110))
		invalid(c, unavailable(120).copy(fallbackApplication = f.fallbackApplication))
		val i = mainInput(130); assertEquals(i.baseHipConstraint.solverConstraint(), c.select(i).constraint)
	}
	@Test fun explicitNanosecondDurationAndZeroTimeNeedNoDwell() {
		val c = controller(1)
		val i = fallback().let {
			val e = it.fallbackApplication!!.candidate
			it.copy(nowNanos = 0, resolvedAtNanos = 0, baseHipConstraint = it.baseHipConstraint.copy(rotation = rotation("imu", 0)),
				correctionState = state(0), fallbackApplication = PositionCorrectionApplicationResult.Ready(e.copy(
					applicationAtNanos = 0, positionObservedAtNanos = 0, baseRotation = rotation("imu", 0),
					predictionProvenance = PositionPredictionProvenance(9, 0, 0, 0, epoch, 4, 0, 5, 0))))
		}
		c.select(i)
		val b = main(at = 0).copy(rotation = rotation("main", 0))
		assertEquals(anchor, c.select(mainInput(0, b)).constraint.position!!.value)
		assertEquals(b.solverConstraint(), c.select(mainInput(1, b)).constraint)
	}
	@Test fun noMutationOrFeedbackOrExecutionOfEarlierContractsAndNoProductionCaller() {
		val c = controller(); val i = fallback(); val candidate = i.fallbackApplication!!.candidate
		val s = i.correctionState.copy(); val before = candidate.copy()
		c.select(i); c.select(mainInput()); c.select(mainInput(160))
		assertEquals(s, i.correctionState); assertEquals(before, candidate)
		val type = PositionCorrectionSolverContinuityResult::class.java
		assertFalse(PoseObservation::class.java.isAssignableFrom(type)); assertFalse(PositionPrediction::class.java.isAssignableFrom(type))
		assertTrue(type.declaredMethods.none { it.returnType.simpleName in setOf("PoseObservation", "PositionPrediction", "OutputPose") })
		val root = Path.of("src/main/java"); val file = root.resolve("dev/monaka/tracking/PositionCorrectionSolverContinuity.kt")
		val source = Files.readString(file)
		for (forbidden in listOf("System.nanoTime", "System.currentTimeMillis", "Instant.now", "Random", "ObservationStore",
			"ConstraintPipeline", "OutputContinuityController", "BackgroundIkPoseReader", "PureMainDecoupledHipPredictor",
			"PositionErrorMeasurement", ".observe(", "advanceWithoutMeasurement(", "PositionCorrectionApplication.prepare", "MonakaRuntime"))
			assertFalse(source.contains(forbidden), forbidden)
		Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") && it != file }.forEach {
			assertFalse(Files.readString(it).contains("PositionCorrectionSolverContinuity"), it.toString())
		} }
	}
	@Test fun actualWritebackAndSolverKeepBothCapabilitiesAndStableProxyAcrossAllThreePhases() {
		val trackers = TestTrackerSet(); trackers.head.position = Vector3(0f, 1.7f, 0f)
		val manager = HumanPoseManager(listOf(trackers.head, trackers.hip)); manager.setLegTweaksEnabled(false)
		manager.skeleton.ikSolver.enabled = false; manager.update()
		val baseline = manager.skeleton.computedHipTracker!!.position
		val c = controller(); val f = fallback(p = baseline + Vector3(.1f, 0f, 0f))
		val inputs = listOf(f, mainInput(110, main(baseline + Vector3(.2f, 0f, 0f))),
			mainInput(160, main(baseline + Vector3(.3f, 0f, 0f))), mainInput(210, main(baseline + Vector3(.4f, 0f, 0f))))
		ConstraintIkWriteback(manager.skeleton).use { writeback ->
			var proxy: dev.slimevr.tracking.trackers.Tracker? = null
			var rebuilds: Int? = null
			val phases = mutableListOf<PositionCorrectionSolverContinuityPhase>()
			for (i in inputs) {
				val r = c.select(i); phases += r.phase
				writeback.applySolver(mapOf(TrackerPosition.HIP to r.constraint), i.assignment)
				assertEquals(ConstraintIkWriteback.ComponentMask(true, true), writeback.masks().getValue(TrackerPosition.HIP))
				val current = manager.skeleton.hipTracker!!
				if (proxy == null) { proxy = current; rebuilds = writeback.topologyRebuilds }
				assertSame(proxy, current); assertEquals(rebuilds, writeback.topologyRebuilds)
				assertEquals(r.constraint.position!!.value, current.position); assertEquals(r.constraint.rotation!!.value, current.getRotation())
				assertNotSame(trackers.hip, current); assertTrue(FeedbackExclusion.isOutput(current.name))
				val viewMethod = ConstraintIkWriteback::class.java.getDeclaredMethod("inputView", List::class.java).also { it.isAccessible = true }
				val view = viewMethod.invoke(writeback, listOf(trackers.head, trackers.hip)) as SkeletonInputView
				assertEquals(listOf(current), view.constraints.filter { it.trackerPosition == TrackerPosition.HIP })
				assertEquals(listOf(current), view.rotations.filter { it.trackerPosition == TrackerPosition.HIP })
				val extraction = manager.skeleton.ikSolver.javaClass.getDeclaredMethod("extractPositionalConstraints", List::class.java).also { it.isAccessible = true }
				assertTrue(current in extraction.invoke(manager.skeleton.ikSolver, view.constraints) as List<*>)
				manager.skeleton.ikSolver.enabled = true; repeat(3) { manager.update() }
				val solved = manager.skeleton.computedHipTracker!!.position
				assertTrue(listOf(solved.x, solved.y, solved.z).all(Float::isFinite))
			}
			assertEquals(listOf(PositionCorrectionSolverContinuityPhase.FALLBACK_ACTIVE, PositionCorrectionSolverContinuityPhase.REACQUIRING,
				PositionCorrectionSolverContinuityPhase.REACQUIRING, PositionCorrectionSolverContinuityPhase.MAIN_DIRECT), phases)
		}
		assertSame(trackers.hip, manager.skeleton.hipTracker)
	}
}
