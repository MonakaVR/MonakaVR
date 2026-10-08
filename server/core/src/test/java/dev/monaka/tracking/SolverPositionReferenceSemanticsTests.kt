package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.BoneType
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKChain
import dev.slimevr.tracking.processor.skeleton.IKConstraint
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

internal class SolverPositionReferenceSemanticsTests {
	private val hip = TrackerPosition.HIP
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val zero = Vector3.NULL
	private val initialRotation = Quaternion.rotationAroundXAxis(.7f) * Quaternion.rotationAroundYAxis(-.4f)
	private val movingRotation = Quaternion.rotationAroundYAxis(.9f) * Quaternion.rotationAroundZAxis(-.6f)
	private val assignment = TrackerBodyAssignments.Snapshot(3,
		mapOf(hip to MainTrackerAssignment(TrackerReference("main"), TrackerReference("imu"))))
	private fun raw(position: Vector3, q: Quaternion = movingRotation, at: Long = 90) = EffectiveConstraint(hip,
		ResolvedComponent(position, "main", ObservationQuality.TRACKED, at),
		ResolvedComponent(q, "main", ObservationQuality.TRACKED, at))
	private fun effective(position: Vector3, q: Quaternion = movingRotation) = SolverEffectiveConstraint(hip,
		ResolvedComponent(position, "arbitrary-provenance", ObservationQuality.DEGRADED, 70),
		ResolvedComponent(q, "imu", ObservationQuality.TRACKED, 80), SolverPositionReference.IK_EFFECTIVE_TARGET)
	private fun near(expected: Vector3, actual: Vector3, epsilon: Float = 2e-5f) {
		assertEquals(expected.x, actual.x, epsilon); assertEquals(expected.y, actual.y, epsilon); assertEquals(expected.z, actual.z, epsilon)
	}
	private inner class Fixture(calibrate: Boolean = true) : AutoCloseable {
		val trackers = TestTrackerSet()
		val manager: HumanPoseManager
		val writeback: ConstraintIkWriteback
		val skeleton get() = manager.skeleton
		val solver get() = skeleton.ikSolver
		val name = "monaka-private:HIP:main"
		init {
			trackers.head.position = Vector3(0f, 1.7f, 0f)
			manager = HumanPoseManager(listOf(trackers.head, trackers.hip)); manager.setLegTweaksEnabled(false)
			solver.enabled = false; manager.update()
			writeback = ConstraintIkWriteback(skeleton)
			val mount = skeleton.hipBone.getTailPosition() + Vector3(.3f, -.2f, .15f)
			writeback.apply(mapOf(hip to raw(mount, initialRotation)), assignment)
			manager.update()
			if (calibrate) {
				solver.resetOffsets(); solver.enabled = true; manager.update()
				assertTrue(calibration().offset.len() > .1f)
				assertNotEquals(Quaternion.IDENTITY, calibration().rotationOffset)
			}
		}
		fun calibration() = assertNotNull(solver.calibrationFor(name, hip.bodyPart))
		fun actual() = assertNotNull(writeback.effectivePositionTargetSnapshot(hip))
		fun project(b: EffectiveConstraint, now: Long = 300, a: TrackerBodyAssignments.Snapshot = assignment) =
			assertIs<MainEffectiveHipTargetResult.Available>(writeback.projectMainEffectiveHipTarget(b, a, now)).target
		fun apply(c: SolverEffectiveConstraint, a: TrackerBodyAssignments.Snapshot = assignment) =
			assertEquals(ConstraintIkWriteback.ApplyResult.Applied, writeback.applySolver(mapOf(hip to c), a)[hip])
		@Suppress("UNCHECKED_CAST")
		fun inputs(): List<IKConstraint> {
			val field = solver.javaClass.getDeclaredField("chainList").also { it.isAccessible = true }
			return (field.get(solver) as List<IKChain>).flatMap { it.positionalInputs() }.filter { it.tracker.name == name }
		}
		fun inject(calibration: IKConstraint.Calibration) = inputs().forEach { it.restore(calibration) }
		override fun close() = writeback.close()
	}

