package dev.monaka.tracking

import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigToggles
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LegacyFullInputSelectionTests {
	private fun configuration() = MonakaConfiguration(
		((MonakaCodec.decodeEnvelope(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").toFile().readBytes()) as DecodeResult.Success).value as MtpPose).coordinate_space,
	)

	private fun tracker(id: Int, role: TrackerPosition?, positional: Boolean = true) = Tracker(
		null, id, "opaque-input-$id", trackerPosition = role, hasPosition = positional, hasRotation = true,
		allowReset = false, allowMounting = false, allowFiltering = false, trackRotDirection = false,
	).also { it.status = TrackerStatus.OK; it.setRotation(Quaternion.IDENTITY) }

	@Test
	fun declaredFullReassignmentPreservesBothHandsThroughActualRuntimeAndSkeleton(@TempDir dir: Path) {
		val head = tracker(1, TrackerPosition.HEAD).also { it.position = Vector3(0f, 1.7f, 0f) }
		val left = tracker(2, TrackerPosition.LEFT_HAND).also { it.position = Vector3(-0.45f, 1.2f, -0.3f) }
		val right = tracker(3, TrackerPosition.RIGHT_HAND).also { it.position = Vector3(0.55f, 1.15f, -0.4f) }
		val inputs = listOf(head, left, right)
		val manager = HumanPoseManager(inputs).also {
			it.setLegTweaksEnabled(false)
			it.setToggle(SkeletonConfigToggles.FORCE_ARMS_FROM_HMD, false)
		}
		val config = configuration()
		val path = dir.resolve("inputs.json")
		config.save(path)
		var now = 1_000_000_000L
		MonakaRuntime({ inputs }, config.space, config.assignments, clock = { now }).use { runtime ->
			val api = MonakaInputConfigurationApi(config, runtime.mtp, { inputs }, path)
			val original = Files.readAllBytes(path)
			assertTrue(api.snapshot(now).assignments.isEmpty()) // Legacy associations are not migrated by reading.
			api.saveAndApply(api.editSources(TrackerPosition.HIP, null, null, true, 0, now))
			assertContentEquals(original, Files.readAllBytes(path)) // No-op is also read-only.
			for (hand in listOf(left, right)) {
				val candidate = api.snapshot(now).candidates.single { it.reference == TrackerReference.slime(hand.name) }
				assertEquals(setOf("position", "orientation"), candidate.capabilities!!.components)
				assertTrue(candidate.sixDofSelectable)
				assertTrue(candidate.sixDofUsableNow)
				api.saveAndApply(api.editSources(hand.trackerPosition!!, candidate.reference, candidate.reference, false, api.snapshot(now).generation, now))
			}
			assertEquals(setOf(TrackerPosition.LEFT_HAND, TrackerPosition.RIGHT_HAND), config.assignments.snapshot().targets.keys)
			assertFalse(config.assignments.snapshot().targets.containsKey(TrackerPosition.HEAD))
			ConstraintIkWriteback(manager.skeleton).use { writeback ->
				for (frame in 0..2) {
					now += 10_000_000L
					left.position += Vector3(-0.01f, 0.01f, 0f)
					right.position += Vector3(0.02f, 0f, -0.01f)
					val resolved = runtime.tick()
					assertEquals(left.position, resolved.getValue(TrackerPosition.LEFT_HAND).position!!.value)
					assertEquals(right.position, resolved.getValue(TrackerPosition.RIGHT_HAND).position!!.value)
					writeback.apply(resolved, config.assignments.snapshot())
					manager.skeleton.updatePose()
					assertTrue((manager.skeleton.leftHandTrackerBone.getPosition() - left.position).len() < 1e-5f)
					assertTrue((manager.skeleton.rightHandTrackerBone.getPosition() - right.position).len() < 1e-5f)
					assertTrue((manager.skeleton.computedLeftHandTracker!!.position - left.position).len() < 1e-5f)
					assertTrue((manager.skeleton.computedRightHandTracker!!.position - right.position).len() < 1e-5f)
				}
			}
			assertEquals(config.assignments.snapshot().targets, MonakaConfiguration.load(path).assignments.snapshot().targets)
		}
	}

	@Test
	fun declarationsRemainSelectableAcrossLossWhileQOnlyAndDuplicateInputsFailClosed(@TempDir dir: Path) {
		val full = tracker(1, null)
		val qOnly = tracker(2, null, positional = false)
		var inputs = listOf(full, qOnly)
		val config = configuration()
		val path = dir.resolve("inputs.json")
		config.save(path)
		val now = 1_000_000_000L
		MonakaRuntime({ inputs }, config.space, config.assignments, clock = { now }).use { runtime ->
			val api = MonakaInputConfigurationApi(config, runtime.mtp, { inputs }, path)
			fun row(t: Tracker) = api.snapshot(now).candidates.single { it.reference == TrackerReference.slime(t.name) }
			assertFalse(row(qOnly).sixDofSelectable)
			assertFails { api.editSources(TrackerPosition.LEFT_HAND, null, TrackerReference.slime(qOnly.name), true, 0, now) }
			full.status = TrackerStatus.OCCLUDED
			assertTrue(row(full).sixDofSelectable)
			assertFalse(row(full).sixDofUsableNow)
			full.status = TrackerStatus.OK
			full.sampleModality = TrackingModality.ROTATION_ONLY
			assertTrue(row(full).sixDofSelectable)
			assertFalse(row(full).sixDofUsableNow)
			assertTrue(row(full).qUsableNow)
			val ref = TrackerReference.slime(full.name)
			api.saveAndApply(api.editSources(TrackerPosition.RIGHT_HAND, ref, null, true, 0, now))
			full.sampleModality = TrackingModality.FULL
			assertNull(runtime.tick().getValue(TrackerPosition.RIGHT_HAND).position) // Explicit clear stays Q-only.
			inputs = listOf(full, full, qOnly)
			assertTrue(row(full).conflict)
			assertFalse(row(full).sixDofSelectable)
			assertNull(row(full).capabilities)
		}
	}
}
