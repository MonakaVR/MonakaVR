package dev.monaka.tracking

import dev.monaka.tracking.revision.MainFallbackPolicy
import dev.monaka.tracking.revision.RotationOwner
import dev.slimevr.tracking.trackers.TrackerPosition

/** Runtime adapter for the reviewed Main/Fallback policy; no competing selection policy. */
class ConstraintResolver(
 private val assignments: () -> Map<TrackerPosition, MainTrackerAssignment> = { emptyMap() },
) {
 fun resolve(target: TrackerPosition, observations: Iterable<PoseObservation>): EffectiveConstraint {
  val matching = observations.filter { it.target == target }.associateBy { it.sourceId }
  val relation = assignments()[target]
  val main = relation?.let { matching[it.mainTracker.observationId] }
   ?: if (relation == null && matching.size == 1) matching.values.single() else null
  val fallback = relation?.rotationFallbackTracker?.let { matching[it.observationId] }
  fun positionUsable(p: PoseObservation?) = p?.position != null && p.positionQuality.usable
  fun rotationUsable(p: PoseObservation?) = p?.rotation != null && p.rotationQuality.usable
  val fallbackUsable = when (fallback?.modality) {
   TrackingModality.FULL -> positionUsable(fallback) && rotationUsable(fallback)
   TrackingModality.ROTATION_ONLY -> rotationUsable(fallback)
   else -> false
  }
  val decision = MainFallbackPolicy.decide(
   main?.modality ?: TrackingModality.NONE, positionUsable(main), rotationUsable(main), fallbackUsable,
  )
  val rotation = when (decision.rotationOwner) { RotationOwner.MAIN -> main; RotationOwner.FALLBACK -> fallback; else -> null }
  return EffectiveConstraint(target,
   if (decision.positionFromMain) ResolvedComponent(requireNotNull(main?.position), main.sourceId, main.positionQuality, main.observedAtNanos) else null,
   rotation?.let { ResolvedComponent(requireNotNull(it.rotation), it.sourceId, it.rotationQuality, it.observedAtNanos) },
  )
 }
}
