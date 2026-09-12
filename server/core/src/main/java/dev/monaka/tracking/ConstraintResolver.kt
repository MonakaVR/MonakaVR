package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

class ConstraintResolver {
	fun resolve(
		target: TrackerPosition,
		observations: Iterable<PoseObservation>,
	): EffectiveConstraint {
		val matching = observations.filter { it.target == target }
		return EffectiveConstraint(
			target = target,
			position = chooseComponent(
				matching,
				value = { it.position },
				quality = { it.positionQuality },
			),
			rotation = chooseComponent(
				matching,
				value = { it.rotation },
				quality = { it.rotationQuality },
			),
		)
	}

	private data class Candidate<T>(
		val component: ResolvedComponent<T>,
		val priority: Int,
	)

	private fun <T> chooseComponent(
		observations: Iterable<PoseObservation>,
		value: (PoseObservation) -> T?,
		quality: (PoseObservation) -> ObservationQuality,
	): ResolvedComponent<T>? {
		return observations
			.mapNotNull { observation ->
				val componentQuality = quality(observation)
				val componentValue = value(observation)
				if (!componentQuality.usable || componentValue == null) {
					return@mapNotNull null
				}
				Candidate(
					component = ResolvedComponent(
						value = componentValue,
						sourceId = observation.sourceId,
						quality = componentQuality,
						observedAtNanos = observation.observedAtNanos,
					),
					priority = observation.priority,
				)
			}
			.maxWithOrNull(
				compareBy<Candidate<T>> { it.component.quality.rank }
					.thenBy { it.priority }
					.thenBy { it.component.observedAtNanos }
					.thenBy { it.component.sourceId },
			)
			?.component
	}
}
