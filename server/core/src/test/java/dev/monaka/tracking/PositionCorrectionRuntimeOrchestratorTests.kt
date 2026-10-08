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

internal class PositionCorrectionRuntimeOrchestratorTests {
	private val hip = TrackerPosition.HIP
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val predictionPolicy = MainDecoupledHipPredictorPolicy(20_000_000, 100_000_000, 100_000_000)
	private val pairing = PositionTemporalPairingPolicy(20_000_000, 100_000_000, 100_000_000, 0)
	private val duration = 400_000_000L
	private val assignment = TrackerBodyAssignments.Snapshot(3,
		mapOf(hip to MainTrackerAssignment(TrackerReference("main"), TrackerReference("imu"))))
	private val body = assertIs<HipBodyModelSnapshotResult.Available>(HipBodyModelSnapshot.create(0f, .1f, .1f, .1f, .1f, .1f)).snapshot
	private val fixed = fixed("hmd", body.identity.modelId)
	private val mount = assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(
		MainTrackerMountCalibrationSnapshot.create("teacher-mount", "mount:1", "main", Vector3(.07f, -.03f, .02f))).snapshot
	private fun fixed(hmd: String, model: String) = assertIs<PredictorFixedCalibrationSnapshotResult.Available>(
		PredictorFixedCalibrationSnapshot.create("fixed", "fit:1", hmd, model, Vector3.NULL, Quaternion.IDENTITY)).snapshot
	private fun tuning() = PositionCorrectionTuning(.1, .2, 5.0, .6, .3, .05, 10_000_000_000,
		300_000_000, .5, .3, .2, 0, 2, .0001)
	private fun sources(sequence: Long, at: Long, epoch: String = "i:1", s: CoordinateSpace = space) = PositionCorrectionPredictorSources(
		RawHmdPoseInput(RawSourceIdentity("hmd", RawSourceKind.RAW_HMD, isHmd = true), Vector3(0f, 1.7f, 0f),
			Quaternion.IDENTITY, s, ObservationSampleProvenance(sequence, at, "h:1", "hc:1", 1, s)),
		RawImuOrientationInput(RawSourceIdentity("imu", RawSourceKind.RAW_IMU), Quaternion.IDENTITY,
			s, ObservationSampleProvenance(sequence, at, epoch, "ic:1", 1, s)), body, fixed)
	private fun teacher(sequence: Long, at: Long, error: Vector3 = Vector3(.1f, .05f, 0f),
		generation: Long = 3, s: CoordinateSpace = space): PositionCorrectionTeacherTickValue {
		// Normalization happens BEFORE constructing the tick, independent of resolver Main authority.
		val q = Quaternion.rotationAroundYAxis(.4f)
		val desired = Vector3(0f, 1.2f, 0f) + error
		val raw = PoseObservation("main", hip, at, position = desired - q.sandwich(mount.trackerToHipCenterLocalOffset),
			rotation = q, provenance = ObservationSampleProvenance(sequence, at, "m:1", "mc:1", 1, s))
		val value = assertIs<MainHipCenterTeacherResult.Available>(MainTrackerMountToHipCenter.normalize(raw,
			RawSourceIdentity("main", RawSourceKind.RAW_BACKEND), mount)).teacher
		val current = PositionTeacherEpoch("main", "m:1", "mc:1", 1, s, generation, PositionBodyReference.HIP_CENTER, mount.identity)
		return PositionCorrectionTeacherTickValue(value, current)
	}
	private fun base(now: Long, main: Boolean = true): EffectiveConstraint = if (main) EffectiveConstraint(hip,
		ResolvedComponent(Vector3(now / 1e10f, 1.1f, -.2f), "main", ObservationQuality.TRACKED, now),
		ResolvedComponent(Quaternion.rotationAroundYAxis(now / 1e9f), "main", ObservationQuality.TRACKED, now))
	else EffectiveConstraint(hip, rotation = ResolvedComponent(Quaternion.IDENTITY, "imu", ObservationQuality.TRACKED, now))
	private fun tick(sequence: Long, now: Long = 1_000_000_000 + sequence * 100_000_000,
		main: Boolean = true, teacherPresent: Boolean = true, sourcesPresent: Boolean = true,
		a: TrackerBodyAssignments.Snapshot = assignment, s: CoordinateSpace = space,
		physical: Long = sequence, at: Long = now, error: Vector3 = Vector3(.1f, .05f, 0f),
		epoch: String = "i:1", resolvedAt: Long = now,
		resolved: Map<TrackerPosition, EffectiveConstraint> = mapOf(hip to base(now, main)),
		sourceValue: PositionCorrectionPredictorSources? = if (sourcesPresent) sources(physical, at, epoch, s) else null,
		teacherValue: PositionCorrectionTeacherTickValue? = if (teacherPresent) teacher(physical, at, error, a.generation.coerceAtLeast(0), s) else null,
	) = PositionCorrectionRuntimeTick(sequence, now, resolvedAt, s, a, resolved, sourceValue, teacherValue)
	private fun processed(r: PositionCorrectionRuntimeTickResult) = assertIs<PositionCorrectionRuntimeTickResult.Processed>(r)
	private fun near(expected: Vector3, actual: Vector3, epsilon: Float = 2e-5f) {
		assertEquals(expected.x, actual.x, epsilon); assertEquals(expected.y, actual.y, epsilon); assertEquals(expected.z, actual.z, epsilon)
	}
	private inner class SpySink(val actual: ConstraintIkWriteback? = null) : PositionCorrectionRuntimeSolverSink {
		var projections = 0; var writes = 0
		var unavailable = false; var rejected = false; var throwOnWrite = false
		var projectedAssignment: TrackerBodyAssignments.Snapshot? = null
		var appliedAssignment: TrackerBodyAssignments.Snapshot? = null
		var appliedMap: Map<TrackerPosition, SolverEffectiveConstraint>? = null
		val events = mutableListOf<String>()
		override fun projectMainEffectiveHipTarget(base: EffectiveConstraint, assignment: TrackerBodyAssignments.Snapshot,
			nowNanos: Long): MainEffectiveHipTargetResult {
			projections++; events += "projection"; projectedAssignment = assignment
			return if (unavailable) MainEffectiveHipTargetResult.Unavailable(SolverTargetFailure.CALIBRATION_INVALID)
				else actual?.projectMainEffectiveHipTarget(base, assignment, nowNanos) ?: MainEffectiveHipTarget.project(base, assignment, nowNanos, null)
		}
		override fun applySolver(constraints: Map<TrackerPosition, SolverEffectiveConstraint>, assignment: TrackerBodyAssignments.Snapshot): Map<TrackerPosition, ConstraintIkWriteback.ApplyResult> {
			writes++; events += "writeback"; appliedAssignment = assignment; appliedMap = constraints
			if (throwOnWrite) error("unexpected sink failure")
			return if (rejected) constraints.mapValues { ConstraintIkWriteback.ApplyResult.Rejected(SolverTargetFailure.INPUT_INVALID) }
				else actual?.applySolver(constraints, assignment) ?: constraints.mapValues { ConstraintIkWriteback.ApplyResult.Applied }
		}
	}
	private inner class Harness(val sink: SpySink = SpySink(), policy: MainDecoupledHipPredictorPolicy = predictionPolicy) {
		var predicts = 0; var throwOnPredict = false
		val inputs = mutableListOf<MainDecoupledHipInput>()
		val predictor = PureMainDecoupledHipPredictor(policy)
		val runtime = PositionCorrectionRuntimeOrchestrator(MainDecoupledHipPredictor {
			predicts++; inputs += it; sink.events += "predict"
			if (throwOnPredict) error("unexpected predictor failure")
			predictor.predict(it)
		}, pairing, tuning(), PositionCorrectionReacquisitionTuning(duration), sink)
		fun run(t: PositionCorrectionRuntimeTick) = processed(runtime.process(t)).also {
			assertEquals(1, it.calls.observe + it.calls.gap)
			assertEquals(1, it.calls.continuity); assertEquals(1, it.calls.writeback)
			assertSame(t.assignment, sink.appliedAssignment); assertSame(it.solverConstraints, sink.appliedMap)
			assertSame(it.continuity.constraint, it.solverConstraints[hip])
			if (it.learning.state.lineage != null) assertEquals(t.nowNanos, it.learning.state.lastStateAdvanceAtNanos)
		}
		fun trained(): PositionCorrectionRuntimeTickResult.Processed {
			run(tick(1)); return run(tick(2)).also { assertEquals(PositionCorrectionPhase.TRACKING, it.learning.state.phase) }
		}
	}

