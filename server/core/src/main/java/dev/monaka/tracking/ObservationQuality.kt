package dev.monaka.tracking

enum class ObservationQuality(
	val usable: Boolean,
	val rank: Int,
) {
	TRACKED(true, 2),
	DEGRADED(true, 1),
	STALE(false, 0),
	LOST(false, 0),
	UNAVAILABLE(false, 0),
}
