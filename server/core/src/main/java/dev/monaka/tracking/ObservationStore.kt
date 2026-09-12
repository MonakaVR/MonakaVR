package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition

/**
 * Holds the latest observation emitted by each logical source.
 *
 * A source owns exactly one current observation. If that source is reassigned to a
 * different body target, the new observation replaces the old target assignment
 * instead of leaving a ghost constraint behind. Older out-of-order observations are
 * ignored.
 */
class ObservationStore {
	private val latestBySource = linkedMapOf<String, PoseObservation>()

	fun put(observation: PoseObservation): Boolean {
		val previous = latestBySource[observation.sourceId]
		if (previous != null && observation.observedAtNanos < previous.observedAtNanos) {
			return false
		}

		latestBySource[observation.sourceId] = observation
		return true
	}

	fun putAll(observations: Iterable<PoseObservation>): Int {
		var accepted = 0
		for (observation in observations) {
			if (put(observation)) accepted++
		}
		return accepted
	}

	fun observationsFor(target: TrackerPosition): List<PoseObservation> =
		latestBySource.values.filter { it.target == target }

	fun targets(): Set<TrackerPosition> = latestBySource.values.mapTo(linkedSetOf()) { it.target }

	fun snapshot(): List<PoseObservation> = latestBySource.values.toList()

	fun removeSource(sourceId: String): PoseObservation? = latestBySource.remove(sourceId)

	fun clear() {
		latestBySource.clear()
	}

	val size: Int
		get() = latestBySource.size
}
