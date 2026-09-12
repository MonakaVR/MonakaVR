package dev.monaka.tracking

/**
 * Registry for backend-neutral observation source profiles.
 *
 * Concrete backends register policy profiles here and refer to them by [profileId]
 * when observations are ingested. Profile ids are intentionally independent from
 * backend implementation class names so configuration can select or replace policy
 * without changing the resolver.
 */
class ObservationSourceProfileRegistry(
	profiles: Iterable<ObservationSourceProfile> = emptyList(),
) {
	private val profilesById = linkedMapOf<String, ObservationSourceProfile>()

	init {
		for (profile in profiles) {
			register(profile)
		}
	}

	fun register(profile: ObservationSourceProfile) {
		require(profile.profileId !in profilesById) {
			"Observation source profile '${profile.profileId}' is already registered"
		}
		profilesById[profile.profileId] = profile
	}

	fun replace(profile: ObservationSourceProfile): ObservationSourceProfile? =
		profilesById.put(profile.profileId, profile)

	operator fun get(profileId: String): ObservationSourceProfile? = profilesById[profileId]

	fun require(profileId: String): ObservationSourceProfile =
		profilesById[profileId]
			?: throw IllegalArgumentException("Unknown observation source profile '$profileId'")

	fun remove(profileId: String): ObservationSourceProfile? = profilesById.remove(profileId)

	fun snapshot(): Map<String, ObservationSourceProfile> = profilesById.toMap()

	fun clear() = profilesById.clear()

	val size: Int
		get() = profilesById.size
}
