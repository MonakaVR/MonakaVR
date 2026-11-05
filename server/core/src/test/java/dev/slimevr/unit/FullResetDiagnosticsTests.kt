package dev.slimevr.unit

import dev.slimevr.tracking.processor.FullResetDiagnostics
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FullResetDiagnosticsTests {
	@Test fun actualFullBoundaryBracketsApplicationAndOtherResetsDoNotAdvanceIt() {
		val trackers = TestTrackerSet()
		val manager = HumanPoseManager(trackers.allL)
		trackers.chest.setRotation(TrackerTestUtils.testRotFromIndex(4))
		val before = trackers.chest.getRotation()
		val events = mutableListOf<Pair<FullResetDiagnostics.Snapshot, Any>>()
		manager.fullResetDiagnostics.observe { events += it to trackers.chest.getRotation() }
		assertEquals(0L, manager.fullResetDiagnostics.snapshot.generation)
		manager.resetTrackersFull("offline diagnostic test")
		assertEquals(listOf("STARTED", "COMPLETED"), events.map { it.first.phase })
		assertEquals(listOf(1L, 1L), events.map { it.first.generation })
		assertEquals(before, events[0].second)
		assertEquals(trackers.chest.getRotation(), events[1].second)
		assertNotEquals(events[0].second, events[1].second)
		assertTrue(events[1].first.completedSystemNanos!! >= events[0].first.startedSystemNanos!!)
		manager.resetTrackersYaw("offline diagnostic test")
		manager.resetTrackersMounting("offline diagnostic test")
		assertEquals(1L, manager.fullResetDiagnostics.snapshot.generation)
		assertEquals(2, events.size)
		manager.skeleton.resetTrackersFull("direct actual skeleton entry")
		assertEquals(2L, manager.fullResetDiagnostics.snapshot.generation)
		assertEquals(4, events.size)
	}

	@Test fun disabledAndThrowingObserversProduceIdenticalResetAndSolverValues() {
		fun execute(readInputs: Boolean = false, observer: ((FullResetDiagnostics.Snapshot) -> Unit)? = null): List<Any> {
			val trackers = TestTrackerSet()
			val manager = HumanPoseManager(trackers.allL)
			manager.setLegTweaksEnabled(false)
			trackers.all.forEachIndexed { i, t -> t.setRotation(TrackerTestUtils.testRotFromIndex(i + 2)) }
			manager.fullResetDiagnostics.observe(observer)
			if (readInputs) manager.skeleton.diagnosticInputOrientations(TrackerPosition.CHEST, emptySet())
			manager.resetTrackersFull("offline observer parity")
			if (readInputs) manager.skeleton.diagnosticInputOrientations(TrackerPosition.CHEST, emptySet())
			manager.update()
			assertEquals("COMPLETED", manager.fullResetDiagnostics.snapshot.phase)
			val values = mutableListOf<Any>()
			trackers.all.forEach { values.add(it.getRotation()) }
			manager.computedTrackers.forEach { values.add(it.position); values.add(it.getRotation()) }
			return values
		}
		val off = execute()
		val observed = mutableListOf<String?>()
		assertEquals(off, execute(true) { observed += it.phase })
		assertEquals(listOf<String?>("STARTED", "COMPLETED"), observed)
		assertEquals(off, execute(true) { throw IllegalStateException("diagnostic callback failure") })
	}

	@Test fun legacyInputSnapshotUsesExistingGuiValuesAndIsDetachedAcrossActualReset() {
		val trackers = TestTrackerSet()
		val manager = HumanPoseManager(trackers.allL)
		trackers.chest.setRotation(TrackerTestUtils.testRotFromIndex(4))
		val before = manager.skeleton.diagnosticInputOrientations(TrackerPosition.CHEST, emptySet()).single()
		assertEquals(trackers.chest.getRawRotation(), before.raw)
		assertEquals(trackers.chest.getRotation(), before.referenceAdjusted)
		assertEquals(trackers.chest.getIdentityAdjustedRotation(), before.identityAdjusted)
		manager.resetTrackersFull("offline GUI input snapshot")
		val after = manager.skeleton.diagnosticInputOrientations(TrackerPosition.CHEST, emptySet()).single()
		assertEquals(before.raw, after.raw)
		assertNotEquals(before.referenceAdjusted, after.referenceAdjusted)
		assertEquals(trackers.chest.getRotation(), after.referenceAdjusted)
		assertEquals(trackers.chest.getIdentityAdjustedRotation(), after.identityAdjusted)
	}

	@Test fun failedCompletionIsDistinctWithoutChangingGeneration() {
		var now = 10L
		val timeline = FullResetDiagnostics { now++ }
		val events = mutableListOf<FullResetDiagnostics.Snapshot>()
		timeline.observe { events += it }
		val start = timeline.started()
		timeline.finished(start, false)
		assertEquals(listOf("STARTED", "FAILED"), events.map { it.phase })
		assertEquals(1L, timeline.snapshot.generation)
		assertEquals(10L, timeline.snapshot.startedSystemNanos)
		assertEquals(11L, timeline.snapshot.completedSystemNanos)
	}
}
