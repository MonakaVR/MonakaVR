package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import kotlin.test.*

class ObservationBackendRunnerTests {
	private fun pipeline() = ConstraintPipeline(profileRegistry = ObservationSourceProfileRegistry(listOf(
		ObservationSourceProfile.sixDof("test", 0, 100, 100))))
	private fun backend(id: String, source: String) = object : ObservationBackend {
		override val backendId = id
		override val profileId = "test"
		override fun poll(observedAtNanos: Long) = listOf(PoseObservation(source, TrackerPosition.HIP, observedAtNanos))
	}

	@Test fun ownerSnapshotIsCopiedUnmodifiableAndSurvivesRemoval() {
		val runner = ObservationBackendRunner(pipeline(), listOf(backend("mtp", "raw")))
		runner.poll("mtp", 100); val owners = runner.sourceOwnersSnapshot()
		assertEquals(mapOf("raw" to "mtp"), owners)
		assertFailsWith<UnsupportedOperationException> { (owners as MutableMap).clear() }
		runner.remove("mtp"); assertTrue(runner.sourceOwnersSnapshot().isEmpty())
		assertEquals("mtp", owners["raw"])
	}

	@Test fun mtpPrefixDoesNotConferBackendOwnershipAndCollisionPreservesOwner() {
		val source = "mtp:spoof"
		val runner = ObservationBackendRunner(pipeline(), listOf(backend("other", source), backend("mtp", source)))
		runner.poll("other", 100)
		assertEquals("other", runner.sourceOwnersSnapshot()[source])
		assertFailsWith<IllegalArgumentException> { runner.poll("mtp", 100) }
		assertEquals("other", runner.sourceOwnersSnapshot()[source])
	}

	@Test fun invalidationAndReplacementReleaseCurrentOwnersWithoutChangingOldSnapshot() {
		val runner = ObservationBackendRunner(pipeline(), listOf(backend("mtp", "old")))
		runner.poll("mtp", 100); val owners = runner.sourceOwnersSnapshot()
		runner.invalidate("mtp"); assertTrue(runner.sourceOwnersSnapshot().isEmpty())
		runner.replace(backend("mtp", "new")); runner.poll("mtp", 101)
		assertEquals(mapOf("new" to "mtp"), runner.sourceOwnersSnapshot())
		assertEquals(mapOf("old" to "mtp"), owners)
		runner.clear(); assertTrue(runner.sourceOwnersSnapshot().isEmpty())
	}
}
