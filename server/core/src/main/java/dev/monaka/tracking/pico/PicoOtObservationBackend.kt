package dev.monaka.tracking.pico

import dev.monaka.tracking.ObservationBackend
import dev.monaka.tracking.ObservationQuality
import dev.monaka.tracking.ObservationSourceSetMode
import dev.monaka.tracking.PoseObservation
import dev.slimevr.tracking.trackers.TrackerPosition

/**
 * Converts PICO OT transport snapshots into Monaka pose observations.
 *
 * Body assignment remains outside the transport and is resolved here from the
 * stable physical [PicoOtTrackerSample.trackerId]. The backend publishes an
 * authoritative source snapshot so trackers omitted by the transport are removed
 * from the constraint pipeline immediately by [dev.monaka.tracking.ObservationBackendRunner].
 */
class PicoOtObservationBackend(
	override val backendId: String,
	override val profileId: String,
	private val dataSource: PicoOtDataSource,
	private val targetResolver: (trackerId: String) -> TrackerPosition?,
	private val sourcePrefix: String = "pico-ot",
) : ObservationBackend {
	override val sourceSetMode: ObservationSourceSetMode = ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT

	var lastSnapshot: PicoOtSnapshot? = null
		private set

	init {
		require(backendId.isNotBlank()) { "backendId must not be blank" }
		require(profileId.isNotBlank()) { "profileId must not be blank" }
		require(sourcePrefix.isNotBlank()) { "sourcePrefix must not be blank" }
	}

	override fun poll(observedAtNanos: Long): List<PoseObservation> {
		require(observedAtNanos >= 0L) { "observedAtNanos must be non-negative" }

		val snapshot = dataSource.snapshot(observedAtNanos)
		val snapshotObservedAtNanos = dataSource.observationTimestampNanos(observedAtNanos)
		require(snapshotObservedAtNanos in 0L..observedAtNanos) {
			"PICO OT data source observation timestamp must be non-negative and not in the future"
		}
		lastSnapshot = snapshot

		return snapshot.trackers.mapNotNull { tracker ->
			val target = targetResolver(tracker.trackerId) ?: return@mapNotNull null
			PoseObservation(
				sourceId = "$sourcePrefix:${tracker.trackerId}",
				target = target,
				observedAtNanos = snapshotObservedAtNanos,
				position = tracker.position,
				rotation = tracker.rotation,
				positionQuality = tracker.positionState.toObservationQuality(),
				rotationQuality = tracker.rotationState.toObservationQuality(),
			)
		}
	}

	private fun PicoOtComponentTrackingState.toObservationQuality(): ObservationQuality = when (this) {
		PicoOtComponentTrackingState.TRACKED -> ObservationQuality.TRACKED
		PicoOtComponentTrackingState.DEGRADED -> ObservationQuality.DEGRADED
		PicoOtComponentTrackingState.LOST -> ObservationQuality.LOST
		PicoOtComponentTrackingState.UNAVAILABLE -> ObservationQuality.UNAVAILABLE
	}
}
