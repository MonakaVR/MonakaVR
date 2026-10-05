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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class LiveHipBodyModelPublicationTests {
	private val chain = listOf(SkeletonConfigOffsets.HEAD, SkeletonConfigOffsets.NECK,
		SkeletonConfigOffsets.UPPER_CHEST, SkeletonConfigOffsets.CHEST,
		SkeletonConfigOffsets.WAIST, SkeletonConfigOffsets.HIP)
	private val defaults = chain.associateWith { it.defaultValue }
	private val changed = chain.withIndex().associate { (i, key) -> key to (.3f + .125f * i) }
	private fun attached() = SkeletonConfigManager(false, HumanPoseManager(emptyList()))
	private fun current(config: SkeletonConfigManager) =
		assertIs<HipBodyModelSnapshotResult.Available>(config.currentHipBodyModelSnapshot()).snapshot
	private fun fields(snapshot: HipBodyModelSnapshot) = listOf(snapshot.headShift, snapshot.neckLength,
		snapshot.upperChestLength, snapshot.chestLength, snapshot.waistLength, snapshot.hipLength)
	private fun identity(values: Map<SkeletonConfigOffsets, Float>): BodyModelIdentity {
		val offline = SkeletonConfigManager(false)
		offline.setOffsets(values)
		return assertIs<HipBodyModelSnapshotResult.Available>(
			SlimeHipBodyModelSnapshotSource.captureOffline(offline)).snapshot.identity
	}

	// Observe inside the real public batch iteration, without a production callback/test hook.
	private fun observedMap(values: Map<SkeletonConfigOffsets, Float>, onRead: (Int) -> Unit): Map<SkeletonConfigOffsets, Float> =
		object : Map<SkeletonConfigOffsets, Float> by values {
			override val entries: Set<Map.Entry<SkeletonConfigOffsets, Float>> =
				values.entries.mapIndexed { index, entry ->
					object : Map.Entry<SkeletonConfigOffsets, Float> {
						override val key = entry.key
						override val value: Float get() { onRead(index); return entry.value }
					}
				}.toSet()
		}

	@Test fun attachedConstructionPublishesEffectiveDefaultsWithOfflineIdentity() {
		val config = attached()
		assertEquals(chain.map { config.getOffset(it) }, fields(current(config)))
		assertEquals(identity(defaults), current(config).identity)
		assertEquals(0L, config.hipBodyModelPublicationSequence)
		assertIs<HipBodyModelSnapshotResult.Unavailable>(SlimeHipBodyModelSnapshotSource.captureOffline(config))
	}

	@Test fun everySingleRelevantUpdatePublishesOnceAndOnlyChangesThatField() {
		for ((index, key) in chain.withIndex()) {
			val config = attached()
			val before = current(config)
			config.setOffset(key, changed.getValue(key))
			val after = current(config)
			assertEquals(1L, config.hipBodyModelPublicationSequence, key.name)
			assertEquals(chain.map { if (it == key) changed.getValue(it) else it.defaultValue }, fields(after))
			assertNotEquals(before.identity, after.identity)
			assertEquals(chain.map { it.defaultValue }, fields(before), "Previously returned snapshot must not mutate")
			config.setOffset(key, changed.getValue(key), false)
			assertEquals(after.identity, current(config).identity)
			assertEquals(2L, config.hipBodyModelPublicationSequence)
			config.resetOffset(key)
			assertEquals(before.identity, current(config).identity)
			assertEquals(3L, config.hipBodyModelPublicationSequence)
			assertEquals(after.hipLength, fields(after)[5])
			assertEquals(changed.getValue(key), fields(after)[index])
		}
	}

	@Test fun bulkDoesNotPublishBetweenNestedSettersAndCommitsOnlyOneFinalTuple() {
		val config = attached()
		val before = config.currentHipBodyModelSnapshot()
		var observed = 0
		config.setOffsets(observedMap(changed) {
			observed++
			assertSame(before, config.currentHipBodyModelSnapshot())
			assertEquals(0L, config.hipBodyModelPublicationSequence)
		}, true)
		assertEquals(6, observed)
		assertEquals(1L, config.hipBodyModelPublicationSequence)
		assertEquals(chain.map { changed.getValue(it) }, fields(current(config)))
		assertEquals(identity(changed), current(config).identity)
	}

	@Test fun copyingManagerKeepsLegacyMergeSemanticsAndSinglePublication() {
		val source = SkeletonConfigManager(true)
		source.setOffset(SkeletonConfigOffsets.HEAD, .55f)
		val config = attached()
		config.setOffset(SkeletonConfigOffsets.NECK, .35f)
		val sequence = config.hipBodyModelPublicationSequence
		config.setOffsets(source)
		assertEquals(sequence + 1, config.hipBodyModelPublicationSequence)
		assertEquals(.55f, current(config).headShift)
		assertEquals(.35f, current(config).neckLength, "Source unset keys must not overwrite destination")
		assertEquals(chain.map { config.getOffset(it) }, fields(current(config)))
	}

	@Test fun attachedAndDetachedResetAndResetAllPublishOnceAndRestoreDefaultEpoch() {
		for (config in listOf(attached(), SkeletonConfigManager(true))) {
			val original = current(config).identity
			for (resetAll in listOf(false, true)) {
				config.setOffsets(changed)
				val sequence = config.hipBodyModelPublicationSequence
				if (resetAll) config.resetAllConfigs() else config.resetOffsets()
				assertEquals(sequence + 1, config.hipBodyModelPublicationSequence)
				assertEquals(original, current(config).identity)
				assertEquals(chain.map { it.defaultValue }, fields(current(config)))
			}
		}
	}

	@Test fun loadPublishesAfterAllOffsetsAndPreservesHeightAndNodeSideEffects(@TempDir directory: Path) {
		val hpm = HumanPoseManager(TestTrackerSet().allL)
		val config = SkeletonConfigManager(true, hpm)
		val before = config.currentHipBodyModelSnapshot()
		val stored = ConfigManager(directory.resolve("absent.yml").toString())
		stored.loadConfig()
		var reads = 0
		stored.vrConfig.skeleton.offsets = object : HashMap<String, Float>() {
			override fun get(key: String): Float? {
				reads++
				assertSame(before, config.currentHipBodyModelSnapshot())
				assertEquals(0L, config.hipBodyModelPublicationSequence)
				return super.get(key)
			}
		}.also { values -> changed.forEach { (key, value) -> values[key.configKey] = value } }
		config.loadFromConfig(stored)
		assertEquals(SkeletonConfigOffsets.values().size, reads)
		assertEquals(1L, config.hipBodyModelPublicationSequence)
		assertEquals(identity(changed), current(config).identity)
		assertEquals(chain.map { config.getOffset(it) }, fields(current(config)))
		assertEquals(SkeletonConfigManager.HEIGHT_OFFSETS.sumOf { config.getOffset(it).toDouble() }.toFloat(),
			config.userHeightFromOffsets, .00001f)
		assertEquals(config.userHeightFromOffsets - current(config).neckLength, config.userNeckHeightFromOffsets)
		assertEquals(current(config).hipLength, hpm.skeleton.hipBone.length, .00001f)
	}

	@Test fun unrelatedOffsetsValuesTogglesAndNullBatchDoNotRepublishOrChangeEpoch() {
		val config = attached()
		val initial = config.currentHipBodyModelSnapshot()
		for (key in SkeletonConfigOffsets.values().filter { it !in chain }) config.setOffset(key, key.defaultValue + .1f)
		for (key in SkeletonConfigValues.values()) config.setValue(key, key.defaultValue + .1f)
		for (key in SkeletonConfigToggles.values()) config.setToggle(key, !key.defaultValue)
		config.setOffsets(null as Map<SkeletonConfigOffsets, Float>?)
		assertSame(initial, config.currentHipBodyModelSnapshot())
		assertEquals(0L, config.hipBodyModelPublicationSequence)
	}

	@Test fun everyNonfiniteFieldRevokesCurrentAvailabilityAndFiniteRecoveryRestoresContentEpoch() {
		for (key in chain) for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			val config = attached()
			val original = current(config)
			config.setOffset(key, invalid, false)
			val unavailable = assertIs<HipBodyModelSnapshotResult.Unavailable>(config.currentHipBodyModelSnapshot())
			assertTrue(unavailable.reason.startsWith("nonfinite_"))
			assertEquals(1L, config.hipBodyModelPublicationSequence)
			config.setOffset(key, null, false)
			assertEquals(original.identity, current(config).identity)
			assertEquals(2L, config.hipBodyModelPublicationSequence)
		}
	}

	@Test fun failedBatchDoesNotPublishPartialAvailableGeometryAndDoesNotSwallowLegacyFailure() {
		val config = attached()
		val before = current(config)
		val failure = IllegalStateException("test input iteration failure")
		assertSame(failure, assertFailsWith<IllegalStateException> {
			config.setOffsets(observedMap(changed) { if (it == 3) throw failure })
		})
		assertEquals("offset_mutation_incomplete",
			assertIs<HipBodyModelSnapshotResult.Unavailable>(config.currentHipBodyModelSnapshot()).reason)
		config.setOffset(SkeletonConfigOffsets.HEAD, .7f)
		assertIs<HipBodyModelSnapshotResult.Unavailable>(config.currentHipBodyModelSnapshot())
		config.resetOffsets()
		assertEquals(before.identity, current(config).identity)
		assertEquals(chain.map { it.defaultValue }, fields(before))
	}

	@Test fun incompleteBatchInOtherThreadCannotSuppressACompletedWriterCommit() {
		val config = attached()
		val started = CountDownLatch(1)
		val release = CountDownLatch(1)
		val failure = AtomicReference<Throwable?>()
		val writer = Thread {
			try {
				config.setOffsets(observedMap(changed) {
					if (it == 3) {
						started.countDown()
						check(release.await(10, TimeUnit.SECONDS))
					}
				})
			} catch (error: Throwable) { failure.set(error) }
		}
		writer.start()
		try {
			assertTrue(started.await(10, TimeUnit.SECONDS))
			assertEquals(identity(defaults), current(config).identity, "Half-written batch must remain unpublished")
			config.setOffset(SkeletonConfigOffsets.HEAD, .75f)
			assertEquals(.75f, current(config).headShift)
			assertEquals(defaults.getValue(SkeletonConfigOffsets.NECK), current(config).neckLength)
			assertEquals(1L, config.hipBodyModelPublicationSequence)
		} finally { release.countDown(); writer.join(10_000) }
		assertFalse(writer.isAlive)
		failure.get()?.let { throw it }
		assertEquals(identity(changed), current(config).identity, "Completed batch linearizes as one whole journal")
		assertEquals(2L, config.hipBodyModelPublicationSequence)
	}

	@Test fun concurrentTwoWriterBatchPublicationNeverExposesMixedGeometry() {
		val config = attached()
		config.setOffsets(defaults)
		val a = identity(defaults); val b = identity(changed)
		val failure = AtomicReference<Throwable?>()
		val barrier = CyclicBarrier(3)
		val iterations = 400
		fun guarded(action: () -> Unit) = Thread {
			try { action() } catch (error: Throwable) { failure.compareAndSet(null, error); barrier.reset() }
		}
		val writers = (0..1).map { writer -> guarded {
			repeat(iterations) { step ->
				barrier.await(10, TimeUnit.SECONDS)
				config.setOffsets(if ((step + writer) % 2 == 0) defaults else changed, false)
				barrier.await(10, TimeUnit.SECONDS)
			}
		} }
		var observations = 0
		val reader = guarded {
			repeat(iterations) { step ->
				barrier.await(10, TimeUnit.SECONDS)
				repeat(32) {
					val seen = current(config)
					assertTrue(seen.identity == a || seen.identity == b, "step=$step mixed tuple=${fields(seen)}")
					observations++
				}
				barrier.await(10, TimeUnit.SECONDS)
			}
		}
		val threads = writers + reader
		threads.forEach { it.start() }
		threads.forEach { it.join(30_000) }
		assertTrue(threads.none { it.isAlive })
		failure.get()?.let { throw it }
		assertEquals(12_800, observations)
		assertEquals(801L, config.hipBodyModelPublicationSequence)
	}

	@Test fun resetAndLoadAreAtomicForConcurrentReader(@TempDir directory: Path) {
		val config = attached()
		val stored = ConfigManager(directory.resolve("absent.yml").toString())
		stored.loadConfig()
		changed.forEach { (key, value) -> stored.vrConfig.skeleton.offsets[key.configKey] = value }
		val a = identity(defaults); val b = identity(changed)
		val barrier = CyclicBarrier(2)
		val failure = AtomicReference<Throwable?>()
		val writer = Thread {
			try { repeat(100) { step ->
				barrier.await(10, TimeUnit.SECONDS)
				if (step % 2 == 0) config.loadFromConfig(stored) else config.resetAllConfigs()
				barrier.await(10, TimeUnit.SECONDS)
			} } catch (error: Throwable) { failure.set(error); barrier.reset() }
		}
		writer.start()
		try { repeat(100) { step ->
			barrier.await(10, TimeUnit.SECONDS)
			repeat(32) { assertTrue(current(config).identity.let { it == a || it == b }, "step=$step") }
			barrier.await(10, TimeUnit.SECONDS)
		} } finally { barrier.reset(); writer.join(10_000) }
		assertFalse(writer.isAlive)
		failure.get()?.let { throw it }
		assertEquals(a, current(config).identity)
		assertEquals(100L, config.hipBodyModelPublicationSequence)
	}

	@Test fun actualHpmConfigurationPublicationIsIndependentOfMainConstraintsAndComputedHip() {
		val trackers = TestTrackerSet()
		trackers.head.position = Vector3(.2f, 1.7f, -.1f)
		val hpm = HumanPoseManager(trackers.allL)
		hpm.setLegTweaksEnabled(false)
		// Read the existing private config only in this test; no mutable production accessor added.
		val config = HumanPoseManager::class.java.getDeclaredField("skeletonConfigManager").let {
			it.isAccessible = true; it.get(hpm) as SkeletonConfigManager
		}
		val published = config.currentHipBodyModelSnapshot()
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.slime(trackers.hip.name))
		}
		ConstraintIkWriteback(hpm.skeleton).use { writeback ->
			val computed = hpm.skeleton.computedHipTracker!!
			var previous: Vector3? = null
			var moved = false
			for (enabled in listOf(false, true)) {
				hpm.skeleton.ikSolver.enabled = enabled
				for (step in 0..2) {
					trackers.head.position = Vector3(.2f + step, 1.7f, -.1f)
					val main = EffectiveConstraint(TrackerPosition.HIP,
						ResolvedComponent(Vector3(.3f + step, 1f, .4f), "mtp:main", ObservationQuality.TRACKED, step.toLong()),
						ResolvedComponent(Quaternion.IDENTITY, "mtp:main", ObservationQuality.TRACKED, step.toLong()))
					writeback.apply(if (step == 0) emptyMap() else mapOf(TrackerPosition.HIP to main), assignments.snapshot())
					hpm.update()
					if (previous != null && previous != computed.position) moved = true
					previous = computed.position
					assertSame(published, config.currentHipBodyModelSnapshot())
					assertEquals(0L, config.hipBodyModelPublicationSequence)
				}
			}
			assertTrue(moved)
		}
	}
}
