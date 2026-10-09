package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace

/** Admission metadata; poll time never creates a new physical sample. */
data class ObservationSampleProvenance(
	val sequence: Long,
	val sampleAtNanos: Long,
	val sourceEpoch: String,
	val calibrationEpoch: String,
	val mappingRevision: Long? = null,
	val space: CoordinateSpace? = null,
	/** Null is legacy/unavailable. Never infer a live world from numeric space revision. */
	val commonWorldEpoch: String? = null,
) {
	init {
		require(sequence >= 0 && sampleAtNanos >= 0 && sourceEpoch.isNotBlank() && calibrationEpoch.isNotBlank())
		require(commonWorldEpoch == null || commonWorldEpoch.isNotBlank())
	}
}