	@Test fun hipConstraintResetTargetIsCentralHipTailAndSameBodyPart(): Unit = Fixture().use { f ->
		assertEquals(BoneType.HIP.bodyPart, hip.bodyPart)
		val cal = f.calibration()
		near(f.skeleton.hipBone.getTailPosition(), f.skeleton.hipTracker!!.position + cal.offset)
		near(f.skeleton.hipBone.getTailPosition(), f.actual())
	}
	@Test fun oldDirectStorageReproducesDoubleApplicationAndTypedCorrectionFixesActualTarget(reporter: TestReporter): Unit = Fixture().use { f ->
		val desired = Vector3(-.6f, 1.1f, .8f)
		val cal = f.calibration()
		val offset = (movingRotation * cal.rotationOffset).sandwich(cal.offset)
		// Characterize old behavior using the unchanged raw adapter, including a private source string.
		val old = raw(desired).copy(position = raw(desired).position!!.copy(sourceId = "monaka-private:position-correction-v1:HIP"))
		f.writeback.apply(mapOf(hip to old), assignment)
		near(desired + offset, f.actual()); assertTrue((f.actual() - desired).len() > .1f)
		f.apply(effective(desired)); near(desired, f.actual())
		near(desired - offset, f.skeleton.hipTracker!!.position)
		assertEquals(cal, f.calibration())
		val evidence = "offset=${cal.offset}; rotationOffset=${cal.rotationOffset}; trackerRotation=$movingRotation; oldTarget=${desired + offset}; desired=$desired; actual=${f.actual()}"
		println("6F numeric evidence: $evidence"); reporter.publishEntry("6F numeric evidence", evidence)
	}
	@ParameterizedTest @ValueSource(strings = ["raw", "monaka-private:position-correction-v1:HIP", "monaka-solver:x"])
	fun adapterUsesPresenceOnlyAndNeverGuessesFromSource(source: String) {
		val b = raw(Vector3(1f, 2f, 3f)); val c = b.copy(position = b.position!!.copy(sourceId = source))
		val adapted = c.solverConstraint()
		assertEquals(SolverPositionReference.TRACKER_ORIGIN, adapted.positionReference)
		assertSame(c.position, adapted.position); assertSame(c.rotation, adapted.rotation)
		assertEquals(SolverPositionReference.NONE, c.copy(position = null).solverConstraint().positionReference)
	}
	@Test fun zeroCalibrationNewChainUsesIdentityAndExactDesiredStorage(): Unit = Fixture(false).use { f ->
		assertEquals(zero, f.calibration().offset)
		val desired = Vector3(-2f, 3f, -4f)
		f.apply(effective(desired)); assertEquals(desired, f.skeleton.hipTracker!!.position); near(desired, f.actual())
	}
	@Test fun combinedRotationOrderHasIndependentAxisGoldenAndRawBehaviorIsUnchanged(): Unit = Fixture().use { f ->
		val h = kotlin.math.sqrt(.5f)
		f.inject(IKConstraint.Calibration(Vector3.POS_X, Quaternion(h, 0f, 0f, h)))
		val q = Quaternion(h, h, 0f, 0f)
		val b = raw(Vector3(2f, 3f, 4f), q)
		// Rz(90) maps +X to +Y, then Rx(90) maps +Y to +Z. Reverse order gives +Y.
		f.writeback.apply(mapOf(hip to b), assignment); near(Vector3(2f, 3f, 5f), f.actual())
		near(Vector3(2f, 3f, 5f), f.project(b).position)
		f.apply(effective(Vector3(-3f, 2f, 1f), q)); near(Vector3(-3f, 2f, 1f), f.actual())
		near(Vector3(-3f, 2f, 0f), f.skeleton.hipTracker!!.position)
	}
	@Test fun validNonunitRotationIsKeptExactlyAndProjectionMatchesActualIk(): Unit = Fixture().use { f ->
		val q = Quaternion(2f, .2f, -.3f, .4f)
		val b = raw(Vector3(2f, 3f, 4f), q)
		val projected = f.project(b)
		f.writeback.apply(mapOf(hip to b), assignment)
		near(projected.position, f.actual()); assertEquals(q, f.skeleton.hipTracker!!.getRotation())
		f.apply(effective(Vector3(-1f, 2f, 3f), q)); near(Vector3(-1f, 2f, 3f), f.actual())
	}
	@Test fun smallInverseCalibrationOfLargeValidNonunitRotationPreservesExistingEquation(): Unit = Fixture().use { f ->
		val q = Quaternion(1e6f, 0f, 0f, 0f)
		val offset = Vector3(.3f, -.2f, .15f)
		f.inject(IKConstraint.Calibration(offset, q.inv()))
		assertTrue(f.calibration().rotationOffset.lenSq() < 1e-10f)
		val b = raw(Vector3(-1f, 2f, 3f), q)
		val projected = f.project(b)
		f.writeback.apply(mapOf(hip to b), assignment)
		near(b.position!!.value + offset, f.actual()); near(projected.position, f.actual())
		f.apply(effective(Vector3(2f, 1f, -3f), q)); near(Vector3(2f, 1f, -3f), f.actual())
	}
	private fun state(now: Long): PositionCorrectionStateSnapshot {
		val e = PositionPredictionEpoch("hmd", "h:1", "hc:1", 1, "imu", "i:1", "ic:1", 1,
			"body", "b:1", "fixed", "f:1", space, 3)
		val t = PositionTeacherEpoch("main", "m:1", "mc:1", 1, space, 3,
			PositionBodyReference.HIP_CENTER, MainTrackerMountCalibrationIdentity("mount", "mt:1"))
		return PositionCorrectionStateSnapshot(PositionCorrectionPhase.HOLDING, Vector3(.1f, -.1f, .05f), space,
			PositionCorrectionLearningLineage(t, e), null, null, 9, now, 0, null)
	}
	private fun fallback(now: Long, p: Vector3): PositionCorrectionSolverContinuityInput {
		val s = state(now); val e = s.lineage!!.predictionEpoch
		val prediction = PositionPrediction.available(hip, p, space, PositionBodyReference.HIP_CENTER,
			PositionPredictionProvenance(9, now, 70, 80, e, 4, 70, 5, 80),
			setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
				PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION))
		val base = EffectiveConstraint(hip, rotation = effective(p).rotation)
		val ready = assertIs<PositionCorrectionApplicationResult.Ready>(PositionCorrectionApplication.prepare(
			prediction, s, base, assignment, space, now))
		return PositionCorrectionSolverContinuityInput(now, now, space, assignment, base, s, ready)
	}
	@Test fun dormant6d6eEndToEndActualTargetsThroughMovingMainAndCompletionWithoutModeJump(reporter: TestReporter): Unit = Fixture().use { f ->
		val controller = PositionCorrectionSolverContinuity(PositionCorrectionReacquisitionTuning(100))
		val input = fallback(100, Vector3(-.4f, 1f, .7f))
		val candidate = input.fallbackApplication!!.candidate
		assertEquals(SolverPositionReference.IK_EFFECTIVE_TARGET, candidate.solverConstraint().positionReference)
		val fallback = controller.select(input); f.apply(fallback.constraint)
		val anchor = candidate.correctedPosition; near(anchor, f.actual())
		val cal = f.calibration(); val proxy = f.skeleton.hipTracker; val rebuilds = f.writeback.topologyRebuilds
		for (now in listOf(110L, 160L, 185L, 209L, 210L)) {
			val q = Quaternion.rotationAroundYAxis(now / 200f) * movingRotation
			val b = raw(Vector3(now / 100f, .9f, -.2f), q, now - 1)
			val main = f.project(b, now)
			val tick = PositionCorrectionSolverContinuityInput(now, now, space, assignment, b, state(now), null, main)
			val selected = controller.select(tick)
			val u = ((now - 110) / 100f).coerceIn(0f, 1f)
			val expected = if (now == 110L) anchor else anchor * (1f - u) + main.position * u
			f.apply(selected.constraint); near(expected, f.actual())
			assertEquals(q, f.skeleton.hipTracker!!.getRotation()); assertSame(proxy, f.skeleton.hipTracker)
			assertEquals(rebuilds, f.writeback.topologyRebuilds); assertEquals(cal, f.calibration())
			if (now < 210) {
				assertEquals(SolverPositionReference.IK_EFFECTIVE_TARGET, selected.constraint.positionReference)
				assertEquals(minOf(70, main.observedAtNanos), selected.constraint.position!!.observedAtNanos)
				// Explicitly rule out interpolation toward the tracker mount.
				if (now == 160L) assertTrue((expected - (anchor * .5f + b.position!!.value * .5f)).len() > .05f)
			} else {
				assertEquals(SolverPositionReference.TRACKER_ORIGIN, selected.constraint.positionReference)
				near(main.position, f.actual()); assertSame(b.position, selected.constraint.position)
				// Same requested physical target represented in both modes, no added discontinuity.
				val direct = f.actual(); f.apply(effective(main.position, q)); near(direct, f.actual())
			}
			val evidence = "u=$u; mainEffective=${main.position}; expected=$expected; actual=${f.actual()}"
			println("6F tick $now: $evidence"); reporter.publishEntry("tick $now", evidence)
		}
	}
	@Test fun resetReadsNewCalibrationNextTickWithoutInvalidatingPhysicalAnchor(): Unit = Fixture().use { f ->
		val c = PositionCorrectionSolverContinuity(PositionCorrectionReacquisitionTuning(100))
		val i = fallback(100, Vector3(-.4f, 1f, .7f)); val anchor = i.fallbackApplication!!.candidate.correctedPosition
		f.apply(c.select(i).constraint)
		val b = raw(Vector3(1f, .8f, .5f))
		fun tick(now: Long) = PositionCorrectionSolverContinuityInput(now, now, space, assignment, b, state(now), null, f.project(b, now))
		val before = f.project(b).position; f.apply(c.select(tick(110)).constraint)
		val old = f.calibration(); f.solver.resetOffsets(); f.manager.update()
		assertNotEquals(old, f.calibration()); assertTrue((before - f.project(b).position).len() > .01f)
		val next = tick(160); f.apply(c.select(next).constraint)
		near(anchor * .5f + next.mainEffectiveHipTarget!!.position * .5f, f.actual())
		assertEquals(anchor, c.snapshot().lastFallbackAnchor!!.position)
	}
	@Test fun retainedCalibrationWorksAcrossPositionLossPauseTopologyRebuildAndNewAssignment(): Unit = Fixture().use { f ->
		val cal = f.calibration()
		f.apply(EffectiveConstraint(hip, rotation = effective(zero).rotation).solverConstraint())
		assertEquals(cal, f.calibration()) // Read retained state with no active positional chain.
		f.apply(effective(Vector3(1f, 2f, 3f))); near(Vector3(1f, 2f, 3f), f.actual())
		f.skeleton.setPauseTracking(true, "6F test")
		assertEquals(ConstraintIkWriteback.ApplyResult.Paused, f.writeback.applySolver(mapOf(hip to effective(zero)), assignment)[hip])
		f.skeleton.setPauseTracking(false, "6F test")
		val relation = assignment.targets.getValue(hip)
		val extra = assignment.copy(targets = assignment.targets + (TrackerPosition.LEFT_FOOT to MainTrackerAssignment(TrackerReference("foot"))))
		f.apply(effective(Vector3(2f, 3f, 4f)), extra); near(Vector3(2f, 3f, 4f), f.actual()); assertEquals(cal, f.calibration())
		val changed = assignment.copy(generation = 4, targets = mapOf(hip to relation.copy(mainTracker = TrackerReference("new-main"))))
		val b = raw(Vector3(-2f, 1f, 4f)).let { it.copy(position = it.position!!.copy(sourceId = "new-main"), rotation = it.rotation!!.copy(sourceId = "new-main")) }
		near(b.position!!.value, f.project(b, a = changed).position)
		f.apply(effective(Vector3(4f, 2f, 1f)), changed)
		assertEquals(Vector3(4f, 2f, 1f), f.skeleton.hipTracker!!.position)
		assertNull(f.solver.calibrationFor(f.name, hip.bodyPart))
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7])
	fun invalidProjectionInputsFailClosed(which: Int): Unit = Fixture().use { f ->
		val b = raw(Vector3(1f, 2f, 3f))
		val bad = when (which) {
			0 -> b.copy(target = TrackerPosition.CHEST); 1 -> b.copy(position = null); 2 -> b.copy(rotation = null)
			3 -> b.copy(position = b.position!!.copy(sourceId = "other"))
			4 -> b.copy(rotation = b.rotation!!.copy(sourceId = "imu"))
			5 -> b.copy(position = b.position!!.copy(observedAtNanos = 301))
			6 -> b.copy(rotation = b.rotation!!.copy(value = Quaternion(0f, 0f, 0f, 0f)))
			else -> b.copy(position = b.position!!.copy(quality = ObservationQuality.LOST))
		}
		assertIs<MainEffectiveHipTargetResult.Unavailable>(f.writeback.projectMainEffectiveHipTarget(bad, assignment, 300))
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun missingStaleOrDifferentAssignmentProjectionCannotAuthorizeReacquisition(which: Int): Unit = Fixture().use { f ->
		val c = PositionCorrectionSolverContinuity(PositionCorrectionReacquisitionTuning(100)); c.select(fallback(100, Vector3(1f, 2f, 3f)))
		val b = raw(Vector3(1f, 2f, 3f))
		val target = when (which) {
			0 -> null; 1 -> f.project(raw(Vector3(3f, 2f, 1f)), now = 110)
			2 -> f.project(b, now = 110, a = assignment.copy(generation = 4))
			else -> f.project(b, now = 109)
		}
		val r = c.select(PositionCorrectionSolverContinuityInput(110, 110, space, assignment, b, state(110), null, target))
		assertEquals(PositionCorrectionSolverContinuityPhase.UNAVAILABLE, r.phase); assertNull(r.constraint.position)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun invalidTypedReferencesRemoveStaleConstraintAndReportFailure(which: Int): Unit = Fixture().use { f ->
		val valid = effective(Vector3(1f, 2f, 3f))
		val bad = when (which) {
			0 -> valid.copy(positionReference = SolverPositionReference.NONE)
			1 -> valid.copy(position = null); 2 -> valid.copy(target = TrackerPosition.CHEST)
			3 -> valid.copy(position = valid.position!!.copy(value = Vector3(Float.NaN, 0f, 0f)))
			4 -> valid.copy(rotation = valid.rotation!!.copy(value = Quaternion(Float.POSITIVE_INFINITY, 0f, 0f, 0f)))
			else -> valid.copy(rotation = valid.rotation!!.copy(value = Quaternion(0f, 0f, 0f, 0f)))
		}
		assertIs<ConstraintIkWriteback.ApplyResult.Rejected>(f.writeback.applySolver(mapOf(hip to bad), assignment)[hip])
		assertNull(f.writeback.effectivePositionTargetSnapshot(hip)); assertFalse(hip in f.writeback.masks())
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun invalidCalibrationAndIntermediateOverflowRejectWithoutWritingNonfinite(which: Int): Unit = Fixture().use { f ->
		val cal = when (which) {
			0 -> IKConstraint.Calibration(Vector3(Float.NaN, 0f, 0f), Quaternion.IDENTITY)
			1 -> IKConstraint.Calibration(Vector3(0f, Float.POSITIVE_INFINITY, 0f), Quaternion.IDENTITY)
			2 -> IKConstraint.Calibration(Vector3.POS_X, Quaternion(Float.NaN, 0f, 0f, 0f))
			3 -> IKConstraint.Calibration(Vector3.POS_X, Quaternion(0f, 0f, 0f, 0f))
			4 -> IKConstraint.Calibration(Vector3.POS_X, Quaternion(1e15f, 0f, 0f, 0f))
			else -> IKConstraint.Calibration(Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE), Quaternion.IDENTITY)
		}
		f.inject(cal)
		val q = if (which == 4) Quaternion(1e15f, 0f, 0f, 0f) else movingRotation
		val proxy = f.skeleton.hipTracker!!; val old = proxy.position
		assertIs<MainEffectiveHipTargetResult.Unavailable>(f.writeback.projectMainEffectiveHipTarget(raw(zero, q), assignment, 300))
		assertIs<ConstraintIkWriteback.ApplyResult.Rejected>(f.writeback.applySolver(mapOf(hip to effective(zero, q)), assignment)[hip])
		assertEquals(old, proxy.position); assertNull(f.writeback.effectivePositionTargetSnapshot(hip))
	}
	@Test fun additionAndSubtractionOverflowHaveTypedFailuresAndNoClamp(): Unit = Fixture().use { f ->
		f.inject(IKConstraint.Calibration(Vector3(Float.MAX_VALUE, 0f, 0f), Quaternion.IDENTITY))
		val b = raw(Vector3(Float.MAX_VALUE, 0f, 0f), Quaternion.IDENTITY)
		assertEquals(SolverTargetFailure.PROJECTION_NONFINITE,
			assertIs<MainEffectiveHipTargetResult.Unavailable>(f.writeback.projectMainEffectiveHipTarget(b, assignment, 300)).reason)
		val c = effective(Vector3(-Float.MAX_VALUE, 0f, 0f), Quaternion.IDENTITY)
		assertEquals(SolverTargetFailure.PRECOMPENSATION_NONFINITE,
			assertIs<ConstraintIkWriteback.ApplyResult.Rejected>(f.writeback.applySolver(mapOf(hip to c), assignment)[hip]).reason)
	}
	@Test fun signedLargeFiniteTargetsStillMatchActualConstraint(): Unit = Fixture().use { f ->
		val p = Vector3(-1e20f, 2e20f, -3e20f)
		f.apply(effective(p)); assertEquals(p, f.actual())
	}
	@Test fun positionOnlyPrecompensatesUsingActualIdentityProxyRotation(): Unit = Fixture().use { f ->
		val p = Vector3(-1f, 2f, 3f)
		f.apply(effective(p).copy(rotation = null))
		// Position-only proxies belong to positional inputs, not the skeleton's FK hipTracker slot.
		assertEquals(Quaternion.IDENTITY, f.inputs().first().tracker.getRotation()); near(p, f.actual())
	}
	@Test fun calibrationLookupRequiresExactNameAndBodyPart(): Unit = Fixture().use { f ->
		assertNull(f.solver.calibrationFor("monaka-private:HIP:mai", hip.bodyPart))
		assertNull(f.solver.calibrationFor(f.name, TrackerPosition.CHEST.bodyPart))
		assertNull(f.solver.calibrationFor("other:${f.name}", hip.bodyPart))
		assertEquals(f.calibration(), f.solver.calibrationSnapshot()[f.name to hip.bodyPart])
	}
	@Test fun knownAlignedTeacherGeometryAgreesWithoutMergingCalibrationContracts(): Unit = Fixture().use { f ->
		val cal = f.calibration()
		val localOffset = cal.rotationOffset.sandwich(cal.offset)
		val mount = assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(
			MainTrackerMountCalibrationSnapshot.create("aligned-fixture", "teacher:1", "main", localOffset)).snapshot
		val b = raw(Vector3(-1f, 2f, 3f))
		val observation = PoseObservation("main", hip, 90, position = b.position!!.value, rotation = b.rotation!!.value,
			provenance = ObservationSampleProvenance(1, 90, "raw:1", "raw-cal:1", 1, space))
		val teacher = assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(
			observation, RawSourceIdentity("main", RawSourceKind.RAW_BACKEND), mount)).teacher
		near(teacher.position, f.project(b).position)
		assertEquals(cal, f.calibration())
	}
}
