package dev.monaka.tracking.hil

import dev.monaka.tracking.*

/** Availability boundary only. Raw values and physical provenance are retained for capture. */
class HilValidityGate(val hilMode: Boolean = false, private val mainSourceId: String) {
	var available: Boolean = true
		private set
	fun setAvailable(value: Boolean) {
		check(hilMode) { "Validity control requires explicit HIL mode" }
		available = value
	}
	fun apply(raw: PoseObservation): PoseObservation = if (!hilMode || available || raw.sourceId != mainSourceId) raw else
		raw.copy(positionQuality = ObservationQuality.UNAVAILABLE, rotationQuality = ObservationQuality.UNAVAILABLE,
			modality = TrackingModality.NONE)
}
