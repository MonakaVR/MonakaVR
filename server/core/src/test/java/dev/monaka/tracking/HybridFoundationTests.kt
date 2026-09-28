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

class HybridFoundationTests {
	private fun fixture() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose

	@Test fun solverEnabledTracksMainWhileDirectIsVisibleAndKeepsComputedIdentityOnLoss() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val head = TestTrackerSet().head.also { it.position = Vector3(0f, 1.7f, 0f) }
		val imu = Tracker(null, 194, "solver-enabled-imu", trackerPosition = TrackerPosition.HIP,
			hasPosition = false, hasRotation = true, allowFiltering = false, allowReset = false,
			allowMounting = false, trackRotDirection = false).also { it.status = TrackerStatus.OK }
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), TrackerReference.slime(imu.name), OutputMode.HYBRID)
		}
		val hpm = HumanPoseManager(listOf(head, imu)); hpm.setLegTweaksEnabled(false)
		val skeleton = hpm.skeleton; val solver = skeleton.ikSolver
		solver.enabled = true; hpm.update()
		val computed = skeleton.computedHipTracker!!
		val baseline = computed.position
		var now = 1_000_000_000L
		DirectConstraintOutput(assignments.snapshot(), p.coordinate_space) { 705 }.use { output ->
			val visible = output.trackers.getValue(TrackerPosition.HIP)
			val identity = Triple(visible.id, visible.name, visible.trackerPosition)
			val reader = BackgroundIkPoseReader(skeleton, BackgroundIkAlignment.confirmedSameSpace(p.coordinate_space))
			val controller = OutputContinuityController(TrackerPosition.HIP, ContinuityPolicy.BACKGROUND_IK)
			MonakaRuntime({ listOf(head, imu, visible) }, p.coordinate_space, assignments, clock = { now }).use { runtime ->
				ConstraintIkWriteback(skeleton).use { writeback ->
					fun send(sequence: Long, full: Boolean, x: Float = baseline.x): Pair<ResolvedTrackingPose, OutputPose> {
						now += 10_000_000
						val sample = p.copy(sequence = sequence, timestamp_ns = p.timestamp_ns + sequence * 10_000_000,
							sent_at_ns = p.timestamp_ns + sequence * 10_000_000 + 100,
							modality = if (full) "full" else "rotation_only",
							position = if (full) listOf(x.toDouble(), baseline.y.toDouble(), baseline.z.toDouble()) else null,
							orientation = listOf(0.0, 0.0, 0.0, 1.0), validity = Validity(full, true),
							confidence = Confidence(if (full) 1.0 else 0.0, 1.0),
							tracking_state = if (full) "tracked" else "degraded")
						assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(sample) as EncodeResult.Success).value, now))
						val resolved = runtime.resolvedTrackingPoses(runtime.tick()).getValue(TrackerPosition.HIP)
						writeback.apply(mapOf(TrackerPosition.HIP to resolved.ikConstraint()), assignments.snapshot(), runtime.mtp.historyGeneration)
						hpm.update()
						assertTrue(solver.enabled)
						assertSame(solver, skeleton.ikSolver)
						assertSame(computed, skeleton.computedHipTracker)
						val composed = controller.update(resolved, reader.read(TrackerPosition.HIP, p.coordinate_space, now), now)
						output.applyPoses(mapOf(TrackerPosition.HIP to composed))
						assertSame(visible, output.trackers.getValue(TrackerPosition.HIP))
						assertEquals(identity, Triple(visible.id, visible.name, visible.trackerPosition))
						assertFalse(FeedbackExclusion.accepts(visible))
						return resolved to composed
					}
					send(0, true)
					solver.resetOffsets(); hpm.update()
					val calibration = solver.calibrationSnapshot()
					assertTrue(calibration.isNotEmpty())
					val before = computed.position
					for (sequence in 1L..5L) {
						val (resolved, visiblePose) = send(sequence, true, baseline.x + .02f * sequence)
						assertEquals(resolved.position, visiblePose.position)
						assertEquals(resolved.rotation, visiblePose.rotation)
						assertEquals(OutputPositionSource.RESOLVED_MAIN, visiblePose.positionSource)
						assertEquals(ConstraintIkWriteback.ComponentMask(true, true), writeback.masks()[TrackerPosition.HIP])
					}
					assertTrue(computed.position.x - before.x > .005f, "Enabled IK must follow Main while output remains Direct")
					val lastVisiblePosition = visible.monakaOutputPose!!.position!!.value
					val (lost, transition) = send(6, false)
					assertNull(lost.position)
					assertEquals(lastVisiblePosition, transition.position!!.value)
					assertEquals(ContinuityState.FALLBACK_ACTIVE, controller.state)
					assertEquals(calibration, solver.calibrationSnapshot())
					val rebuilds = writeback.topologyRebuilds
					head.position = Vector3(.05f, 1.7f, 0f)
					val atLoss = computed.position
					send(7, false)
					assertNotEquals(atLoss, computed.position)
					assertEquals(rebuilds, writeback.topologyRebuilds)
					assertEquals(calibration, solver.calibrationSnapshot())
				}
			}
		}
	}

	@Test fun directVisibleOutputKeepsExistingIkCorrectedAndLiveAcrossLossAndRecovery() {
		for (mode in listOf(OutputMode.DIRECT, OutputMode.HYBRID)) {
			val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
			val head = TestTrackerSet().head.also { it.position = Vector3(0f, 1.7f, 0f) }
			val imu = Tracker(null, 92, "imu-fallback", trackerPosition = TrackerPosition.HIP, hasPosition = false, hasRotation = true,
				allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false).also { it.status = TrackerStatus.OK }
			val legImu = Tracker(null, 93, "imu-leg", trackerPosition = TrackerPosition.LEFT_LOWER_LEG, hasPosition = false, hasRotation = true,
				allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false).also { it.status = TrackerStatus.OK }
			val assignments = TrackerBodyAssignments().also {
				it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), TrackerReference.slime(imu.name), mode)
			}
			assertTrue(assignments.snapshot().targets.getValue(TrackerPosition.HIP).useAsIkConstraint)
			val hpm = HumanPoseManager(listOf(head, imu, legImu)); hpm.setLegTweaksEnabled(false)
			hpm.skeleton.ikSolver.enabled = false; hpm.update()
			val computedIdentity = hpm.skeleton.computedHipTracker
			val baseline = hpm.skeleton.computedHipTracker!!.position
			var now = 1_000_000_000L
			var allocations = 0
			DirectConstraintOutput(assignments.snapshot(), p.coordinate_space) { 500 + allocations++ }.use { output ->
				val visible = output.trackers.getValue(TrackerPosition.HIP)
				val identity = Triple(visible.id, visible.name, visible.trackerPosition)
				val reader = BackgroundIkPoseReader(hpm.skeleton, BackgroundIkAlignment.confirmedSameSpace(p.coordinate_space))
				val controller = OutputContinuityController(TrackerPosition.HIP, assignments.snapshot().targets.getValue(TrackerPosition.HIP).continuity)
				MonakaRuntime({ listOf(head, imu, legImu, visible) }, p.coordinate_space, assignments, clock = { now }).use { runtime ->
					ConstraintIkWriteback(hpm.skeleton).use { writeback ->
						fun step(): Pair<ResolvedTrackingPose, OutputPose> {
							val resolved = runtime.resolvedTrackingPoses(runtime.tick())
							val main = resolved.getValue(TrackerPosition.HIP)
							writeback.apply(resolved.mapValues { it.value.ikConstraint() }, assignments.snapshot(), runtime.mtp.historyGeneration)
							hpm.update()
							assertSame(computedIdentity, hpm.skeleton.computedHipTracker)
							val background = reader.read(TrackerPosition.HIP, p.coordinate_space, now)
							val composed = controller.update(main, background, now)
							output.applyPoses(mapOf(TrackerPosition.HIP to composed))
							assertEquals(identity, Triple(visible.id, visible.name, visible.trackerPosition))
							assertSame(visible, output.trackers.getValue(TrackerPosition.HIP))
							assertFalse(FeedbackExclusion.accepts(visible))
							assertTrue(runtime.pipeline.observations(now).none { FeedbackExclusion.isOutput(it.sourceId.removePrefix("slime:")) })
							return main to composed
						}
						fun send(sequence: Long, modality: String, x: Float = baseline.x): Pair<ResolvedTrackingPose, OutputPose> {
							now += 10_000_000
							val pose = p.copy(sequence = sequence, timestamp_ns = p.timestamp_ns + sequence * 10_000_000,
								sent_at_ns = p.timestamp_ns + sequence * 10_000_000 + 100, modality = modality,
								position = if (modality == "full") listOf(x.toDouble(), baseline.y.toDouble(), baseline.z.toDouble()) else null,
								orientation = if (modality == "none") null else listOf(0.0, 0.0, 0.0, 1.0),
								validity = Validity(modality == "full", modality != "none"),
								confidence = Confidence(if (modality == "full") 1.0 else 0.0, if (modality == "none") 0.0 else 1.0),
								tracking_state = when (modality) { "full" -> "tracked"; "rotation_only" -> "degraded"; else -> "lost" })
							assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now))
							return step()
						}
						send(0, "full")
						hpm.skeleton.ikSolver.resetOffsets(); hpm.skeleton.ikSolver.enabled = true; step()
						val calibration = hpm.skeleton.ikSolver.calibrationSnapshot()
						assertTrue(calibration.isNotEmpty())
						val before = hpm.skeleton.computedHipTracker!!.position
						for (seq in 1L..8L) {
							legImu.setRotation(Quaternion(kotlin.math.cos(.01f * seq), kotlin.math.sin(.01f * seq), 0f, 0f))
							val (main, composed) = send(seq, "full", baseline.x + .01f * seq)
							assertSame(legImu, hpm.skeleton.leftLowerLegTracker)
							assertEquals(legImu.getRotation(), hpm.skeleton.leftLowerLegTracker!!.getRotation())
							assertEquals(key.observationId, main.position!!.sourceId)
							assertEquals(main.position, composed.position)
							assertEquals(ConstraintIkWriteback.ComponentMask(true, true), writeback.masks()[TrackerPosition.HIP])
							assertNotNull(hpm.skeleton.hipTracker)
							assertEquals(main.position!!.value, hpm.skeleton.hipTracker!!.position)
						}
						assertTrue(hpm.skeleton.computedHipTracker!!.position.x - before.x > .005f, "Existing IK must respond while visible output stays Direct")
						val lastVisibleBeforeLoss = visible.monakaOutputPose!!.position!!.value
						val (lost, fallback) = send(9, "rotation_only")
						assertFalse(lost.positionValid); assertNull(lost.position)
						assertEquals("slime:${imu.name}", lost.rotation!!.sourceId)
						assertEquals(lost.rotation!!.sourceId, fallback.rotationOwner)
						if (mode == OutputMode.HYBRID) {
							assertEquals(lastVisibleBeforeLoss, fallback.position!!.value) // no loss-edge teleport
							assertEquals(OutputPositionSource.BACKGROUND_IK, fallback.positionSource)
						} else assertNull(fallback.position)
						if (mode == OutputMode.HYBRID) {
							now += 150_000_000
							assertEquals(hpm.skeleton.computedHipTracker!!.position, step().second.position!!.value)
						}
						val builds = writeback.topologyRebuilds
						val lossPosition = hpm.skeleton.computedHipTracker!!.position
						for (seq in 10L..15L) {
							head.position = Vector3(.01f * (seq - 9), 1.7f, 0f)
							send(seq, "rotation_only")
							assertEquals(builds, writeback.topologyRebuilds)
						}
						assertNotEquals(lossPosition, hpm.skeleton.computedHipTracker!!.position)
						assertEquals(calibration, hpm.skeleton.ikSolver.calibrationSnapshot())
						send(16, "full", baseline.x + .08f)
						assertEquals(if (mode == OutputMode.HYBRID) ContinuityState.FALLBACK_ACTIVE else ContinuityState.MAIN_DIRECT, controller.state)
						if (mode == OutputMode.HYBRID) {
							now += 150_000_000
							step(); assertEquals(ContinuityState.FALLBACK_ACTIVE, controller.state) // same FULL sample
							send(17, "full", baseline.x + .08f)
							assertEquals(ContinuityState.REACQUIRING, controller.state)
						}
						assertEquals(calibration, hpm.skeleton.ikSolver.calibrationSnapshot())
						val last = runtime.mtp.samples().getValue(key)
						now = last.sampleTime + 500_000_001
						assertNull(step().first.position)
						head.status = TrackerStatus.TIMED_OUT
						assertNull(step().second.position) // No valid root => no manufactured fallback position.
						assertTrue(reader.framesRead >= 20); assertEquals(1, allocations)
					}
				}
			}
		}
	}

	@Test fun localConfigSeparatesOutputParticipationContinuityAndExplicitAlignment(@TempDir directory: Path) {
		val p = fixture(); val a = TrackerBodyAssignments()
		a.configure(TrackerPosition.HIP, TrackerReference.slime("main"), TrackerReference.slime("fallback"), OutputMode.HYBRID)
		val config = MonakaConfiguration(p.coordinate_space, a, backgroundIkSharedSpace = p.coordinate_space,
			continuityTuning = ContinuityTuning(25, 200, 75))
		val path = directory.resolve("hybrid.json"); config.save(path)
		val loaded = MonakaConfiguration.load(path)
		assertEquals(a.snapshot().targets, loaded.assignments.snapshot().targets)
		assertEquals(config.backgroundIkSharedSpace, loaded.backgroundIkSharedSpace)
		assertEquals(config.continuityTuning, loaded.continuityTuning)
		val mapper = ObjectMapper(); val json = mapper.readTree(path.toFile()) as ObjectNode
		val assignment = json["assignments"][0] as ObjectNode
		assignment.remove(listOf("outputMode", "continuity", "useAsIkConstraint")); json.remove("backgroundIkAlignment"); json.remove("continuityTuning")
		mapper.writeValue(path.toFile(), json)
		val legacy = MonakaConfiguration.load(path)
		assertNull(legacy.backgroundIkSharedSpace)
		assertEquals(ContinuityTuning(), legacy.continuityTuning)
		val relation = legacy.assignments.snapshot().targets.getValue(TrackerPosition.HIP)
		assertEquals(OutputMode.IK, relation.outputMode); assertTrue(relation.useAsIkConstraint); assertEquals(ContinuityPolicy.NONE, relation.continuity)
		assignment.put("outputMode", "direct"); mapper.writeValue(path.toFile(), json)
		assertTrue(MonakaConfiguration.load(path).assignments.snapshot().targets.getValue(TrackerPosition.HIP).useAsIkConstraint)
		assignment.put("useAsIkConstraint", false); mapper.writeValue(path.toFile(), json)
		assertFalse(MonakaConfiguration.load(path).assignments.snapshot().targets.getValue(TrackerPosition.HIP).useAsIkConstraint)
		assignment.put("outputMode", "hybrid"); mapper.writeValue(path.toFile(), json)
		assertFails { MonakaConfiguration.load(path) }
		assertFails { MonakaConfiguration(p.coordinate_space, backgroundIkSharedSpace = p.coordinate_space.copy(revision = 1)) }
		json.putObject("continuityTuning").put("stableFullDwellMs", -1)
		mapper.writeValue(path.toFile(), json)
		assertFails { MonakaConfiguration.load(path) }
		json.putObject("continuityTuning").put("stableFullDwellMss", 25)
		mapper.writeValue(path.toFile(), json)
		assertFails { MonakaConfiguration.load(path) }
	}
}
