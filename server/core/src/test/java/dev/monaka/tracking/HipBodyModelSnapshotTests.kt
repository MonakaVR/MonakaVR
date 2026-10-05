package dev.monaka.tracking

import dev.slimevr.config.ConfigManager
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.processor.config.SkeletonConfigToggles
import dev.slimevr.tracking.processor.config.SkeletonConfigValues
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class HipBodyModelSnapshotTests {
	private val relevant = listOf(SkeletonConfigOffsets.HEAD, SkeletonConfigOffsets.NECK,
		SkeletonConfigOffsets.UPPER_CHEST, SkeletonConfigOffsets.CHEST,
		SkeletonConfigOffsets.WAIST, SkeletonConfigOffsets.HIP)
	private fun capture(config: SkeletonConfigManager) =
		assertIs<HipBodyModelSnapshotResult.Available>(SlimeHipBodyModelSnapshotSource.captureOffline(config)).snapshot
	private fun fields(snapshot: HipBodyModelSnapshot) = listOf(snapshot.headShift, snapshot.neckLength,
		snapshot.upperChestLength, snapshot.chestLength, snapshot.waistLength, snapshot.hipLength)

	@Test fun defaultsMatchAllEffectiveCenterChainOffsets() {
		val config = SkeletonConfigManager(false)
		val snapshot = capture(config)
		assertEquals(relevant.map { config.getOffset(it) }, fields(snapshot))
		assertEquals("monaka:slimevr:hip-body-model:v1", snapshot.identity.modelId)
		assertEquals("hip-body-v1;headShift=3dcccccd;neckLength=3dcccccd;upperChestLength=3e23d70a;" +
			"chestLength=3e23d70a;waistLength=3e4ccccd;hipLength=3d23d70a;", snapshot.identity.epoch)
	}

	@Test fun everyRelevantEffectiveFieldIndividuallyChangesContentEpoch() {
		for ((index, key) in relevant.withIndex()) {
			val config = SkeletonConfigManager(false)
			val before = capture(config)
			config.setOffset(key, config.getOffset(key) + .03125f)
			val after = capture(config)
			assertEquals(config.getOffset(key), fields(after)[index], key.name)
			assertNotEquals(before.identity.epoch, after.identity.epoch, key.name)
			assertEquals(fields(before).filterIndexed { i, _ -> i != index },
				fields(after).filterIndexed { i, _ -> i != index })
		}
	}

	@Test fun repeatedCaptureAndEquivalentExplicitDefaultsKeepEpoch() {
		val config = SkeletonConfigManager(false)
		val identity = capture(config).identity
		assertEquals(identity, capture(config).identity)
		for (key in relevant) config.setOffset(key, key.defaultValue)
		assertEquals(identity, capture(config).identity)
		for (key in relevant) config.setOffset(key, config.getOffset(key))
		assertEquals(identity, capture(config).identity)
		for (key in relevant) config.setOffset(key, null)
		assertEquals(identity, capture(config).identity)
	}

	@Test fun allExcludedOffsetsIncludingTrackerAttachmentsLimbsAndHipWidthKeepEpoch() {
		val config = SkeletonConfigManager(false)
		val identity = capture(config).identity
		for (key in SkeletonConfigOffsets.values().filter { it !in relevant }) {
			config.setOffset(key, config.getOffset(key) + .0625f)
			assertEquals(identity, capture(config).identity, key.name)
		}
	}

	@Test fun allSolverValuesAndTogglesKeepGeometryEpoch() {
		val config = SkeletonConfigManager(false)
		val identity = capture(config).identity
		for (key in SkeletonConfigValues.values()) {
			config.setValue(key, key.defaultValue + .1f)
			assertEquals(identity, capture(config).identity, key.name)
		}
		for (key in SkeletonConfigToggles.values()) {
			config.setToggle(key, !key.defaultValue)
			assertEquals(identity, capture(config).identity, key.name)
		}
	}

	@Test fun snapshotIsImmutableAcrossBulkUpdateResetAndRevert() {
		val config = SkeletonConfigManager(false)
		val a = capture(config)
		val original = fields(a)
		val identity = a.identity
		config.setOffsets(relevant.associateWith { config.getOffset(it) + .02f })
		val b = capture(config)
		assertNotEquals(identity, b.identity)
		assertEquals(original, fields(a))
		assertEquals(identity, a.identity)
		config.resetOffsets()
		assertEquals(identity, capture(config).identity)
		assertEquals(original, fields(a))
		config.setOffsets(relevant.associateWith { it.defaultValue })
		assertEquals(identity, capture(config).identity)
	}

	@Test fun offlineConfigLoadCopiesEffectiveGeometryWithoutChangingOlderSnapshot(@TempDir directory: Path) {
		val config = SkeletonConfigManager(false)
		val before = capture(config)
		val original = fields(before)
		val stored = ConfigManager(directory.resolve("absent-test-config.yml").toString())
		stored.loadConfig()
		for (key in relevant) stored.vrConfig.skeleton.offsets[key.configKey] = key.defaultValue + .025f
		config.loadFromConfig(stored)
		val after = capture(config)
		assertEquals(relevant.map { config.getOffset(it) }, fields(after))
		assertEquals(original, fields(before))
		assertNotEquals(before.identity, after.identity)
		assertEquals(after.identity, capture(config).identity)
	}

	@Test fun nonfiniteEffectiveGeometryFailsClosedForEveryFieldWithoutClamping() {
		for (key in relevant) for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			val config = SkeletonConfigManager(false)
			config.setOffset(key, invalid, false)
			assertIs<HipBodyModelSnapshotResult.Unavailable>(SlimeHipBodyModelSnapshotSource.captureOffline(config), key.name)
		}
		assertIs<HipBodyModelSnapshotResult.Unavailable>(HipBodyModelSnapshot.create(0f, 0f, Float.NaN, 0f, 0f, 0f))
	}

	@Test fun signedZeroPolicyAndRawBitPrecisionAreExplicit() {
		fun identity(head: Float) = assertIs<HipBodyModelSnapshotResult.Available>(
			HipBodyModelSnapshot.create(head, .1f, .16f, .16f, .2f, .04f)).snapshot.identity
		assertNotEquals(identity(+0.0f), identity(-0.0f))
		assertNotEquals(identity(.1f), identity(Float.fromBits(.1f.toRawBits() + 1)))
		assertEquals(identity(.1f), identity(.1f))
	}

	@Test fun attachedConfigurationCannotClaimAtomicCapture() {
		val hpm = HumanPoseManager(emptyList())
		val attached = SkeletonConfigManager(false, hpm)
		val result = assertIs<HipBodyModelSnapshotResult.Unavailable>(
			SlimeHipBodyModelSnapshotSource.captureOffline(attached))
		assertEquals("offline_capture_requires_detached_config", result.reason)
	}

	@Test fun captureRejectsOtherThreadRatherThanClaimingConcurrentConsistency() {
		val config = SkeletonConfigManager(false)
		val result = AtomicReference<HipBodyModelSnapshotResult>()
		val reader = Thread { result.set(SlimeHipBodyModelSnapshotSource.captureOffline(config)) }
		reader.start(); reader.join()
		assertEquals("offline_capture_wrong_thread",
			assertIs<HipBodyModelSnapshotResult.Unavailable>(result.get()).reason)
		assertIs<HipBodyModelSnapshotResult.Available>(SlimeHipBodyModelSnapshotSource.captureOffline(config))
	}

	@Test fun mainConstraintsSolverStateAndChangingComputedPoseCannotChangeOfflineModel() {
		val config = SkeletonConfigManager(false)
		val snapshot = capture(config)
		val trackers = TestTrackerSet()
		trackers.head.position = Vector3(.2f, 1.7f, -.1f)
		val hpm = HumanPoseManager(trackers.allL, relevant.associateWith { config.getOffset(it) })
		hpm.setLegTweaksEnabled(false)
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.slime(trackers.hip.name))
		}
		ConstraintIkWriteback(hpm.skeleton).use { writeback ->
			val computed = hpm.skeleton.computedHipTracker!!
			var previous: Vector3? = null
			var changed = false
			for (enabled in listOf(false, true)) {
				hpm.skeleton.ikSolver.enabled = enabled
				for (step in 0..2) {
					trackers.head.position = Vector3(.2f + step, 1.7f, -.1f)
					trackers.hip.setRotation(Quaternion.IDENTITY)
					val main = EffectiveConstraint(TrackerPosition.HIP,
						position = ResolvedComponent(Vector3(.3f + step, 1f, .4f), "mtp:main",
							ObservationQuality.TRACKED, step.toLong()),
						rotation = ResolvedComponent(Quaternion.IDENTITY, "mtp:main", ObservationQuality.TRACKED, step.toLong()))
					writeback.apply(if (step == 0) emptyMap() else mapOf(TrackerPosition.HIP to main), assignments.snapshot())
					hpm.update()
					if (previous != null && previous != computed.position) changed = true
					previous = computed.position
					assertSame(computed, hpm.skeleton.computedHipTracker)
					assertEquals(snapshot.identity, capture(config).identity)
					assertEquals(fields(snapshot), fields(capture(config)))
				}
			}
			assertTrue(changed, "The test must actually change computed HIP, not only its input.")
		}
	}

	@Test fun snapshotHasOnlyImmutableValuesAndExistingBodyIdentityInterop() {
		val snapshot = capture(SkeletonConfigManager(false))
		val allowed = setOf(Float::class.javaPrimitiveType, BodyModelIdentity::class.java)
		assertTrue(HipBodyModelSnapshot::class.java.declaredFields
			.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
			.all { it.type in allowed && java.lang.reflect.Modifier.isFinal(it.modifiers) })
		assertTrue(snapshot.identity.modelId.isNotBlank())
		assertTrue(snapshot.identity.epoch.startsWith(HipBodyModelSnapshot.EPOCH_SCHEMA + ";"))
	}
}