	@Test fun coldMainHasExactCountsAndSingleSnapshot() {
		val h = Harness(); val t = tick(7); val r = h.run(t)
		assertEquals(PositionCorrectionRuntimeCallCounts(1, 1, 1, 0, 0, 1, 1, 1), r.calls)
		assertEquals(listOf("predict", "projection", "writeback"), h.sink.events)
		assertEquals(1, h.predicts); assertEquals(1, h.sink.projections); assertEquals(1, h.sink.writes)
		assertSame(t.assignment, h.sink.projectedAssignment)
		assertEquals(7, r.prediction!!.provenance!!.predictionSequence)
		assertSame(r.prediction.provenance.epoch, r.learning.state.lineage!!.predictionEpoch)
		assertEquals(PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, r.continuity.phase)
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun teacherAbsenceIsLegalIndependentlyOfMain(main: Boolean) {
		val h = Harness(); h.trained(); val r = h.run(tick(3, main = main, teacherPresent = false))
		assertEquals(PositionCorrectionRuntimeCallCounts(1, 0, 0, 1, if (main) 0 else 1, if (main) 1 else 0, 1, 1), r.calls)
		assertEquals(PositionCorrectionPhase.HOLDING, r.learning.state.phase)
		assertNull(r.measurement)
	}
	@Test fun teacherWithNoResolverPositionCanMeasureAndApply() {
		val h = Harness(); h.trained(); val r = h.run(tick(3, main = false))
		assertEquals(PositionCorrectionRuntimeCallCounts(1, 1, 1, 0, 1, 0, 1, 1), r.calls)
		assertIs<PositionCorrectionApplicationResult.Ready>(r.application)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun pairingRejectAdvancesExactlyOneGap(which: Int) {
		val h = Harness(); h.trained(); val now = 1_300_000_000L
		val value = teacher(3, if (which == 0) now - 200_000_000 else if (which == 1) now - 30_000_000 else now)
		val changed = when (which) {
			2 -> value.copy(expectedEpoch = value.expectedEpoch.copy(sourceEpoch = "current-new-source"))
			3 -> value.copy(expectedEpoch = value.expectedEpoch.copy(mountCalibration = MainTrackerMountCalibrationIdentity("new", "new:1")))
			4 -> value.copy(expectedEpoch = value.expectedEpoch.copy(mappingRevision = 2))
			5 -> value.copy(expectedEpoch = value.expectedEpoch.copy(assignmentGeneration = 4))
			else -> value
		}
		val r = h.run(tick(3, teacherValue = changed))
		val rejection = assertIs<PositionErrorMeasurementResult.Rejected>(r.measurement)
		assertEquals(if (which == 0) PositionTemporalPairingRejectionReason.TEACHER_STALE else if (which == 1)
			PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED else PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH, rejection.pairingReason)
		assertEquals(1, r.calls.measure); assertEquals(0, r.calls.observe); assertEquals(1, r.calls.gap)
	}
	@Test fun learnerOutlierDoesNotAddExplicitGap() {
		val h = Harness(); h.trained(); val r = h.run(tick(3, error = Vector3(10f, 0f, 0f)))
		assertIs<PositionErrorMeasurementResult.Measured>(r.measurement)
		assertEquals(PositionCorrectionLearningReason.MEASUREMENT_RESIDUAL_OUTLIER, r.learning.reason)
		assertEquals(1, r.calls.observe); assertEquals(0, r.calls.gap)
		assertEquals(PositionCorrectionPhase.HOLDING, r.learning.state.phase)
	}
	@Test fun samePhysicalPairNewTickIsLearnerDuplicateWithoutExplicitGap() {
		val h = Harness(); val first = h.run(tick(1)); val r = h.run(tick(2, now = first.nowNanos, physical = 1))
		assertEquals(PositionCorrectionLearningDecision.DUPLICATE, r.learning.decision)
		assertEquals(1, r.calls.observe); assertEquals(0, r.calls.gap); assertEquals(2, h.sink.writes)
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun missingSourcesInvalidatesOldStateWithOrWithoutTeacher(teacher: Boolean) {
		val h = Harness(); assertTrue(h.trained().learning.state.correctionWorld.len() > 0)
		val count = h.predicts; val r = h.run(tick(3, main = false, sourcesPresent = false, teacherPresent = teacher))
		assertEquals(count, h.predicts)
		assertEquals(PositionCorrectionRuntimeCallCounts(0, 0, 0, 1, 0, 0, 1, 1), r.calls)
		assertEquals(PositionCorrectionLearningReason.PREDICTION_CONTEXT_UNAVAILABLE, r.learning.reason)
		assertNull(r.learning.state.lineage); assertEquals(Vector3.NULL, r.learning.state.correctionWorld)
		assertNull(r.solverConstraints[hip]!!.position)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun assemblyPreflightIsTypedWithoutPredictRetry(which: Int) {
		val h = Harness(); val now = 1_100_000_000L; val src = sources(1, now)
		val otherSpace = space.copy(revision = 8)
		val bad = when (which) {
			0 -> src.copy(rawHmd = src.rawHmd.copy(space = otherSpace, provenance = src.rawHmd.provenance.copy(space = otherSpace)))
			1 -> src.copy(rawImu = src.rawImu.copy(space = otherSpace, provenance = src.rawImu.provenance.copy(space = otherSpace)))
			2 -> src.copy(rawHmd = src.rawHmd.copy(provenance = src.rawHmd.provenance.copy(sampleAtNanos = now + 1)))
			3 -> src.copy(rawImu = src.rawImu.copy(provenance = src.rawImu.provenance.copy(sampleAtNanos = now + 1)))
			4 -> src.copy(fixedCalibration = fixed("other-hmd", body.identity.modelId))
			else -> src.copy(fixedCalibration = fixed("hmd", "other-model"))
		}
		val r = h.run(tick(1, sourceValue = bad))
		assertEquals(PositionCorrectionAssemblyFailure.entries[which + 1], assertIs<PositionCorrectionPredictorAssemblyResult.Unavailable>(r.assembly).reason)
		assertEquals(0, h.predicts); assertEquals(0, r.calls.measure); assertEquals(1, r.calls.gap)
	}
	@Test fun unavailablePredictorIsCalledOnceAndInvalidates() {
		val h = Harness(policy = MainDecoupledHipPredictorPolicy(0, 0, 0))
		val r = h.run(tick(1, at = 1_099_999_999))
		assertEquals(1, h.predicts); assertEquals(PredictionValidity.UNAVAILABLE, r.prediction!!.validity)
		assertEquals(0, r.calls.measure); assertEquals(1, r.calls.gap); assertEquals(0, r.calls.application)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
	fun fatalPreflightHasNoStageSideEffectsOrReservation(which: Int) {
		val h = Harness(); h.run(tick(5)); val p = h.predicts; val w = h.sink.writes; val proj = h.sink.projections
		val bad = when (which) {
			0 -> tick(-1, sourcesPresent = false, teacherPresent = false); 1 -> tick(5); 2 -> tick(4); 3 -> tick(6, now = -1, sourcesPresent = false, teacherPresent = false)
			4 -> tick(6, now = 1_499_999_999); 5 -> tick(6, resolvedAt = 0)
			else -> tick(6, a = assignment.copy(generation = -1))
		}
		val r = assertIs<PositionCorrectionRuntimeTickResult.Rejected>(h.runtime.process(bad))
		assertEquals(PositionCorrectionRuntimeRejectionReason.entries[which], r.reason)
		assertEquals(p, h.predicts); assertEquals(w, h.sink.writes); assertEquals(proj, h.sink.projections)
		val next = h.run(tick(6))
		assertEquals(PositionCorrectionLearningDecision.UPDATED, next.learning.decision)
		assertEquals(w + 1, h.sink.writes)
	}
	@Test fun sameNowNewTickAndPredictionSequenceGapsAreLegal() {
		val h = Harness(); val first = h.run(tick(1)); h.run(tick(2, now = first.nowNanos, sourcesPresent = false))
		val r = h.run(tick(9, now = first.nowNanos, physical = 1))
		assertEquals(9, r.prediction!!.provenance!!.predictionSequence); assertEquals(3, h.sink.writes)
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun unexpectedExceptionsReserveTickAndNeverRetry(atPredict: Boolean) {
		val h = Harness(); h.throwOnPredict = atPredict; h.sink.throwOnWrite = !atPredict
		val t = tick(1)
		assertFailsWith<IllegalStateException> { h.runtime.process(t) }
		val p = h.predicts; val w = h.sink.writes
		assertEquals(PositionCorrectionRuntimeRejectionReason.DUPLICATE_TICK,
			assertIs<PositionCorrectionRuntimeTickResult.Rejected>(h.runtime.process(t)).reason)
		assertEquals(p, h.predicts); assertEquals(w, h.sink.writes)
		h.throwOnPredict = false; h.sink.throwOnWrite = false; h.run(tick(2))
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun applicationRejectionsHaveNoRetryAndNoManufacturedPosition(which: Int) {
		val h = Harness(); if (which != 0) h.trained()
		val b = base(1_300_000_000, false)
		val bad = when (which) { 1 -> b.copy(rotation = null); 2 -> b.copy(rotation = b.rotation!!.copy(sourceId = "other")); else -> b }
		val r = h.run(tick(3, main = false, teacherPresent = false, resolved = mapOf(hip to bad)))
		assertIs<PositionCorrectionApplicationResult.Rejected>(r.application)
		assertEquals(1, r.calls.application); assertNull(r.solverConstraints[hip]!!.position)
	}
	@Test fun unavailableMainProjectionCannotFabricateReacquisitionEndpoint() {
		val h = Harness(); h.trained(); h.run(tick(3, main = false, teacherPresent = false)); h.sink.unavailable = true
		val r = h.run(tick(4))
		assertIs<MainEffectiveHipTargetResult.Unavailable>(r.mainProjection)
		assertEquals(PositionCorrectionSolverContinuityPhase.UNAVAILABLE, r.continuity.phase)
		assertNull(r.solverConstraints[hip]!!.position); assertEquals(0, r.calls.application)
	}
	@Test fun partialWritebackRejectionIsReturnedWithoutSecondWrite() {
		val h = Harness(); h.sink.rejected = true; val r = h.run(tick(1))
		assertIs<ConstraintIkWriteback.ApplyResult.Rejected>(r.writeback[hip]); assertEquals(1, h.sink.writes)
		h.sink.rejected = false; assertEquals(ConstraintIkWriteback.ApplyResult.Applied, h.run(tick(2)).writeback[hip])
	}
	@Test fun preservesAllNonHipComponentsAndFreezesInputAndEvidenceMaps() {
		val targets = listOf(TrackerPosition.HEAD, TrackerPosition.LEFT_FOOT, TrackerPosition.RIGHT_FOOT)
		val original = linkedMapOf(hip to base(1_100_000_000))
		for (target in targets) original[target] = EffectiveConstraint(target,
			if (target == TrackerPosition.RIGHT_FOOT) null else ResolvedComponent(Vector3(1f, 2f, 3f), "source:$target", ObservationQuality.DEGRADED, 20),
			ResolvedComponent(Quaternion.IDENTITY, "rotation:$target", ObservationQuality.TRACKED, 30))
		val relations = assignment.targets.toMutableMap()
		val t = tick(1, resolved = original, a = assignment.copy(targets = relations))
		val captured = original.toMap(); original.clear(); relations.clear()
		val h = Harness(); val r = h.run(t)
		for (target in targets) {
			val expected = captured.getValue(target); val actual = r.solverConstraints.getValue(target)
			assertSame(expected.position, actual.position); assertSame(expected.rotation, actual.rotation)
			assertEquals(expected.solverConstraint(), actual)
		}
		assertEquals(assignment, t.assignment)
		@Suppress("UNCHECKED_CAST")
		fun <K, V> rejectMutation(map: Map<K, V>, key: K, value: V) {
			assertFailsWith<UnsupportedOperationException> { (map as MutableMap<K, V>)[key] = value }
		}
		rejectMutation(t.resolvedConstraints, hip, base(0)); rejectMutation(t.assignment.targets, hip, assignment.targets.getValue(hip))
		rejectMutation(r.solverConstraints, hip, SolverEffectiveConstraint(hip)); rejectMutation(r.writeback, hip, ConstraintIkWriteback.ApplyResult.Paused)
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun contextChangeInvalidatesCorrectionAndAnchorInSameTick(which: Int) {
		val h = Harness(); h.trained(); h.run(tick(3, main = false, teacherPresent = false))
		val r = h.run(tick(4, main = false, teacherPresent = false,
			epoch = if (which == 0) "i:2" else "i:1", a = if (which == 1) assignment.copy(generation = 4) else assignment,
			s = if (which == 2) space.copy(revision = 8) else space))
		assertEquals(PositionCorrectionLearningReason.PREDICTION_EPOCH_CHANGED, r.learning.reason)
		assertEquals(Vector3.NULL, r.learning.state.correctionWorld); assertNull(r.learning.state.lineage)
		assertIs<PositionCorrectionApplicationResult.Rejected>(r.application); assertNull(r.continuity.constraint.position)
		val next = h.run(tick(5, teacherPresent = false)); assertEquals(PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, next.continuity.phase)
	}
	@Test fun deterministicReplayMatchesEveryStageStateAndMap() {
		val a = Harness(); val b = Harness()
		val ticks = listOf(tick(1), tick(2), tick(3, main = false, teacherPresent = false),
			tick(4, now = 2_000_000_000, main = false, teacherPresent = false), tick(5, now = 2_100_000_000),
			tick(6, now = 2_300_000_000), tick(7, now = 2_500_000_000), tick(8, now = 2_600_000_000, main = false, sourcesPresent = false))
		for (t in ticks) {
			val x = a.run(t); val y = b.run(t)
			assertEquals(x.assembly, y.assembly); assertEquals(x.prediction?.position, y.prediction?.position)
			assertEquals(x.prediction?.provenance, y.prediction?.provenance); assertEquals(x.measurement, y.measurement)
			assertEquals(x.learning, y.learning); assertEquals(x.application, y.application); assertEquals(x.continuity, y.continuity)
			assertEquals(x.mainProjection?.javaClass, y.mainProjection?.javaClass)
			assertEquals((x.mainProjection as? MainEffectiveHipTargetResult.Available)?.target?.position,
				(y.mainProjection as? MainEffectiveHipTargetResult.Available)?.target?.position)
			assertEquals(x.calls, y.calls); assertEquals(x.solverConstraints, y.solverConstraints); assertEquals(x.writeback, y.writeback)
		}
	}

	private inner class ActualFixture : AutoCloseable {
		val trackers = TestTrackerSet()
		val manager: HumanPoseManager
		val writeback: ConstraintIkWriteback
		val skeleton get() = manager.skeleton
		val solver get() = skeleton.ikSolver
		val sink: SpySink
		val h: Harness
		init {
			trackers.head.position = Vector3(0f, 1.7f, 0f)
			manager = HumanPoseManager(listOf(trackers.head, trackers.hip)); manager.setLegTweaksEnabled(false)
			solver.enabled = false; manager.update(); writeback = ConstraintIkWriteback(skeleton)
			val raw = base(0).copy(position = ResolvedComponent(skeleton.hipBone.getTailPosition() + Vector3(.3f, -.2f, .15f),
				"main", ObservationQuality.TRACKED, 0), rotation = ResolvedComponent(Quaternion.rotationAroundXAxis(.7f), "main", ObservationQuality.TRACKED, 0))
			writeback.apply(mapOf(hip to raw), assignment); manager.update(); solver.resetOffsets(); solver.enabled = true; manager.update()
			assertTrue(calibration().offset.len() > .1f); assertNotEquals(Quaternion.IDENTITY, calibration().rotationOffset)
			sink = SpySink(writeback); h = Harness(sink)
		}
		fun calibration() = assertNotNull(solver.calibrationFor("monaka-private:HIP:main", hip.bodyPart))
		fun run(t: PositionCorrectionRuntimeTick): PositionCorrectionRuntimeTickResult.Processed = h.run(t).also {
			val c = it.continuity.constraint
			if (c.position != null) {
				val expected = if (c.positionReference == SolverPositionReference.IK_EFFECTIVE_TARGET) c.position.value
					else c.position.value + assertNotNull(SolverCalibrationTransform.rotatedOffset(c.rotation!!.value, calibration()))
				val actual = assertNotNull(writeback.effectivePositionTargetSnapshot(hip)); near(expected, actual)
				println("6G actual IK tick=${t.tickSequence} phase=${it.continuity.phase} learning=${it.learning.state.phase} expected=$expected actual=$actual calls=${it.calls}")
			} else assertNull(writeback.effectivePositionTargetSnapshot(hip))
			manager.update()
		}
		override fun close() = writeback.close()
	}
	@Test fun actualIkMainValidLossHoldDecayReturnMidpointCompletionAndContextLossE2E(): Unit = ActualFixture().use { f ->
		val cal = f.calibration(); f.run(tick(1)); val trained = f.run(tick(2))
		assertEquals(PositionCorrectionPhase.TRACKING, trained.learning.state.phase)
		val proxy = f.skeleton.hipTracker; val rebuilds = f.writeback.topologyRebuilds
		val held = f.run(tick(3, main = false, teacherPresent = false))
		assertEquals(PositionCorrectionPhase.HOLDING, held.learning.state.phase)
		near(trained.learning.state.correctionWorld, assertIs<PositionCorrectionApplicationResult.Ready>(held.application).candidate.correctionWorld)
		val decayed = f.run(tick(4, now = 2_000_000_000, main = false, teacherPresent = false))
		assertEquals(PositionCorrectionPhase.DECAYING, decayed.learning.state.phase)
		assertTrue(decayed.learning.state.correctionWorld.len() < held.learning.state.correctionWorld.len())
		val anchor = assertIs<PositionCorrectionApplicationResult.Ready>(decayed.application).candidate.correctedPosition
		var previousCorrection = decayed.learning.state.correctionWorld
		for ((seq, now, u) in listOf(Triple(5L, 2_100_000_000L, 0f), Triple(6L, 2_300_000_000L, .5f), Triple(7L, 2_500_000_000L, 1f))) {
			val r = f.run(tick(seq, now)); val main = assertIs<MainEffectiveHipTargetResult.Available>(r.mainProjection).target.position
			val expected = anchor * (1 - u) + main * u
			near(expected, assertNotNull(f.writeback.effectivePositionTargetSnapshot(hip)))
			assertEquals(if (u < 1) PositionCorrectionSolverContinuityPhase.REACQUIRING else PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, r.continuity.phase)
			assertEquals(if (u < 1) SolverPositionReference.IK_EFFECTIVE_TARGET else SolverPositionReference.TRACKER_ORIGIN, r.continuity.constraint.positionReference)
			assertTrue((r.learning.state.correctionWorld - previousCorrection).len() <= .05001f)
			previousCorrection = r.learning.state.correctionWorld
		}
		assertSame(proxy, f.skeleton.hipTracker); assertEquals(rebuilds, f.writeback.topologyRebuilds); assertEquals(cal, f.calibration())
		val lost = f.run(tick(8, now = 2_600_000_000, main = false, sourcesPresent = false))
		assertEquals(PositionCorrectionLearningReason.PREDICTION_CONTEXT_UNAVAILABLE, lost.learning.reason)
		assertNull(lost.continuity.constraint.position); assertNull(f.writeback.effectivePositionTargetSnapshot(hip)); assertEquals(cal, f.calibration())
		assertEquals(8, f.sink.writes); assertEquals(5, f.sink.projections)
	}
	@Test fun actualSinkConstructorRemainsExplicitAndUsesSameBoundary(): Unit = ActualFixture().use { f ->
		val runtime = PositionCorrectionRuntimeOrchestrator(predictionPolicy, pairing, tuning(), PositionCorrectionReacquisitionTuning(duration), f.writeback)
		val r = processed(runtime.process(tick(1)))
		near(assertIs<MainEffectiveHipTargetResult.Available>(r.mainProjection).target.position,
			assertNotNull(f.writeback.effectivePositionTargetSnapshot(hip)))
	}
	@Test fun actualSinkPartialRejectionCommitsOnceAndRecoversNextTick(): Unit = ActualFixture().use { f ->
		val head = TrackerPosition.HEAD
		val a = assignment.copy(targets = assignment.targets + (head to MainTrackerAssignment(TrackerReference("head"))))
		val bad = EffectiveConstraint(head, rotation = ResolvedComponent(Quaternion(0f, 0f, 0f, 0f), "head", ObservationQuality.TRACKED, 1))
		val r = f.h.run(tick(1, a = a, resolved = mapOf(hip to base(1_100_000_000), head to bad)))
		assertEquals(ConstraintIkWriteback.ApplyResult.Applied, r.writeback[hip])
		assertEquals(SolverTargetFailure.INPUT_INVALID, assertIs<ConstraintIkWriteback.ApplyResult.Rejected>(r.writeback[head]).reason)
		assertEquals(1, f.sink.writes); assertNotNull(f.writeback.effectivePositionTargetSnapshot(hip))
		val good = bad.copy(rotation = bad.rotation!!.copy(value = Quaternion.IDENTITY))
		val next = f.h.run(tick(2, a = a, resolved = mapOf(hip to base(1_200_000_000), head to good)))
		assertEquals(ConstraintIkWriteback.ApplyResult.Applied, next.writeback[head]); assertEquals(2, f.sink.writes)
	}
	@Test fun sourceAuditHasNoAcquisitionClockCallbackOrSecondWrite() {
		val path = Path.of("src/main/java/dev/monaka/tracking/PositionCorrectionRuntimeOrchestrator.kt")
		val source = Files.readString(path)
		for (forbidden in listOf("MonakaRuntime", "ConstraintPipeline", "ObservationStore", "ObservationBackendRunner",
			"RawHmdPoseAdmission", "OpenVR", "ProtobufBridge", "SkeletonConfigManager", "System.nanoTime", "System.currentTimeMillis",
			"Instant.now", "resetOffsets", "refreshConstraintInputs", "coroutine", "Future", "catch (", "Tracker(")) assertFalse(source.contains(forbidden), forbidden)
		val window = source.substringAfter("val selected =").substringBefore("val receipt =")
		assertFalse(window.contains("sink.")); assertFalse(window.contains("predictor.")); assertFalse(window.contains("learningLaw."))
		assertEquals(1, Regex("sink\\.applySolver\\(").findAll(source).count())
		assertEquals(1, Regex("continuity\\.select\\(").findAll(source).count())
		assertEquals(1, Regex("learningLaw\\.observe\\(").findAll(source).count())
		assertEquals(1, Regex("learningLaw\\.advanceWithoutMeasurement\\(").findAll(source).count())
		assertFalse(Files.readString(Path.of("src/main/java/dev/monaka/tracking/MonakaRuntime.kt")).contains("PositionCorrectionRuntimeOrchestrator"))
	}
}
