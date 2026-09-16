package dev.monaka.tracking.revision

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PersistentTrackerIdentityTests {
	@Test
	fun sameTrackerNameInDifferentSourceOrPublisherDoesNotCollide() {
		val a = PersistentTrackerIdentity("bridge-a", "source-a", "tracker-1")
		val otherSource = PersistentTrackerIdentity("bridge-a", "source-b", "tracker-1")
		val otherPublisher = PersistentTrackerIdentity("bridge-b", "source-a", "tracker-1")

		assertNotEquals(a, otherSource)
		assertNotEquals(a, otherPublisher)
	}

	@Test
	fun exactPersistentTripleIsStableAndEqual() {
		val first = PersistentTrackerIdentity("bridge-a", "source-a", "tracker-1")
		val second = PersistentTrackerIdentity("bridge-a", "source-a", "tracker-1")

		assertEquals(first, second)
		assertEquals(first.hashCode(), second.hashCode())
	}

	@Test
	fun sourceLifetimeIgnoresTrackerNameButPreservesPublisherAndSource() {
		val trackerA = PersistentTrackerIdentity("bridge-a", "source-a", "tracker-1")
		val trackerB = PersistentTrackerIdentity("bridge-a", "source-a", "tracker-2")
		val otherSource = PersistentTrackerIdentity("bridge-a", "source-b", "tracker-1")

		assertEquals(trackerA.sourceLifetime(), trackerB.sourceLifetime())
		assertNotEquals(trackerA.sourceLifetime(), otherSource.sourceLifetime())
	}

	@Test
	fun blankIdentityComponentsFailClosed() {
		assertFailsWith<IllegalArgumentException> {
			PersistentTrackerIdentity("", "source-a", "tracker-1")
		}
		assertFailsWith<IllegalArgumentException> {
			PersistentTrackerIdentity("bridge-a", " ", "tracker-1")
		}
		assertFailsWith<IllegalArgumentException> {
			PersistentTrackerIdentity("bridge-a", "source-a", "")
		}
	}
}
