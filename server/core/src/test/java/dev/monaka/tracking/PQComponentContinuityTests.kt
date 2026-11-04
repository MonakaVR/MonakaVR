package dev.monaka.tracking

import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.Validity
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T1-T10 only: admitted public components -> real runtime -> commitNormal -> solver inputs.
 * These in-memory inputs establish software behavior, not physical tracking quality.
 */
class PQComponentContinuityTests {
	private val hip = TrackerPosition.HIP
	private val teacherPosition = Vector3(.3f, 1.2f, 0f)
	private val aRotation = Quaternion.rotationAroundYAxis(.1f)
	private val bRotation = Quaternion.rotationAroundXAxis(.35f)
	private val recoveryNanos = 400_000_000L

	private fun fixturePose(): MtpPose = assertIs<MtpPose>(
		assertIs<DecodeResult.Success>(
			MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()),
		).value,
	)

	private fun near(expected: Vector3, actual: Vector3, tolerance: Float = .0001f) {
		assertTrue((expected - actual).len() < tolerance, "expected=$expected actual=$actual tolerance=$tolerance")
	}

	private data class Frame(
		val tick: MonakaResolvedTickSnapshot,
		val visible: ResolvedTrackingPose,
		val receipt: MonakaSolverCommitResult,
	) {
		fun processed() = assertIs<PositionCorrectionRuntimeTickResult.Processed>(assertIs<PositionCorrectionCommitted>(receipt).result)
	}

	private inner class Fixture(val split: Boolean = false, val qOnly: Boolean = false, legacySelection: Boolean = false) : AutoCloseable {
		val template = fixturePose()
		val a = LogicalTracker("component-A", "tracker-A", "publisher-A")
		val b = LogicalTracker("component-B", "tracker-B", "publisher-B")
		var q = b
		var qSession = template.session_id
		var qCalibration = template.input.session_id
		var qMapping = template.mapping_revision
		var now = 1_000_000_000L
		var sequence = 0L
		val trackers = TestTrackerSet()
		val manager: HumanPoseManager
		val assignments = TrackerBodyAssignments()
		val runtime: MonakaRuntime
		val composition: MonakaSolverComposition

		init {
			trackers.head.position = Vector3(0f, 1.7f, 0f)
			trackers.hip.setRotation(bRotation)
			manager = HumanPoseManager(if (qOnly) listOf(trackers.head, trackers.hip) else listOf(trackers.head))
			manager.setLegTweaksEnabled(false)
			manager.skeleton.ikSolver.enabled = false
			manager.update()
			assignments.configureSources(
				hip,
				if (qOnly) TrackerReference.slime(trackers.hip.name) else TrackerReference.mtp(if (split) q else a),
				if (qOnly) null else TrackerReference.mtp(a),
				true,
			)
			if (legacySelection) assignments.configure(hip, TrackerReference.slime(trackers.hip.name))
			runtime = MonakaRuntime(
				{ if (qOnly) listOf(trackers.head, trackers.hip) else listOf(trackers.head) },
				template.coordinate_space,
				assignments,
				clock = { now },
			)
			composition = MonakaSolverComposition(
				manager.skeleton,
				null,
				{ listOf(trackers.head) },
				{ HipBodyModelSnapshot.create(0f, .1f, .1f, .1f, .1f, .1f) },
				recoveryNanos,
			)
			manager.skeleton.ikSolver.enabled = true
		}

		fun pose(key: LogicalTracker, modality: String, position: Vector3 = teacherPosition): MtpPose {
			val rotation = if (key == a) aRotation else bRotation
			val isQ = key != a
			return template.copy(
				publisher_id = key.publisherId,
				source_id = key.sourceId,
				tracker_id = key.trackerId,
				session_id = if (isQ) qSession else template.session_id,
				sequence = sequence,
				timestamp_ns = template.timestamp_ns + sequence * 100_000_000,
				sent_at_ns = template.timestamp_ns + sequence * 100_000_000,
				position = if (modality == "full") listOf(position.x.toDouble(), position.y.toDouble(), position.z.toDouble()) else null,
				orientation = if (modality == "none") null else listOf(rotation.x.toDouble(), rotation.y.toDouble(), rotation.z.toDouble(), rotation.w.toDouble()),
				modality = modality,
				validity = Validity(modality == "full", modality != "none"),
				confidence = Confidence(if (modality == "full") 1.0 else 0.0, if (modality == "none") 0.0 else 1.0),
				tracking_state = when (modality) {
					"full" -> "tracked"
					"rotation_only" -> "degraded"
					else -> "lost"
				},
				mapping_revision = if (isQ) qMapping else template.mapping_revision,
				input = template.input.copy(
					source_id = key.sourceId,
					device_id = key.trackerId,
					session_id = if (isQ) qCalibration else template.input.session_id,
					sequence = sequence,
				),
			)
		}

		fun step(aMode: String = "full", bMode: String = "full", delta: Long = 100_000_000, position: Vector3 = teacherPosition): Frame {
			now += delta
			sequence++
			if (!qOnly) {
				fun submit(value: MtpPose) {
					val bytes = assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(value)).value
					assertTrue(runtime.inbox.receive(bytes, now), "Public fixture admission")
				}
				submit(pose(a, aMode, position))
				if (split) submit(pose(q, bMode))
			}
			val tick = runtime.tickSnapshot()
			val bodyCorrected = runtime.correctBodyRotations(tick, runtime.resolvedTrackingPoses(tick))
			val visible = runtime.convergeBodyRotations(tick, bodyCorrected).getValue(hip)
			val hmd = RawHmdPoseInput(
				RawSourceIdentity("head-input", RawSourceKind.RAW_HMD, isHmd = true),
				trackers.head.position,
				Quaternion.IDENTITY,
				template.coordinate_space,
				ObservationSampleProvenance(sequence, now, "head:1", "head-calibration:1", 0, template.coordinate_space),
			)
			val receipt = composition.commitNormal(tick, mapOf(hip to visible.ikConstraint()), runtime, hmd)
			assertTrue(receipt.writebackAttempted)
			manager.update()
			val computed = assertNotNull(manager.skeleton.computedHipTracker).position
			assertTrue(listOf(computed.x, computed.y, computed.z).all(Float::isFinite))
			return Frame(tick, visible, receipt)
		}

		fun train(): Frame {
			var frame = step()
			repeat(29) { frame = step() }
			val result = frame.processed()
			assertEquals(PositionCorrectionPhase.TRACKING, result.learning.state.phase)
			assertTrue(result.learning.state.correctionWorld.x > .25f, "Must actually learn a nonzero measured correction")
			assertEquals(PredictionValidity.AVAILABLE, assertNotNull(result.prediction).validity)
			return frame
		}

		fun assertFull(frame: Frame) {
			assertTrue(frame.visible.positionValid)
			assertEquals(a.observationId, frame.visible.positionOwner)
			assertEquals(a.observationId, frame.visible.rotationOwner)
			assertEquals(aRotation, frame.visible.rotation!!.value)
			near(teacherPosition, frame.visible.position!!.value)
			val selected = frame.processed().solverConstraints.getValue(hip)
			assertEquals(a.observationId, selected.position!!.sourceId)
			assertEquals(a.observationId, selected.rotation!!.sourceId)
			assertEquals(ConstraintIkWriteback.ApplyResult.Applied, frame.processed().writeback[hip])
			val proxy = assertNotNull(manager.skeleton.hipTracker)
			assertTrue(proxy.hasPosition && proxy.hasRotation)
			near(teacherPosition, proxy.position)
		}

		fun assertFallback(frame: Frame, rotationOwner: LogicalTracker): Vector3 {
			val result = frame.processed()
			assertNull(frame.visible.position)
			assertEquals(rotationOwner.observationId, frame.visible.rotationOwner)
			assertEquals(PositionCorrectionSolverContinuityPhase.FALLBACK_ACTIVE, result.continuity.phase)
			val prediction = assertNotNull(result.prediction)
			assertEquals(PredictionValidity.AVAILABLE, prediction.validity)
			val predicted = assertNotNull(prediction.position)
			assertEquals(rotationOwner.observationId, assertNotNull(prediction.provenance).epoch.imuSourceId)
			val selected = result.solverConstraints.getValue(hip)
			val position = assertNotNull(selected.position).value
			assertTrue((position - predicted).len() > .25f, "Learned correction must reach the selected solver target")
			near(teacherPosition, position, .001f)
			assertEquals(rotationOwner.observationId, selected.rotation!!.sourceId)
			assertEquals(SolverPositionReference.IK_EFFECTIVE_TARGET, selected.positionReference)
			assertEquals(ConstraintIkWriteback.ApplyResult.Applied, result.writeback[hip])
			val proxy = assertNotNull(manager.skeleton.hipTracker)
			assertTrue(proxy.hasPosition && proxy.hasRotation)
			near(position, proxy.position)
			return position
		}

		override fun close() {
			composition.close()
			runtime.close()
		}
	}

	@Test fun T1_sameSourceFullKeepsPairedMainAndActivatesCorrection(): Unit = Fixture().use { f ->
		val full = f.train()
		f.assertFull(full)
		assertEquals(MonakaSolverCommitOwner.POSITION_CORRECTION, full.receipt.owner)
		assertNotNull(f.composition.lastPositionCorrection)
		assertEquals(f.a.observationId, full.processed().prediction!!.provenance!!.epoch.imuSourceId)
	}

	@Test fun T2_sameSourcePositionLossKeepsCorrectedPredictionWithFreshMainQ(): Unit = Fixture().use { f ->
		f.train()
		// FULL is still a paired admission contract. Q-only validity must use ROTATION_ONLY.
		val malformed = f.pose(f.a, "rotation_only").copy(modality = "full")
		assertFalse(MonakaCodec.encodeEnvelope(malformed) is EncodeResult.Success)
		val proxy = f.manager.skeleton.hipTracker
		val lost = f.step(aMode = "rotation_only")
		f.assertFallback(lost, f.a)
		assertSame(proxy, f.manager.skeleton.hipTracker)
		assertEquals(f.now, lost.visible.rotation!!.observedAtNanos)
	}

	@Test fun T3_sameSourceReacquisitionStartsAtFallbackAndConvergesWithoutSnap(): Unit = Fixture().use { f ->
		f.train()
		val fallback = f.assertFallback(f.step(aMode = "rotation_only"), f.a)
		val recoveredPosition = teacherPosition + Vector3(.2f, 0f, 0f)
		val first = f.step(position = recoveredPosition).processed()
		assertEquals(PositionCorrectionSolverContinuityPhase.REACQUIRING, first.continuity.phase)
		near(fallback, first.solverConstraints.getValue(hip).position!!.value)
		near(fallback, f.manager.skeleton.hipTracker!!.position)
		val halfway = f.step(delta = recoveryNanos / 2, position = recoveredPosition).processed()
		assertEquals(PositionCorrectionSolverContinuityPhase.REACQUIRING, halfway.continuity.phase)
		near((fallback + recoveredPosition) * .5f, halfway.solverConstraints.getValue(hip).position!!.value)
		val complete = f.step(delta = recoveryNanos / 2, position = recoveredPosition).processed()
		assertEquals(PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, complete.continuity.phase)
		near(recoveredPosition, complete.solverConstraints.getValue(hip).position!!.value)
		near(recoveredPosition, f.manager.skeleton.hipTracker!!.position)
		// Also exercise Main reacquisition and B return in the same product tick.
		Fixture(split = true).use { split ->
			split.train()
			split.assertFallback(split.step(aMode = "rotation_only"), split.b)
			val anchor = split.assertFallback(split.step(aMode = "rotation_only", bMode = "none"), split.a)
			val simultaneous = split.step(position = recoveredPosition).processed()
			assertEquals(PositionCorrectionSolverContinuityPhase.REACQUIRING, simultaneous.continuity.phase)
			near(anchor, simultaneous.solverConstraints.getValue(hip).position!!.value)
			near(anchor, split.manager.skeleton.hipTracker!!.position)
			assertEquals(split.b.observationId, simultaneous.prediction!!.provenance!!.epoch.imuSourceId)
		}
	}

	@Test fun T4_qOnlyKeepsExistingRotationPathWithoutPositionCorrectionOrZeroPosition() {
		for (legacy in listOf(false, true)) Fixture(qOnly = true, legacySelection = legacy).use { f ->
		val alive = f.step()
		assertIs<MonakaSolverCommitResult.GenericCommitted>(alive.receipt)
		assertNull(f.composition.lastPositionCorrection)
		assertNull(alive.visible.position)
		assertEquals("slime:${f.trackers.hip.name}", alive.visible.rotationOwner)
		assertFalse(f.manager.skeleton.hipTracker!!.hasPosition)
		f.trackers.hip.status = TrackerStatus.DISCONNECTED
		val lost = f.step()
		assertNull(lost.visible.position)
		assertNull(lost.visible.rotation)
		assertNull(f.manager.skeleton.hipTracker)
		assertNull(f.composition.lastPositionCorrection)
		f.trackers.hip.status = TrackerStatus.OK
		f.trackers.hip.setRotation(bRotation)
		val restored = f.step()
		assertIs<MonakaSolverCommitResult.GenericCommitted>(restored.receipt)
		assertNull(restored.visible.position)
		assertNotNull(restored.visible.rotation)
		assertFalse(f.manager.skeleton.hipTracker!!.hasPosition)
		assertNull(f.composition.lastPositionCorrection)
		}
	}

	@Test fun T5_splitFullKeepsVisiblePairedAWhileBTeachesPredictor(): Unit = Fixture(split = true).use { f ->
		val full = f.train()
		f.assertFull(full)
		assertEquals(f.b.observationId, full.processed().prediction!!.provenance!!.epoch.imuSourceId)
		assertNotEquals(bRotation, full.visible.rotation!!.value)
	}

	@Test fun T6_splitBLossCannotInvalidateFullMainPositionOrRotation(): Unit = Fixture(split = true).use { f ->
		f.train()
		val proxy = f.manager.skeleton.hipTracker
		val lost = f.step(bMode = "none")
		f.assertFull(lost)
		assertSame(proxy, f.manager.skeleton.hipTracker)
		assertTrue(lost.visible.main.positionValid && lost.visible.main.rotationValid)
		assertEquals(f.a.observationId, lost.processed().prediction!!.provenance!!.epoch.imuSourceId)
	}

	@Test fun T7_splitPositionLossUsesBQAndCorrectedPredictionWithoutJump(): Unit = Fixture(split = true).use { f ->
		f.train()
		val lost = f.step(aMode = "rotation_only")
		f.assertFallback(lost, f.b)
		// Complete A loss still uses the admitted independent B orientation.
		f.assertFallback(f.step(aMode = "none"), f.b)
	}

	@Test fun T8_splitQSwitchDuringPositionLossRebindsLineageAndKeepsContinuity(): Unit = Fixture(split = true).use { f ->
		val trained = f.train().processed()
		val teacherAt = assertNotNull(trained.learning.state.lastAccepted).teacherSampleAtNanos
		val oldEpoch = trained.prediction!!.provenance!!.epoch
		val first = f.assertFallback(f.step(aMode = "rotation_only"), f.b)
		val switched = f.step(aMode = "rotation_only", bMode = "none")
		near(first, f.assertFallback(switched, f.a))
		val aResult = switched.processed()
		assertTrue((aResult.prediction!!.position!! - trained.prediction!!.position!!).len() > .1f, "Q switch must actually change the raw position prediction")
		assertNotEquals(oldEpoch, aResult.learning.state.lineage!!.predictionEpoch)
		assertEquals(f.a.observationId, aResult.learning.state.lineage!!.predictionEpoch.imuSourceId)
		assertNull(aResult.learning.state.lastAccepted, "A committed handoff is not an accepted physical teacher pair")
		assertNull(aResult.learning.state.lastObserved)
		assertEquals(teacherAt, aResult.learning.state.lastAcceptedTeacherAtNanos)
		val returned = f.step(aMode = "rotation_only")
		near(first, f.assertFallback(returned, f.b))
		val bResult = returned.processed()
		assertEquals(f.b.observationId, bResult.learning.state.lineage!!.predictionEpoch.imuSourceId)
		assertNull(bResult.learning.state.lastAccepted)
		assertNull(bResult.learning.state.lastObserved)
		assertEquals(teacherAt, bResult.learning.state.lastAcceptedTeacherAtNanos)
		// The switch cannot refresh the original teacher's 2 s hold clock.
		val aged = f.step(aMode = "rotation_only", delta = teacherAt + 2_100_000_000 - f.now).processed()
		assertEquals(PositionCorrectionPhase.DECAYING, aged.learning.state.phase)
		assertTrue(aged.learning.state.correctionWorld.len() < bResult.learning.state.correctionWorld.len())
		assertNotNull(aged.solverConstraints.getValue(hip).position)
		// Simultaneous P/B loss starts directly from committed FULL, with no earlier fallback tick.
		Fixture(split = true).use { simultaneous ->
			val full = simultaneous.train().processed()
			val lost = simultaneous.step(aMode = "rotation_only", bMode = "none")
			simultaneous.assertFallback(lost, simultaneous.a)
			assertTrue((lost.processed().prediction!!.position!! - full.prediction!!.position!!).len() > .1f)
			assertNull(lost.processed().learning.state.lastAccepted)
			assertEquals(full.learning.state.lastAcceptedTeacherAtNanos, lost.processed().learning.state.lastAcceptedTeacherAtNanos)
		}
	}

	@Test fun T9_allQLossRemovesStaleConstraintsWithoutManufacturingZeroOrIdentity(): Unit = Fixture(split = true).use { f ->
		f.train()
		f.assertFallback(f.step(aMode = "none"), f.b)
		val lost = f.step(aMode = "none", bMode = "none")
		assertNull(lost.visible.position)
		assertNull(lost.visible.rotation)
		val selected = lost.processed().solverConstraints.getValue(hip)
		assertNull(selected.position)
		assertNull(selected.rotation)
		assertEquals(PositionCorrectionSolverContinuityPhase.UNAVAILABLE, lost.processed().continuity.phase)
		assertNull(f.manager.skeleton.hipTracker)
		assertNull(f.step(aMode = "none", bMode = "none").processed().solverConstraints.getValue(hip).rotation)
	}

	@Test fun T10_fullMainSurvivesEveryQContextChangeWithoutOldLearningLineage() {
		for (change in listOf("identity", "source_epoch", "calibration_epoch", "mapping_revision", "assignment_generation")) {
			Fixture(split = true).use { f ->
				val before = f.train().processed()
				val oldLineage = assertNotNull(before.learning.state.lineage)
				var delta = 100_000_000L
				when (change) {
					"identity" -> {
						f.q = LogicalTracker("component-C", "tracker-C", "publisher-C")
						f.assignments.configureSources(hip, TrackerReference.mtp(f.q), TrackerReference.mtp(f.a), true)
					}
					"source_epoch" -> {
						f.qSession = "33333333-3333-3333-3333-333333333333"
						delta = 600_000_000 // Honor the public backend's active-session lease.
					}
					"calibration_epoch" -> f.qCalibration = "44444444-4444-4444-4444-444444444444"
					"mapping_revision" -> f.qMapping++
					"assignment_generation" -> {
						f.assignments.configureSources(hip, TrackerReference.mtp(f.a), TrackerReference.mtp(f.a), true)
						f.assignments.configureSources(hip, TrackerReference.mtp(f.b), TrackerReference.mtp(f.a), true)
					}
				}
				val frame = f.step(delta = delta)
				f.assertFull(frame)
				val result = frame.processed()
				val lineage = assertNotNull(result.learning.state.lineage, change)
				assertNotEquals(oldLineage, lineage, change)
				assertEquals(result.prediction!!.provenance!!.epoch, lineage.predictionEpoch)
				assertEquals(f.q.observationId, lineage.predictionEpoch.imuSourceId)
				assertEquals(frame.tick.assignment.generation, lineage.predictionEpoch.assignmentGeneration)
				assertEquals(f.now, assertNotNull(result.learning.state.lastAccepted).teacherSampleAtNanos)
				assertEquals(Vector3.NULL, result.learning.state.correctionWorld, "New context must not inherit old correction: $change")
			}
		}
	}
}
