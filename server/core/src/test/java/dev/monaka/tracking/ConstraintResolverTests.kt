package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class ConstraintResolverTests {
 private val target = TrackerPosition.HIP
 private fun sample(id: String, modality: TrackingModality) = PoseObservation(id, target, 100,
  position = if (modality == TrackingModality.FULL) Vector3(1f, 2f, 3f) else null,
  rotation = if (modality != TrackingModality.NONE) Quaternion.IDENTITY else null, modality = modality)
 private fun resolver(fallback: Boolean = true) = ConstraintResolver {
  mapOf(target to MainTrackerAssignment(TrackerReference("main"), if (fallback) TrackerReference("fallback") else null))
 }
 @Test fun fullMainOwnsBothRegardlessOfFallbackPriorityAndRecency() {
  val main = sample("main", TrackingModality.FULL).copy(positionQuality = ObservationQuality.DEGRADED)
  val extra = sample("unassigned", TrackingModality.FULL).copy(priority = 999, observedAtNanos = 999)
  val result = resolver().resolve(target, listOf(extra, sample("fallback", TrackingModality.FULL), main))
  assertEquals("main", result.position?.sourceId); assertEquals("main", result.rotation?.sourceId)
 }
 @Test fun modalityAndExplicitFallbackMatrix() {
  for (modality in TrackingModality.entries) for (fallbackAssigned in listOf(false, true)) for (fresh in listOf(false, true)) {
   val fallback = sample("fallback", TrackingModality.ROTATION_ONLY).copy(rotationQuality = if (fresh) ObservationQuality.TRACKED else ObservationQuality.STALE)
   val result = resolver(fallbackAssigned).resolve(target, listOf(sample("main", modality), fallback))
   assertEquals(if (modality == TrackingModality.FULL) "main" else null, result.position?.sourceId)
   val expected = when { modality == TrackingModality.FULL -> "main"; fallbackAssigned && fresh -> "fallback"; modality == TrackingModality.ROTATION_ONLY -> "main"; else -> null }
   assertEquals(expected, result.rotation?.sourceId, "$modality / $fallbackAssigned / $fresh")
  }
 }
 @Test fun incompleteFullCannotSelfDemoteOrServeAsFallback() {
  val malformed = sample("main", TrackingModality.FULL).copy(positionQuality = ObservationQuality.STALE)
  assertNull(resolver(false).resolve(target, listOf(malformed)).rotation)
  val result = resolver().resolve(target, listOf(sample("main", TrackingModality.NONE), malformed.copy(sourceId = "fallback")))
  assertNull(result.position); assertNull(result.rotation)
 }
 @Test fun lossRecoveryDoesNotRememberStaleComponents() {
  val r = resolver()
  for (modality in listOf(TrackingModality.FULL, TrackingModality.ROTATION_ONLY, TrackingModality.NONE, TrackingModality.FULL)) {
   val result = r.resolve(target, listOf(sample("main", modality)))
   assertEquals(modality == TrackingModality.FULL, result.position != null)
   assertEquals(modality != TrackingModality.NONE, result.rotation != null)
  }
 }
 @Test fun ambiguousLegacyCandidatesNeverInventMainOrFallback() {
  val r = ConstraintResolver()
  val result = r.resolve(target, listOf(sample("a", TrackingModality.FULL), sample("b", TrackingModality.FULL)))
  assertNull(result.position); assertNull(result.rotation)
 }
 @Test fun staleLostAndOtherTargetsDoNotProduceConstraints() {
  val result = resolver().resolve(target, listOf(
   sample("main", TrackingModality.FULL).copy(positionQuality = ObservationQuality.STALE, rotationQuality = ObservationQuality.LOST),
   sample("fallback", TrackingModality.ROTATION_ONLY).copy(target = TrackerPosition.CHEST)))
  assertNull(result.position); assertNull(result.rotation)
 }
}
