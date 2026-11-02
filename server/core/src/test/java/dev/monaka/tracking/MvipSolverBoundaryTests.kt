package dev.monaka.tracking

import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.Validity
import dev.slimevr.tracking.processor.HumanPoseManager
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

/** Encoded public input through the real runtime, production owner and existing IK. No sockets. */
class MvipSolverBoundaryTests {
	private val hip = TrackerPosition.HIP

	private fun fixture(): MtpPose = assertIs<MtpPose>(
		assertIs<DecodeResult.Success>(
			MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()),
		).value,
	)

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
}
