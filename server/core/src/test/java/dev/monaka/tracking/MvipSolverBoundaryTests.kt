package dev.monaka.tracking

import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.Validity
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.BoneType
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

/** Encoded public input through the real runtime, production owner and existing IK. No sockets. */
class MvipSolverBoundaryTests {
	private val hip = TrackerPosition.HIP

	private fun fixture(): MtpPose = assertIs<MtpPose>(
		assertIs<DecodeResult.Success>(
			MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()),
		).value,
	)

	@ParameterizedTest
	@ValueSource(booleans = [false, true])
	fun explicitMainSuppressesCompetingRawBodyThroughLossAndPreservesOtherBodies(steamVrComputedInput: Boolean) {
		var now = 1_000_000_000L
		val source = fixture()
		val key = LogicalTracker(source.source_id, source.tracker_id, source.publisher_id)
		val assignments = TrackerBodyAssignments(mapOf(key to hip))
		val selectedQ = Quaternion(kotlin.math.sqrt(.96f), .2f, 0f, 0f)
		val competingQ = Quaternion(0f, 0f, 1f, 0f)
		fun raw(id: Int, target: TrackerPosition) = Tracker(
			null, id, "external-$id", trackerPosition = target, hasPosition = true, hasRotation = true,
			isComputed = steamVrComputedInput, allowFiltering = false, trackRotDirection = false,
		).also { it.status = TrackerStatus.OK; it.position = Vector3(3f, 1f, 0f); it.setRotation(competingQ) }
		val competing = raw(801, hip)
		val otherBody = raw(802, TrackerPosition.CHEST)
		val head = TestTrackerSet().head.also { it.position = Vector3(0f, 1.7f, 0f) }
		val raw = listOf(head, competing, otherBody)
		val manager = HumanPoseManager(raw)
		manager.setLegTweaksEnabled(false)
		MonakaRuntime({ raw }, source.coordinate_space, assignments, clock = { now }).use { runtime ->
			MonakaSolverComposition(ConstraintIkWriteback(manager.skeleton), ProductionPositionCorrectionGate.NOT_CONFIGURED).use { composition ->
				var sequence = 0L
				fun step(lost: Boolean = false, submit: Boolean = true) {
					if (submit) {
						val next = source.copy(
							sequence = sequence, timestamp_ns = source.timestamp_ns + sequence * 10_000_000,
							sent_at_ns = source.sent_at_ns + sequence * 10_000_000,
							position = if (lost) null else listOf(.1, 1.0, 0.0),
							orientation = if (lost) null else listOf(selectedQ.x.toDouble(), selectedQ.y.toDouble(), selectedQ.z.toDouble(), selectedQ.w.toDouble()),
							modality = if (lost) "none" else "full", validity = Validity(!lost, !lost),
							confidence = Confidence(if (lost) 0.0 else 1.0, if (lost) 0.0 else 1.0),
							tracking_state = if (lost) "lost" else "tracked", input = source.input.copy(sequence = sequence++),
						)
						assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(next)).value, now))
					}
					val tick = runtime.tickSnapshot()
					assertIs<MonakaSolverCommitResult.GenericCommitted>(composition.commitGeneric(tick, tick.constraints))
					manager.skeleton.beginDiagnosticInputFrame(tick.tickSequence)
					manager.update()
					val consumed = manager.skeleton.finishDiagnosticInputFrame()
					assertSame(otherBody, manager.skeleton.chestTracker)
					val targets = manager.skeleton.ikSolver.diagnosticPositionTargets()
					assertTrue(targets.any { it.trackerIdentity == otherBody.name })
					assertTrue(targets.none { it.trackerIdentity == competing.name })
					if (lost) {
						assertNull(tick.constraints[hip]?.rotation)
						assertNull(manager.skeleton.hipTracker)
						assertTrue(targets.none { it.target == hip })
						assertTrue(consumed.none { it.target == hip })
					} else {
						assertEquals(key.observationId, tick.constraints.getValue(hip).position!!.sourceId)
						val selected = assertNotNull(manager.skeleton.hipTracker)
						assertTrue(selected !== competing)
						assertEquals(1, targets.count { it.target == hip })
						assertEquals(selected.name, targets.single { it.target == hip }.trackerIdentity)
						assertTrue(consumed.any { it.target == hip })
						for (input in consumed.filter { it.target == hip }) {
							val actual = Quaternion(assertNotNull(input.qw), assertNotNull(input.qx), assertNotNull(input.qy), assertNotNull(input.qz))
							assertTrue(kotlin.math.abs(actual.unit().dot(selectedQ.unit())) > .9999f)
							assertTrue(kotlin.math.abs(actual.unit().dot(competingQ.unit())) < .01f)
						}
					}
					now += 10_000_000
				}
				step()
				step(lost = true)
				step()
				now += 500_000_001
				step(lost = true, submit = false)
				step()
			}
		}
	}

	@Test
	fun admittedFrameIsCommittedOnceAndLossCannotRetainPositionAcrossRecovery() {
		var now = 1_000_000_000L
		val source = fixture()
		val key = LogicalTracker(source.source_id, source.tracker_id, source.publisher_id)
		val assignments = TrackerBodyAssignments(mapOf(key to hip))
		val trackers = TestTrackerSet()
		trackers.head.position = Vector3(0f, 1.7f, 0f)
		val manager = HumanPoseManager(listOf(trackers.head))
		manager.setLegTweaksEnabled(false)
		manager.skeleton.ikSolver.enabled = false
		manager.update()
		val origin = manager.skeleton.computedHipTracker!!.position

		MonakaRuntime({ listOf(trackers.head) }, source.coordinate_space, assignments, clock = { now }).use { runtime ->
			val writeback = ConstraintIkWriteback(manager.skeleton)
			MonakaSolverComposition(writeback, ProductionPositionCorrectionGate.NOT_CONFIGURED).use { composition ->
				fun pose(sequence: Long, x: Double, modality: String = "full") = source.copy(
					sequence = sequence,
					timestamp_ns = source.timestamp_ns + sequence * 10_000_000,
					sent_at_ns = source.sent_at_ns + sequence * 10_000_000,
					position = if (modality == "full") listOf(x, origin.y.toDouble(), origin.z.toDouble()) else null,
					orientation = if (modality == "none") null else source.orientation,
					modality = modality,
					validity = Validity(modality == "full", modality != "none"),
					confidence = Confidence(if (modality == "full") 1.0 else 0.0, if (modality == "none") 0.0 else 1.0),
					tracking_state = when (modality) {
						"full" -> "tracked"
						"rotation_only" -> "degraded"
						else -> "lost"
					},
					input = source.input.copy(sequence = sequence),
				)
				fun submit(pose: MtpPose) {
					assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(pose)).value, now))
				}
				fun commit(tick: MonakaResolvedTickSnapshot) {
					val receipt = assertIs<MonakaSolverCommitResult.GenericCommitted>(composition.commitGeneric(tick, tick.constraints))
					assertEquals(tick.tickSequence, receipt.tickSequence)
					assertEquals(MonakaSolverCommitOwner.GENERIC_EXISTING, receipt.owner)
					manager.update()
				}
				fun step() = runtime.tickSnapshot().also(::commit)

				submit(pose(0, origin.x.toDouble()))
				step()
				manager.skeleton.ikSolver.resetOffsets()
				manager.skeleton.ikSolver.enabled = true
				step()
				val calibration = manager.skeleton.ikSolver.calibrationSnapshot()
				assertTrue(calibration.isNotEmpty())
				val computed = manager.skeleton.computedHipTracker!!
				val initialX = computed.position.x

				now += 10_000_000
				submit(pose(1, origin.x + .1))
				val pinned = runtime.tickSnapshot()
				// A later queued packet cannot alter the already resolved solver frame.
				submit(pose(2, origin.x + .2))
				commit(pinned)
				assertEquals((origin.x + .1).toFloat(), manager.skeleton.hipTracker!!.position.x)
				assertEquals(key.observationId, pinned.constraints.getValue(hip).position!!.sourceId)
				assertTrue(computed.position.x - initialX > .005f)
				val frozenPosition = manager.skeleton.hipTracker!!.position
				val rejected = assertIs<MonakaSolverCommitResult.Rejected>(composition.commitGeneric(pinned, emptyMap()))
				assertEquals(MonakaSolverCommitRejection.DUPLICATE_TICK, rejected.reason)
				assertFalse(rejected.writebackAttempted)
				assertEquals(frozenPosition, manager.skeleton.hipTracker!!.position)
				step()
				assertEquals((origin.x + .2).toFloat(), manager.skeleton.hipTracker!!.position.x)

				// Rejected malformed input cannot consume a valid adjacent input or kill IK.
				assertFalse(runtime.inbox.receive("{".toByteArray(), now))
				now += 10_000_000
				submit(pose(3, 999.0, "rotation_only"))
				val rotationOnly = step().constraints.getValue(hip)
				assertNull(rotationOnly.position)
				assertNotNull(rotationOnly.rotation)
				assertEquals(ConstraintIkWriteback.ComponentMask(false, true), writeback.masks()[hip])
				assertTrue(kotlin.math.abs(computed.position.x - origin.x) < .001f)
				assertEquals(calibration, manager.skeleton.ikSolver.calibrationSnapshot())

				now += 10_000_000
				submit(pose(4, 999.0, "none"))
				val none = step().constraints[hip]
				assertNull(none?.position)
				assertNull(none?.rotation)
				assertNull(manager.skeleton.hipTracker)
				assertNull(writeback.masks()[hip])

				now += 10_000_000
				submit(pose(5, origin.x + .1))
				val recovered = step()
				assertEquals(key.observationId, recovered.constraints.getValue(hip).position!!.sourceId)
				assertEquals(ConstraintIkWriteback.ComponentMask(true, true), writeback.masks()[hip])
				assertEquals(calibration, manager.skeleton.ikSolver.calibrationSnapshot())
				assertTrue(computed.position.x - initialX > .005f)
				assertEquals(ProductionPositionCorrectionGate.NOT_CONFIGURED, composition.positionCorrectionGate)

				// A valid repeated datagram must not make the old physical sample fresh.
				val last = runtime.mtp.samples().getValue(key)
				now = last.sampleTime + 500_000_001
				submit(last.pose)
				val stale = step()
				assertNull(stale.constraints[hip]?.position)
				assertNull(stale.constraints[hip]?.rotation)
				assertNull(manager.skeleton.hipTracker)
				assertEquals(last.sampleTime, runtime.mtp.samples().getValue(key).sampleTime)
				assertEquals(1, runtime.inbox.diagnostics()["DuplicateOrOldSequence"])
			}
		}
	}

	@ParameterizedTest
	@EnumSource(value = TrackerPosition::class, names = ["CHEST", "LEFT_UPPER_LEG"])
	fun explicitQOnlyConsumesRotationInRealIkAndIgnoresLargePositionChanges(target: TrackerPosition) {
		var now = 1_000_000_000L
		val source = fixture()
		val key = LogicalTracker(source.source_id, source.tracker_id, source.publisher_id)
		val assignments = TrackerBodyAssignments().also {
			it.configureSources(target, TrackerReference.mtp(key), null, true)
		}
		val relation = assignments.snapshot().targets.getValue(target)
		assertEquals(TrackerReference.mtp(key), relation.sourceSelection!!.qSource)
		assertNull(relation.sourceSelection!!.sixDofSource)
		assertEquals(OutputMode.IK, relation.outputMode)
		assertTrue(relation.useAsIkConstraint)
		val trackers = TestTrackerSet()
		trackers.head.position = Vector3(0f, 1.7f, 0f)
		val manager = HumanPoseManager(listOf(trackers.head))
		manager.setLegTweaksEnabled(false)
		manager.skeleton.ikSolver.enabled = true
		manager.update()
		val bone = manager.skeleton.getBone(if (target == TrackerPosition.CHEST) BoneType.CHEST else BoneType.LEFT_UPPER_LEG)
		val q0 = Quaternion.IDENTITY
		val q1 = Quaternion(kotlin.math.sqrt(.96f), .2f, 0f, 0f)

		MonakaRuntime({ listOf(trackers.head) }, source.coordinate_space, assignments, clock = { now }).use { runtime ->
			MonakaSolverComposition(ConstraintIkWriteback(manager.skeleton), ProductionPositionCorrectionGate.NOT_CONFIGURED).use { composition ->
				var sequence = 0L
				fun step(p: List<Double>, q: Quaternion, mode: String): List<HumanSkeleton.ConsumedRotationInput> {
					val next = source.copy(
						sequence = sequence++, timestamp_ns = source.timestamp_ns + sequence * 10_000_000,
						sent_at_ns = source.sent_at_ns + sequence * 10_000_000,
						position = p.takeIf { mode == "full" },
						orientation = listOf(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble()).takeIf { mode != "none" },
						modality = mode, validity = Validity(mode == "full", mode != "none"),
						confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (mode == "none") 0.0 else 1.0),
						tracking_state = when (mode) { "full" -> "tracked"; "none" -> "lost"; else -> "degraded" },
						input = source.input.copy(sequence = sequence),
					)
					assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(next)).value, now))
					val tick = runtime.tickSnapshot()
					val resolved = tick.constraints[target]
					assertNull(resolved?.position)
					if (mode != "none") assertNotNull(resolved?.rotation) else assertNull(resolved?.rotation)
					assertIs<MonakaSolverCommitResult.GenericCommitted>(composition.commitGeneric(tick, tick.constraints))
					manager.skeleton.beginDiagnosticInputFrame(tick.tickSequence)
					manager.update()
					val consumed = manager.skeleton.finishDiagnosticInputFrame().filter { it.target == target }
					if (mode == "none") assertTrue(consumed.isEmpty()) else {
						assertTrue(consumed.isNotEmpty())
						for (input in consumed) {
							assertEquals(tick.tickSequence, input.tick)
							assertFalse(input.hasPosition)
							assertTrue(input.hasRotation)
							assertNull(input.px); assertNull(input.py); assertNull(input.pz)
							assertEquals(listOf(q.x, q.y, q.z, q.w), listOf(input.qx, input.qy, input.qz, input.qw))
							assertEquals(if (target == TrackerPosition.CHEST) "updateSpineTransforms" else "updateLegTransforms", input.readSite)
						}
					}
					now += 10_000_000
					return consumed
				}
				fun computed() = manager.computedTrackers.map { it.position to it.getRotation() }
				val a = step(listOf(0.0, 1.0, 0.0), q0, "full")
				val initial = computed()
				val initialBone = bone.getGlobalRotation()
				val calibration = manager.skeleton.ikSolver.calibrationSnapshot()
				val b = step(listOf(100.0, -40.0, 75.0), q0, "full")
				assertEquals(a.map { it.copy(tick = 0) }, b.map { it.copy(tick = 0) })
				// Deterministic HMD/all other inputs fixed: exact equality is tighter than existing 1e-5 math tolerance.
				assertEquals(initial, computed())
				assertEquals(initialBone, bone.getGlobalRotation())
				step(listOf(-90.0, 80.0, -70.0), q1, "full")
				assertTrue((bone.getGlobalRotation() * initialBone.inv()).angleR() > .01f)
				assertTrue(computed().zip(initial).any { (next, before) -> next != before })
				val rotated = computed()
				step(emptyList(), q1, "rotation_only")
				assertEquals(rotated, computed())
				assertEquals(calibration, manager.skeleton.ikSolver.calibrationSnapshot())
				// An invalid Q removes the consumed constraint rather than retaining the prior Q.
				step(emptyList(), q1, "none")
				step(emptyList(), q1, "rotation_only")
				val last = runtime.mtp.samples().getValue(key)
				now = last.sampleTime + 500_000_001
				val stale = runtime.tickSnapshot()
				assertNull(stale.constraints[target]?.rotation)
				assertIs<MonakaSolverCommitResult.GenericCommitted>(composition.commitGeneric(stale, stale.constraints))
				manager.skeleton.beginDiagnosticInputFrame(stale.tickSequence)
				manager.update()
				assertTrue(manager.skeleton.finishDiagnosticInputFrame().none { it.target == target })
				// Opt-out and arm-without-update expose no historical reads.
				manager.update()
				assertTrue(manager.skeleton.finishDiagnosticInputFrame().isEmpty())
				manager.skeleton.beginDiagnosticInputFrame(999)
				assertTrue(manager.skeleton.finishDiagnosticInputFrame().isEmpty())
			}
		}
	}

}
