package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.monaka.protocol.v2.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class DirectConstraintOutputTests {
	private fun fixture() = assertIs<MtpPose>(assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(
		File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes(),
	)).value)

	@Test fun resolverDrivesDirectFullRotationOnlyNoneRecoveryOwnersAndFreshness() {
		for (externalFallback in listOf(false, true)) {
			var now = 1_000_000_000L
			val p = fixture()
			val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
			val fallback = Tracker(null, 91, "test-fallback", trackerPosition = TrackerPosition.HIP,
				hasPosition = true, hasRotation = true, allowFiltering = false, allowReset = false,
				allowMounting = false, trackRotDirection = false).also {
				it.status = TrackerStatus.OK; it.position = Vector3(99f, 88f, 77f)
				it.setRotation(Quaternion(0f, 0f, 1f, 0f))
			}
			val assignments = TrackerBodyAssignments().also {
				it.configure(TrackerPosition.HIP, TrackerReference.mtp(key),
					if (externalFallback) TrackerReference.slime(fallback.name) else null, OutputMode.DIRECT)
			}
			var ids = 100
			DirectConstraintOutput(assignments.snapshot(), p.coordinate_space) { ids++ }.use { output ->
				val tracker = output.trackers.getValue(TrackerPosition.HIP)
				val identity = Triple(tracker.id, tracker.name, tracker.trackerPosition)
				// Include the output in input enumeration to prove self-feedback exclusion.
				MonakaRuntime({ listOf(fallback, tracker) }, p.coordinate_space, assignments, clock = { now }).use { runtime ->
					fun step(): EffectiveConstraint {
						val resolved = runtime.tick(); output.apply(resolved)
						val constraint = resolved.getValue(TrackerPosition.HIP)
						assertSame(constraint.position, tracker.monakaOutputPose!!.position)
						assertSame(constraint.rotation, tracker.monakaOutputPose!!.rotation)
						assertSame(tracker, output.trackers.getValue(TrackerPosition.HIP))
						assertEquals(identity, Triple(tracker.id, tracker.name, tracker.trackerPosition))
						assertFalse(FeedbackExclusion.accepts(tracker))
						assertTrue(runtime.pipeline.observations(now).none { it.sourceId.contains(DirectConstraintOutput.PREFIX) })
						return constraint
					}
					fun send(seq: Long, mode: String): EffectiveConstraint {
						now += 10_000_000
						val pose = p.copy(sequence = seq, modality = mode,
							timestamp_ns = p.timestamp_ns + seq * 10_000_000, sent_at_ns = p.timestamp_ns + seq * 10_000_000 + 100,
							position = if (mode == "full") listOf(1.0 + seq, 2.0, 3.0) else null,
							orientation = if (mode == "none") null else listOf(0.0, 0.0, 0.0, 1.0),
							validity = Validity(mode == "full", mode != "none"),
							confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (mode == "none") 0.0 else 1.0),
							tracking_state = when (mode) { "full" -> "tracked"; "rotation_only" -> "degraded"; else -> "lost" })
						assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(pose)).value, now))
						return step()
					}
					val full = send(0, "full")
					assertEquals(Vector3(1f, 2f, 3f), tracker.position)
					assertEquals(key.observationId, full.position!!.sourceId)
					assertEquals(key.observationId, full.rotation!!.sourceId)
					assertEquals(full.rotation!!.value, tracker.getRotation())
					val rotation = send(1, "rotation_only")
					assertNull(rotation.position)
					assertEquals(if (externalFallback) "slime:${fallback.name}" else key.observationId, rotation.rotation!!.sourceId)
					assertEquals(if (externalFallback) fallback.getRotation() else Quaternion.IDENTITY, tracker.getRotation())
					val none = send(2, "none")
					assertNull(none.position)
					if (externalFallback) assertEquals("slime:${fallback.name}", none.rotation!!.sourceId) else assertNull(none.rotation)
					assertEquals(Vector3(4f, 2f, 3f), send(3, "full").position!!.value)
					val last = runtime.mtp.samples().getValue(key)
					now = last.sampleTime + 500_000_001
					val expired = step(); assertNull(expired.position)
					if (externalFallback) assertEquals("slime:${fallback.name}", expired.rotation!!.sourceId) else assertNull(expired.rotation)
					output.apply(runtime.tick(), paused = true)
					assertNull(tracker.monakaOutputPose!!.rotation)
					output.close(); assertNull(tracker.monakaOutputPose!!.position)
					assertEquals(101, ids) // exactly one allocation throughout all transitions
				}
			}
		}
	}

	@Test fun explicitSolverOptOutExcludesDirectButDefaultIkStillWritesExistingSkeleton() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val assignments = TrackerBodyAssignments(mapOf(key to TrackerPosition.HIP))
		val head = TestTrackerSet().head.also { it.position = Vector3(0f, 1.7f, 0f) }
		val hpm = HumanPoseManager(listOf(head))
		MonakaRuntime({ listOf(head) }, p.coordinate_space, assignments, clock = { 1_000_000_000L }).use { runtime ->
			assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(p)).value, runtime.clock()))
			val resolved = runtime.tick()
			ConstraintIkWriteback(hpm.skeleton).use { writeback ->
				writeback.apply(resolved, assignments.snapshot()); hpm.update()
				assertEquals(ConstraintIkWriteback.ComponentMask(true, true), writeback.masks()[TrackerPosition.HIP])
				assignments.configure(TrackerPosition.HIP, TrackerReference.mtp(key), outputMode = OutputMode.DIRECT, useAsIkConstraint = false)
				writeback.apply(resolved, assignments.snapshot()); hpm.update()
				assertFalse(writeback.masks().containsKey(TrackerPosition.HIP)); assertNull(hpm.skeleton.hipTracker)
				DirectConstraintOutput(assignments.snapshot(), p.coordinate_space) { 500 }.use { direct ->
					direct.apply(resolved)
					assertEquals(Vector3(1f, 2f, 3f), direct.trackers.getValue(TrackerPosition.HIP).position)
					assertNotEquals(hpm.skeleton.computedHipTracker!!.position, direct.trackers.getValue(TrackerPosition.HIP).position)
				}
			}
		}
	}

	@Test fun configRoundtripDefaultIkAndStrictModeValidation(@TempDir directory: Path) {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val assignments = TrackerBodyAssignments()
		assignments.configure(TrackerPosition.HIP, TrackerReference.mtp(key), TrackerReference.slime("fallback"), OutputMode.DIRECT)
		val config = MonakaConfiguration(p.coordinate_space, assignments)
		val file = directory.resolve("direct.json"); config.save(file)
		assertEquals(assignments.snapshot().targets, MonakaConfiguration.load(file).assignments.snapshot().targets)
		val mapper = ObjectMapper(); val json = mapper.readTree(file.toFile())
		assertEquals(2, json["version"].intValue())
		val item = json["assignments"][0] as ObjectNode
		item.remove("outputMode"); mapper.writeValue(file.toFile(), json)
		assertEquals(OutputMode.IK, MonakaConfiguration.load(file).assignments.snapshot().targets.getValue(TrackerPosition.HIP).outputMode)
		for (invalid in listOf("DIRECT", "bad", "")) {
			item.put("outputMode", invalid); mapper.writeValue(file.toFile(), json)
			assertFails { MonakaConfiguration.load(file) }
		}
		assertFails { assignments.configure(TrackerPosition.WAIST, TrackerReference.slime("other"), outputMode = OutputMode.DIRECT) }
	}
}
