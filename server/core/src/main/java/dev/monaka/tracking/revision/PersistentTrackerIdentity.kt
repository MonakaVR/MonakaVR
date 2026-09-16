package dev.monaka.tracking.revision

/**
 * Contract-shape candidate for the stable tracker namespace consumed by MonakaVR.
 *
 * C2 defines runtime source lifetime as (publisher_id, source_id) and logical
 * tracker identity as (publisher_id, source_id, tracker_id). Dynamic state such
 * as session, modality, body assignment, mapping revision, quality and endpoint
 * never participates in this identity.
 *
 * This type is intentionally codec-free and runtime-unwired. It only freezes the
 * identity boundary so candidate Main/Fallback logic cannot collapse trackers
 * that reuse the same trackerId under different publishers or sources.
 */
data class PersistentTrackerIdentity(
	val publisherId: String,
	val sourceId: String,
	val trackerId: String,
) {
	init {
		require(publisherId.isNotBlank()) { "publisherId must not be blank" }
		require(sourceId.isNotBlank()) { "sourceId must not be blank" }
		require(trackerId.isNotBlank()) { "trackerId must not be blank" }
	}

	fun sourceLifetime(): SourceLifetimeIdentity =
		SourceLifetimeIdentity(publisherId = publisherId, sourceId = sourceId)
}

/** Runtime lifetime namespace for one backend source as published by one Bridge. */
data class SourceLifetimeIdentity(
	val publisherId: String,
	val sourceId: String,
) {
	init {
		require(publisherId.isNotBlank()) { "publisherId must not be blank" }
		require(sourceId.isNotBlank()) { "sourceId must not be blank" }
	}
}
