package dev.monaka.tracking

import dev.monaka.protocol.v2.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class MtpRotationOnlyDownstreamTests {
	private fun fixture(): MtpPose {
		val decoded = MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes())
		val p = assertIs<MtpPose>(assertIs<DecodeResult.Success>(decoded).value)
		return p.copy(
			publisher_id = "monaka-bridge-local-1", source_id = "vive-local-1", tracker_id = "altra-0",
			input = p.input.copy(source_id = "vive-local-1", device_id = "23:34:e4:5a:fe:39"),
		)
	}

	@Test fun sameMainFullRotationOnlyFullKeepsLiveRotationAndExistingIkCalibrationWithoutFallback() {
		var now = 1_000_000_000L
		val p = fixture()
		val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val assignments = TrackerBodyAssignments(mapOf(key to TrackerPosition.HIP))
		assertNull(assignments.snapshot().targets.getValue(TrackerPosition.HIP).rotationFallbackTracker)
		val trackers = TestTrackerSet()
		trackers.head.position = Vector3(0f, 1.7f, 0f)
		val hpm = HumanPoseManager(listOf(trackers.head))
		hpm.setLegTweaksEnabled(false)
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()
		val baseline = hpm.skeleton.computedHipTracker!!.position
		MonakaRuntime({ listOf(trackers.head) }, p.coordinate_space, assignments, clock = { now }).use { r ->
			ConstraintIkWriteback(hpm.skeleton).use { w ->
				fun step(): EffectiveConstraint {
					val result = r.tick()
					w.apply(result, assignments.snapshot(), r.mtp.historyGeneration)
					hpm.update()
					return result.getValue(TrackerPosition.HIP)
				}
				fun send(sequence: Long, modality: String, angle: Double = .01 * sequence): EffectiveConstraint {
					now += 10_000_000
					val pose = p.copy(
						sequence = sequence, modality = modality,
						timestamp_ns = p.timestamp_ns + sequence * 10_000_000,
						sent_at_ns = p.timestamp_ns + sequence * 10_000_000 + 100,
						position = if (modality == "full") listOf(baseline.x + .1, baseline.y.toDouble(), baseline.z.toDouble()) else null,
						orientation = if (modality == "none") null else listOf(0.0, sin(angle), 0.0, cos(angle)),
						validity = Validity(modality == "full", modality != "none"),
						confidence = Confidence(if (modality == "full") 1.0 else 0.0, if (modality == "none") 0.0 else 1.0),
						tracking_state = when (modality) { "full" -> "tracked"; "rotation_only" -> "degraded"; else -> "lost" },
						input = p.input.copy(sequence = sequence),
					)
					assertTrue(r.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(pose)).value, now))
					return step()
				}
				val first = send(0, "full")
				assertNotNull(first.position); assertEquals(key.observationId, first.rotation!!.sourceId)
				hpm.skeleton.ikSolver.resetOffsets(); hpm.skeleton.ikSolver.enabled = true; step()
				val calibration = hpm.skeleton.ikSolver.calibrationSnapshot()
				assertTrue(calibration.isNotEmpty())
				val history = r.mtp.historyGeneration
				var rotationBuilds = 0
				var prior = first.rotation!!.value
				// More than 500ms of optical loss with live packets must remain usable.
				for (sequence in 1L..60L) {
					val result = send(sequence, "rotation_only")
					assertNull(result.position)
					val rotation = assertNotNull(result.rotation)
					assertEquals(key.observationId, rotation.sourceId)
					assertEquals(ObservationQuality.DEGRADED, rotation.quality)
					assertEquals(now - 100, rotation.observedAtNanos)
					assertEquals(Quaternion(cos(.01 * sequence).toFloat(), 0f, sin(.01 * sequence).toFloat(), 0f), rotation.value)
					assertNotEquals(prior, rotation.value); prior = rotation.value
					assertEquals(ConstraintIkWriteback.ComponentMask(false, true), w.masks()[TrackerPosition.HIP])
					assertEquals(rotation.value, hpm.skeleton.hipTracker!!.getRotation())
					if (sequence == 1L) rotationBuilds = w.topologyRebuilds else assertEquals(rotationBuilds, w.topologyRebuilds)
					assertEquals(history, r.mtp.historyGeneration)
					assertEquals(calibration, hpm.skeleton.ikSolver.calibrationSnapshot())
				}
				val recovered = send(61, "full")
				assertNotNull(recovered.position); assertEquals(key.observationId, recovered.rotation!!.sourceId)
				assertEquals(calibration, hpm.skeleton.ikSolver.calibrationSnapshot())
				val none = send(62, "none"); assertNull(none.position); assertNull(none.rotation)
				assertNull(hpm.skeleton.hipTracker)
				send(63, "rotation_only")
				val last = r.mtp.samples().getValue(key)
				now = last.sampleTime + 499_999_999
				assertNotNull(step().rotation)
				// Duplicate and present metadata do not renew the last sample during silence.
				assertTrue(r.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(last.pose)).value, now))
				val metadata = MtpTrackerState(
					p.version, p.source_id, p.session_id, p.clock_id, 0, last.pose.timestamp_ns, last.pose.sent_at_ns,
					p.timestamp_kind, p.tracker_id, "present", "degraded", p.coordinate_space, p.capabilities,
					"rotation_only", p.publisher_id, null, p.mapping_revision,
				)
				assertTrue(r.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(metadata)).value, now))
				now = last.sampleTime + 500_000_001
				val stale = step(); assertNull(stale.position); assertNull(stale.rotation)
				assertNull(hpm.skeleton.hipTracker)
				assertEquals(last.sampleTime, r.mtp.samples().getValue(key).sampleTime)
				assertEquals(1, r.inbox.diagnostics()["DuplicateOrOldSequence"])
				assertEquals(ObservationQuality.STALE, r.pipeline.observations(now).single { it.sourceId == key.observationId }.rotationQuality)
				assertNotNull(send(64, "full").position)
				assertNull(assignments.snapshot().targets.getValue(TrackerPosition.HIP).rotationFallbackTracker)
			}
		}
	}
}
