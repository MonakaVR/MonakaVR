package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal class MonakaSolverCompositionTests {
	private val hip = TrackerPosition.HIP
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val assignment = TrackerBodyAssignments.Snapshot(3,
		mapOf(hip to MainTrackerAssignment(TrackerReference("main"), TrackerReference("imu"))))
	private fun base(now: Long = 100) = EffectiveConstraint(hip,
		ResolvedComponent(Vector3(.3f, 1.1f, -.2f), "main", ObservationQuality.TRACKED, now),
		ResolvedComponent(Quaternion.rotationAroundYAxis(.4f), "main", ObservationQuality.TRACKED, now))
	private fun tick(sequence: Long, now: Long = 100, paused: Boolean = false,
		a: TrackerBodyAssignments.Snapshot = assignment, c: Map<TrackerPosition, EffectiveConstraint> = mapOf(hip to base(now))) =
		MonakaResolvedTickSnapshot(sequence, now, now, paused, a, c, 0)
	private fun pc(t: MonakaResolvedTickSnapshot) = PositionCorrectionRuntimeTick(t.tickSequence, t.nowNanos,
		t.resolvedAtNanos, space, t.assignment, t.constraints, null, null)
	private fun rejection(r: MonakaSolverCommitResult, reason: MonakaSolverCommitRejection) {
		assertEquals(reason, assertIs<MonakaSolverCommitResult.Rejected>(r).reason)
		assertFalse(r.writebackAttempted)
	}
	private fun orchestrator(w: ConstraintIkWriteback) = PositionCorrectionRuntimeOrchestrator(
		MainDecoupledHipPredictorPolicy(100, 100, 100), PositionTemporalPairingPolicy(100, 100, 100, 0),
		PositionCorrectionTuning(.1, .2, 5.0, .6, .3, .05, 10_000_000_000, 300_000_000, .5, .3, .2, 0, 2, .0001),
		PositionCorrectionReacquisitionTuning(400_000_000), w)
	private fun trainedInput(t: MonakaResolvedTickSnapshot, teacherPresent: Boolean = true): PositionCorrectionRuntimeTick {
		val body = assertIs<HipBodyModelSnapshotResult.Available>(HipBodyModelSnapshot.create(0f, .1f, .1f, .1f, .1f, .1f)).snapshot
		val fixed = assertIs<PredictorFixedCalibrationSnapshotResult.Available>(PredictorFixedCalibrationSnapshot.create(
			"fit", "fit:1", "hmd", body.identity.modelId, Vector3.NULL, Quaternion.IDENTITY)).snapshot
		val sources = PositionCorrectionPredictorSources(
			RawHmdPoseInput(RawSourceIdentity("hmd", RawSourceKind.RAW_HMD, isHmd = true), Vector3(0f, 1.7f, 0f),
				Quaternion.IDENTITY, space, ObservationSampleProvenance(t.tickSequence, t.nowNanos, "h:1", "hc:1", 1, space)),
			RawImuOrientationInput(RawSourceIdentity("imu", RawSourceKind.RAW_IMU), Quaternion.IDENTITY,
				space, ObservationSampleProvenance(t.tickSequence, t.nowNanos, "i:1", "ic:1", 1, space)), body, fixed)
		val mount = MainTrackerMountCalibrationIdentity("mount", "mount:1")
		val teacher = MainHipCenterPositionTeacher("main", Vector3(.1f, 1.25f, 0f), ObservationQuality.TRACKED,
			ObservationSampleProvenance(t.tickSequence, t.nowNanos, "m:1", "mc:1", 1, space),
			RawSourceIdentity("main", RawSourceKind.RAW_BACKEND), mount)
		val epoch = PositionTeacherEpoch("main", "m:1", "mc:1", 1, space, assignment.generation, PositionBodyReference.HIP_CENTER, mount)
		return PositionCorrectionRuntimeTick(t.tickSequence, t.nowNanos, t.resolvedAtNanos, space, t.assignment,
			t.constraints, sources, if (teacherPresent) PositionCorrectionTeacherTickValue(teacher, epoch) else null)
	}
	private inner class Fixture : AutoCloseable {
		val trackers = TestTrackerSet()
		val manager = HumanPoseManager(listOf(trackers.head, trackers.hip))
		val skeleton get() = manager.skeleton
		val writeback: ConstraintIkWriteback
		var creates = 0; var disposals = 0; var calls = 0
		var throwFactory = false; var throwProcess = false; var reject = false
		val instances = mutableListOf<PositionCorrectionRuntimeOrchestrator>()
		val owner: MonakaSolverComposition
		init {
			trackers.head.position = Vector3(0f, 1.7f, 0f)
			manager.setLegTweaksEnabled(false); skeleton.ikSolver.enabled = false; manager.update()
			writeback = ConstraintIkWriteback(skeleton)
			owner = MonakaSolverComposition(writeback, ProductionPositionCorrectionGate.CONFIGURED_RAW_HMD_BLOCKED, { shared ->
				assertSame(writeback, shared); creates++
				if (throwFactory) error("factory failure")
				val runtime = orchestrator(shared); instances += runtime
				object : MonakaPositionCorrectionSession {
					override fun process(tick: PositionCorrectionRuntimeTick): PositionCorrectionRuntimeTickResult {
						calls++
						if (throwProcess) error("process failure")
						return if (reject) PositionCorrectionRuntimeTickResult.Rejected(PositionCorrectionRuntimeRejectionReason.TIME_ROLLBACK)
						else runtime.process(tick)
					}
					override fun close() { disposals++ }
				}
			})
		}
		fun generic(t: MonakaResolvedTickSnapshot) = assertIs<MonakaSolverCommitResult.GenericCommitted>(owner.commitGeneric(t, t.constraints))
		fun future(t: MonakaResolvedTickSnapshot) = assertIs<PositionCorrectionCommitted>(owner.commitPositionCorrection(t, pc(t)))
		override fun close() = owner.close()
	}

	@Test fun genericReservesOnceAndDuplicateCannotMutateProxy(): Unit = Fixture().use { f ->
		val t = tick(1); val receipt = f.generic(t)
		assertEquals(1, receipt.tickSequence); assertTrue(receipt.writebackAttempted)
		assertEquals(MonakaSolverCommitOwner.GENERIC_EXISTING, receipt.owner)
		val proxy = f.skeleton.hipTracker; val position = proxy!!.position
		rejection(f.owner.commitGeneric(t, mapOf(hip to base().copy(position = null))), MonakaSolverCommitRejection.DUPLICATE_TICK)
		assertSame(proxy, f.skeleton.hipTracker); assertEquals(position, proxy.position)
		assertEquals(1, f.writeback.topologyRebuilds); assertEquals(0, f.creates)
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun ownersExcludeEachOtherInBothOrders(pcFirst: Boolean): Unit = Fixture().use { f ->
		val t = tick(1)
		if (pcFirst) { f.future(t); rejection(f.owner.commitGeneric(t, emptyMap()), MonakaSolverCommitRejection.DUPLICATE_TICK) }
		else { f.generic(t); rejection(f.owner.commitPositionCorrection(t, pc(t)), MonakaSolverCommitRejection.DUPLICATE_TICK) }
		assertEquals(if (pcFirst) 1 else 0, f.calls); assertEquals(1, f.writeback.topologyRebuilds)
	}
	@Test fun orchestratorPreflightRejectionStillConsumesOwnerSequence(): Unit = Fixture().use { f ->
		f.future(tick(1, 200))
		val t = tick(2, 100)
		val receipt = f.future(t)
		assertIs<PositionCorrectionRuntimeTickResult.Rejected>(receipt.result); assertFalse(receipt.writebackAttempted)
		rejection(f.owner.commitGeneric(t, emptyMap()), MonakaSolverCommitRejection.DUPLICATE_TICK)
		assertEquals(2, f.calls); assertEquals(1, f.writeback.topologyRebuilds)
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun factoryAndProcessExceptionsCannotFallbackOrRetry(factory: Boolean): Unit = Fixture().use { f ->
		f.throwFactory = factory; f.throwProcess = !factory; val t = tick(1)
		assertFailsWith<IllegalStateException> { f.owner.commitPositionCorrection(t, pc(t)) }
		rejection(f.owner.commitGeneric(t, t.constraints), MonakaSolverCommitRejection.DUPLICATE_TICK)
		rejection(f.owner.commitPositionCorrection(t, pc(t)), MonakaSolverCommitRejection.DUPLICATE_TICK)
		assertEquals(0, f.writeback.topologyRebuilds)
	}
	@Test fun genericWritebackExceptionCannotRetryEitherOwner(): Unit = Fixture().use { f ->
		f.writeback.close(); val t = tick(1)
		assertFailsWith<IllegalStateException> { f.owner.commitGeneric(t, t.constraints) }
		rejection(f.owner.commitGeneric(t, t.constraints), MonakaSolverCommitRejection.DUPLICATE_TICK)
		rejection(f.owner.commitPositionCorrection(t, pc(t)), MonakaSolverCommitRejection.DUPLICATE_TICK)
		assertEquals(0, f.creates)
	}
	@Test fun partialTypedReceiptNeverFallsBack(): Unit = Fixture().use { f ->
		val head = TrackerPosition.HEAD
		val a = assignment.copy(targets = assignment.targets + (head to MainTrackerAssignment(TrackerReference("head"))))
		val t = tick(1, a = a, c = mapOf(hip to base(), head to EffectiveConstraint(head,
			ResolvedComponent(Vector3(Float.NaN, 1f, 0f), "head", ObservationQuality.TRACKED, 100))))
		val result = assertIs<PositionCorrectionRuntimeTickResult.Processed>(f.future(t).result)
		assertEquals(ConstraintIkWriteback.ApplyResult.Applied, result.writeback[hip])
		assertIs<ConstraintIkWriteback.ApplyResult.Rejected>(result.writeback[head])
		rejection(f.owner.commitGeneric(t, t.constraints), MonakaSolverCommitRejection.DUPLICATE_TICK)
		assertEquals(1, f.calls)
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun rollbackRejectedButSequenceGapsAndSameNowAreLegal(pcOwner: Boolean): Unit = Fixture().use { f ->
		f.generic(tick(7))
		val old = tick(6)
		rejection(if (pcOwner) f.owner.commitPositionCorrection(old, pc(old)) else f.owner.commitGeneric(old, old.constraints),
			MonakaSolverCommitRejection.TICK_SEQUENCE_ROLLBACK)
		f.generic(tick(20)); f.future(tick(25)); assertEquals(1, f.calls)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4])
	fun futureTickCoherenceChecksAllFiveFactsBeforeReservation(which: Int): Unit = Fixture().use { f ->
		val t = tick(5); val r = pc(t)
		val bad = PositionCorrectionRuntimeTick(if (which == 0) 6 else r.tickSequence,
			if (which == 1) 101 else r.nowNanos, if (which == 2) 99 else r.resolvedAtNanos, space,
			if (which == 3) assignment.copy(generation = 4) else assignment,
			if (which == 4) emptyMap() else r.resolvedConstraints, null, null)
		rejection(f.owner.commitPositionCorrection(t, bad), MonakaSolverCommitRejection.INCOHERENT_TICK)
		assertEquals(0, f.creates); f.generic(t)
	}
	@Test fun ownerSwitchPreservesNonzeroCalibrationProxyAndTopology(): Unit = Fixture().use { f ->
		val mount = f.skeleton.hipBone.getTailPosition() + Vector3(.3f, -.2f, .15f)
		val t = tick(1, c = mapOf(hip to base().copy(position = base().position!!.copy(value = mount))))
		f.generic(t); f.manager.update(); f.skeleton.ikSolver.resetOffsets(); f.skeleton.ikSolver.enabled = true; f.manager.update()
		val name = "monaka-private:HIP:main"
		val calibration = assertNotNull(f.skeleton.ikSolver.calibrationFor(name, hip.bodyPart))
		assertTrue(calibration.offset.len() > .1f)
		val proxy = f.skeleton.hipTracker; val rebuilds = f.writeback.topologyRebuilds
		val target = f.writeback.effectivePositionTargetSnapshot(hip)!!
		val next = tick(2, c = t.constraints)
		val r = assertIs<PositionCorrectionRuntimeTickResult.Processed>(f.future(next).result)
		// 6G MAIN_DIRECT deliberately preserves the raw Main origin through its typed sink.
		assertEquals(SolverPositionReference.TRACKER_ORIGIN, r.solverConstraints.getValue(hip).positionReference)
		assertTrue((f.writeback.effectivePositionTargetSnapshot(hip)!! - target).len() < 2e-5f)
		f.generic(tick(3, c = t.constraints))
		assertTrue((f.writeback.effectivePositionTargetSnapshot(hip)!! - target).len() < 2e-5f)
		assertSame(proxy, f.skeleton.hipTracker); assertEquals(rebuilds, f.writeback.topologyRebuilds)
		assertEquals(calibration, f.skeleton.ikSolver.calibrationFor(name, hip.bodyPart))
	}
	@Test fun pauseDropsSessionAndResumeCreatesFreshLearnerAndContinuity(): Unit = Fixture().use { f ->
		f.future(tick(1)); assertEquals(1, f.creates)
		val proxy = f.skeleton.hipTracker; val rebuilds = f.writeback.topologyRebuilds
		f.skeleton.setPauseTracking(true, "test"); f.generic(tick(2, paused = true)); f.generic(tick(3, paused = true))
		assertEquals(1, f.disposals); assertSame(proxy, f.skeleton.hipTracker); assertEquals(rebuilds, f.writeback.topologyRebuilds)
		val paused = tick(4, paused = true)
		rejection(f.owner.commitPositionCorrection(paused, pc(paused)), MonakaSolverCommitRejection.PAUSED)
		assertEquals(1, f.calls)
		f.skeleton.setPauseTracking(false, "test"); f.generic(tick(5))
		assertEquals(1, f.creates) // Resume alone never starts a session.
		val r = assertIs<PositionCorrectionRuntimeTickResult.Processed>(f.future(tick(6)).result)
		assertEquals(2, f.creates); assertNotSame(f.instances[0], f.instances[1])
		assertNull(r.learning.state.lineage); assertEquals(Vector3.NULL, r.learning.state.correctionWorld)
		f.owner.close(); f.owner.close(); assertEquals(2, f.disposals)
	}
	@Test fun effectiveCorrectionSwitchAndPauseEraseLearnedAndFallbackState(): Unit = Fixture().use { f ->
		val mount = f.skeleton.hipBone.getTailPosition() + Vector3(.3f, -.2f, .15f)
		f.generic(tick(1, c = mapOf(hip to base().copy(position = base().position!!.copy(value = mount)))))
		f.manager.update(); f.skeleton.ikSolver.resetOffsets(); f.skeleton.ikSolver.enabled = true; f.manager.update()
		val calibration = f.skeleton.ikSolver.calibrationFor("monaka-private:HIP:main", hip.bodyPart)!!
		assertTrue(calibration.offset.len() > .1f)
		val proxy = f.skeleton.hipTracker; val rebuilds = f.writeback.topologyRebuilds
		fun run(t: MonakaResolvedTickSnapshot, teacher: Boolean = true) = assertIs<PositionCorrectionRuntimeTickResult.Processed>(
			assertIs<PositionCorrectionCommitted>(f.owner.commitPositionCorrection(t, trainedInput(t, teacher))).result)
		run(tick(2, 1_200_000_000)); val trained = run(tick(3, 1_300_000_000))
		assertEquals(PositionCorrectionPhase.TRACKING, trained.learning.state.phase)
		assertTrue(trained.learning.state.correctionWorld.len() > 0)
		val fallback = base(1_400_000_000).copy(position = null, rotation = base().rotation!!.copy(sourceId = "imu"))
		val r = run(tick(4, 1_400_000_000, c = mapOf(hip to fallback)), false)
		assertEquals(PositionCorrectionSolverContinuityPhase.FALLBACK_ACTIVE, r.continuity.phase)
		assertEquals(SolverPositionReference.IK_EFFECTIVE_TARGET, r.solverConstraints.getValue(hip).positionReference)
		val target = f.writeback.effectivePositionTargetSnapshot(hip)!!
		val rotation = r.solverConstraints.getValue(hip).rotation!!.value
		val raw = base().copy(position = base().position!!.copy(value = target - SolverCalibrationTransform.rotatedOffset(rotation, calibration)!!),
			rotation = base().rotation!!.copy(value = rotation))
		f.generic(tick(5, 1_500_000_000, c = mapOf(hip to raw)))
		assertTrue((f.writeback.effectivePositionTargetSnapshot(hip)!! - target).len() < 2e-5f)
		assertSame(proxy, f.skeleton.hipTracker); assertEquals(rebuilds, f.writeback.topologyRebuilds)
		assertEquals(calibration, f.skeleton.ikSolver.calibrationFor("monaka-private:HIP:main", hip.bodyPart))
		f.skeleton.setPauseTracking(true, "test"); f.generic(tick(6, 1_600_000_000, paused = true))
		f.skeleton.setPauseTracking(false, "test")
		val resumed = run(tick(7, 1_700_000_000, c = mapOf(hip to fallback)), false)
		assertNotSame(f.instances[0], f.instances[1]); assertEquals(1, f.disposals)
		assertEquals(Vector3.NULL, resumed.learning.state.correctionWorld)
		assertNull(resumed.solverConstraints.getValue(hip).position)
		assertEquals(PositionCorrectionSolverContinuityPhase.UNAVAILABLE, resumed.continuity.phase)
	}
	@Test fun closeRejectsBothOwnersAndClosesBoundary(): Unit = Fixture().use { f ->
		f.future(tick(1)); f.owner.close(); f.owner.close()
		rejection(f.owner.commitGeneric(tick(2), emptyMap()), MonakaSolverCommitRejection.CLOSED)
		rejection(f.owner.commitPositionCorrection(tick(2), pc(tick(2))), MonakaSolverCommitRejection.CLOSED)
		assertFailsWith<IllegalStateException> { f.writeback.apply(emptyMap(), assignment) }
		assertEquals(1, f.disposals); assertEquals(1, f.calls)
	}
	private fun config() = PositionCorrectionConfig(
		PositionCorrectionRawImuSpaceConfig("slime:hip", space, true),
		PositionCorrectionMainMountCalibrationConfig("mount", "mount:1", "main", Vector3.NULL),
		PositionCorrectionFixedCalibrationConfig("fit", "fit:1", "hmd", HipBodyModelSnapshot.MODEL_ID, Vector3.NULL, Quaternion.IDENTITY),
		PositionCorrectionPredictorPolicyConfig(100, 100, 100), PositionCorrectionPairingPolicyConfig(100, 100, 100, 0),
		PositionCorrectionLearningTuningConfig(.1, .2, 5.0, .6, .3, .05, 10_000_000_000, 300_000_000, .5, .3, .2, 0, 2, .0001),
		PositionCorrectionReacquisitionTuningConfig(400_000_000))
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun publicProductionConstructionNeverCapturesOrStartsSession(configured: Boolean): Unit = Fixture().use { f ->
		f.owner.close()
		val config = if (configured) config() else null
		MonakaSolverComposition(f.skeleton, config, { error("Dormant tracker capture") }, { error("Dormant body capture") }).use { owner ->
			assertEquals(if (configured) ProductionPositionCorrectionGate.CONFIGURED_RAW_HMD_BLOCKED else ProductionPositionCorrectionGate.NOT_CONFIGURED,
				owner.positionCorrectionGate)
			owner.commitGeneric(tick(1), tick(1).constraints)
			f.skeleton.setPauseTracking(true, "test"); owner.commitGeneric(tick(2, paused = true), emptyMap())
			f.skeleton.setPauseTracking(false, "test"); owner.commitGeneric(tick(3), tick(3).constraints)
			val session = owner.javaClass.getDeclaredField("session").also { it.isAccessible = true }
			assertNull(session.get(owner)); if (configured) assertEquals(config(), config)
		}
	}
	@Test fun sourceOwnershipHasNoRawHmdProbeOrDesktopDependency() {
		val source = Files.readString(Path.of("src/main/java/dev/monaka/tracking/MonakaSolverComposition.kt"))
		for (forbidden in listOf("dev.monaka.tracking.desktop", "RawHmdPoseAdmission", "RawHmdPoseInput(", ".capture(", "captureSourceSnapshot", "VRServer"))
			assertFalse(source.contains(forbidden), forbidden)
		assertEquals(1, Regex("ConstraintIkWriteback\\(skeleton\\)").findAll(source).count())
		assertEquals(listOf("NOT_CONFIGURED", "CONFIGURED_RAW_HMD_BLOCKED"), ProductionPositionCorrectionGate.entries.map { it.name })
	}
}
