package dev.monaka.tracking

import dev.monaka.tracking.mtp.MtpSourceContextSnapshot
import java.util.Collections

/** Pinned raw sources, never effective constraints. Maps and assignment are defensive copies. */
internal class MonakaSourceTickSnapshot(
	val tickSequence: Long,
	val nowNanos: Long,
	assignment: TrackerBodyAssignments.Snapshot,
	observationsBySource: Map<String, PoseObservation>,
	sourceOwners: Map<String, String>,
	mtpContexts: Map<LogicalTracker, MtpSourceContextSnapshot>,
	mtpSamples: Map<LogicalTracker, dev.monaka.tracking.mtp.MtpObservationBackend.Sample> = emptyMap(),
) {
	val assignment = assignment.copy(targets = immutableCopy(assignment.targets))
	val observationsBySource = immutableCopy(observationsBySource)
	val sourceOwners = immutableCopy(sourceOwners)
	val mtpContexts = immutableCopy(mtpContexts)
	val mtpSamples = immutableCopy(mtpSamples)
}

private fun <K, V> immutableCopy(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
