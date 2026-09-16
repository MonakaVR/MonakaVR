package dev.monaka.tracking

import dev.monaka.protocol.v2.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class ArchitectureRevisionTests {
 private fun pose() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
 private fun key(p: MtpPose) = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
 private fun send(r: MonakaRuntime, p: Envelope, peer: String = "test") {
  val wire = assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(p)).value
  assertTrue(r.inbox.receive(wire, r.clock(), peer))
 }
 @Test fun oneTrackerSpaceFaultCannotRemoveSiblingOrSameNameUnderOtherPublisher() {
  val p = pose()
  val sibling = p.copy(tracker_id = "other")
  val remote = p.copy(publisher_id = "other-publisher")
  val assignments = TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP, key(sibling) to TrackerPosition.LEFT_FOOT, key(remote) to TrackerPosition.RIGHT_FOOT))
  MonakaRuntime({ emptyList() }, p.coordinate_space, assignments, clock = { 1_000_000_000 }).use { r ->
   send(r, p); send(r, sibling); send(r, remote); r.tick()
   send(r, p.copy(sequence = 1, coordinate_space = p.coordinate_space.copy(id = "bad")))
   val result = r.tick()
   assertNull(result[TrackerPosition.HIP]?.position)
   assertNotNull(result[TrackerPosition.LEFT_FOOT]?.position); assertNotNull(result[TrackerPosition.RIGHT_FOOT]?.position)
   send(r, p.copy(sequence = 2)); assertNotNull(r.tick()[TrackerPosition.HIP]?.position)
   send(r, p.copy(sequence = 3, clock_id = "99999999-9999-4999-8999-999999999999"))
   val clockFault = r.tick()
   assertNull(clockFault[TrackerPosition.HIP]?.position)
   assertNotNull(clockFault[TrackerPosition.LEFT_FOOT]?.position)
   assertNotNull(clockFault[TrackerPosition.RIGHT_FOOT]?.position)
  }
 }
 @Test fun sessionLeaseRejectsCompetitorWithoutPoisoningAndDuplicatesCannotRenew() {
  var now = 1_000_000_000L; val p = pose()
  val next = p.copy(session_id = "33333333-3333-3333-3333-333333333333", clock_id = "33333333-3333-3333-3333-333333333333")
  MonakaRuntime({ emptyList() }, p.coordinate_space, TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP)), clock = { now }).use { r ->
   send(r, p, "a"); r.tick()
   now += 100_000_000; send(r, next, "b"); r.tick()
   assertEquals(p.session_id, r.mtp.samples().getValue(key(p)).pose.session_id)
   now += 300_000_000; send(r, p, "a"); r.tick()
   now += 100_000_000; send(r, next, "b"); r.tick()
   assertEquals(next.session_id, r.mtp.samples().getValue(key(p)).pose.session_id)
   send(r, p.copy(sequence = 2), "a"); r.tick()
   assertEquals(next.session_id, r.mtp.samples().getValue(key(p)).pose.session_id)
  }
 }
 @Test fun calibrationSurvivesFullRotationFallbackRecoveryPauseAndOtherTrackerRebuild() {
  var now = 1_000_000_000L
  val trackers = TestTrackerSet(); trackers.head.position = Vector3(0f, 1.7f, 0f)
  val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip)); hpm.setLegTweaksEnabled(false)
  hpm.skeleton.ikSolver.enabled = false; hpm.update()
  val baseline = hpm.skeleton.computedHipTracker!!.position
  val p = pose().copy(position = listOf(baseline.x + 0.25, baseline.y.toDouble(), baseline.z.toDouble()))
  val assignments = TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP))
  MonakaRuntime({ listOf(trackers.head, trackers.hip) }, p.coordinate_space, assignments, clock = { now }).use { r ->
   ConstraintIkWriteback(hpm.skeleton).use { w ->
    fun step() { w.apply(r.tick(hpm.skeleton.getPauseTracking()), assignments.snapshot(), r.mtp.historyGeneration); hpm.update() }
    send(r, p); step(); hpm.skeleton.ikSolver.resetOffsets()
    hpm.skeleton.ikSolver.enabled = true; step() // Existing solver applies the requested reset on solve.
    val before = hpm.skeleton.ikSolver.calibrationSnapshot()
    val calibrated = before.filterKeys { it.first.startsWith("monaka-private:HIP") }
    assertTrue(calibrated.isNotEmpty())
    assertTrue(calibrated.values.any { it.offset.len() > 0.1f })
    val frames = w.topologyRebuilds
    step(); step(); assertEquals(frames, w.topologyRebuilds)
    send(r, p.copy(sequence = 1, modality = "rotation_only", validity = Validity(false, true), confidence = Confidence(0.0, 1.0), tracking_state = "degraded"))
    step(); assertFalse(w.masks().getValue(TrackerPosition.HIP).position)
    assignments.configure(TrackerPosition.HIP, TrackerReference.mtp(key(p)), TrackerReference.slime(trackers.hip.name))
    send(r, p.copy(sequence = 2, modality = "none", validity = Validity(false, false), confidence = Confidence(0.0, 0.0), tracking_state = "lost"))
    step(); assertEquals("slime:" + trackers.hip.name, r.pipeline.resolve(TrackerPosition.HIP).rotation?.sourceId)
    hpm.skeleton.setPauseTracking(true, "calibration regression"); step()
    hpm.skeleton.setPauseTracking(false, "calibration regression"); step()
    now += 1_000; send(r, p.copy(sequence = 3)); step()
    assertEquals(calibrated, hpm.skeleton.ikSolver.calibrationSnapshot().filterKeys { it.first.startsWith("monaka-private:HIP") })
    // Another target changes topology; existing calibrated Main must survive.
    val sibling = p.copy(tracker_id = "foot", sequence = 0)
    assignments.assign(key(sibling), TrackerPosition.LEFT_FOOT); send(r, sibling); step()
    assertEquals(calibrated, hpm.skeleton.ikSolver.calibrationSnapshot().filterKeys { it.first.startsWith("monaka-private:HIP") })
    assignments.unassign(key(sibling)); step()
    assertEquals(calibrated, hpm.skeleton.ikSolver.calibrationSnapshot().filterKeys { it.first.startsWith("monaka-private:HIP") })
   }
  }
 }
 @Test fun explicitRotationOnlySuppressesStaleSlimePositionWithoutChangingTrackerFlags() {
  val tracker = Tracker(null, 99, "stable-device", trackerPosition = TrackerPosition.HIP, hasPosition = true, hasRotation = true)
  tracker.position = Vector3(999f, 999f, 999f); tracker.status = TrackerStatus.OCCLUDED
  tracker.sampleModality = TrackingModality.ROTATION_ONLY
  val p = assertNotNull(SlimeTrackerPoseObservationAdapter().adapt(tracker, 10))
  assertNull(p.position); assertTrue(p.rotationQuality.usable); assertTrue(tracker.hasPosition)
  tracker.sampleModality = TrackingModality.NONE
  val none = assertNotNull(SlimeTrackerPoseObservationAdapter().adapt(tracker, 11))
  assertNull(none.position); assertNull(none.rotation)
 }
}
