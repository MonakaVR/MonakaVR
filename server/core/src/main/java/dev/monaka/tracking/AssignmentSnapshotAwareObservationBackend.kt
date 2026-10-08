package dev.monaka.tracking

/** Optional server-thread poll seam; ordinary ObservationBackend implementations stay compatible. */
internal interface AssignmentSnapshotAwareObservationBackend {
	fun poll(observedAtNanos: Long, assignment: TrackerBodyAssignments.Snapshot): List<PoseObservation>
}
