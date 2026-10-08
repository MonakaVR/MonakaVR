package dev.monaka.tracking

import dev.monaka.protocol.v2.*
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class MonakaSourceTickSnapshotTests {
	private val hip = TrackerPosition.HIP
	private fun pose() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun submit(runtime: MonakaRuntime, pose: MtpPose) = assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, 1_000_000_000))

	@Test fun cutoffReadOnceBeforeRuntimeNowAndNoClockReadsDuringSourceCapture() {
		val order = mutableListOf<String>()
		val assignments = TrackerBodyAssignments()
		val p = pose(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		assignments.assign(key, hip)
		MonakaRuntime({ emptyList() }, p.coordinate_space, assignments,
			clock = { order += "runtime"; 1_000_000_000 }, trackerReceiptClock = { order += "receipt"; -17 }).use { runtime ->
			submit(runtime, p)
			val tick = runtime.tickSnapshot()
			assertEquals(listOf("receipt", "runtime"), order)
			assertEquals(-17, tick.trackerReceiptCutoffSystemNanos)
			val snapshot = runtime.captureSourceSnapshot(tick)
			assertEquals(listOf("receipt", "runtime"), order)
			assertEquals(tick.assignment, snapshot.assignment)
			assertEquals(tick.tickSequence, snapshot.tickSequence); assertEquals(tick.nowNanos, snapshot.nowNanos)
			assertEquals("mtp", snapshot.sourceOwners[key.observationId])
			assertEquals(p.sequence, snapshot.observationsBySource.getValue(key.observationId).provenance!!.sequence)
			val context = snapshot.mtpContexts.getValue(key)
			assertEquals("${p.session_id}:${p.clock_id}", context.sourceEpoch)
			assertEquals(p.input.session_id, context.calibrationEpoch)
			assertEquals(p.mapping_revision, context.mappingRevision); assertEquals(p.coordinate_space, context.coordinateSpace)
		}
	}

	@Test fun latestCompletedRuntimeTickOnlyAndOldSourcesRemainImmutable() {
		val p = pose(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val assignments = TrackerBodyAssignments(mapOf(key to hip))
		MonakaRuntime({ emptyList() }, p.coordinate_space, assignments, clock = { 1_000_000_000 }).use { runtime ->
			submit(runtime, p); val a = runtime.tickSnapshot(); val sourceA = runtime.captureSourceSnapshot(a)
			val fake = MonakaResolvedTickSnapshot(a.tickSequence, a.nowNanos, a.nowNanos, a.paused, a.assignment, a.constraints, a.trackerReceiptCutoffSystemNanos)
			assertFailsWith<IllegalStateException> { runtime.captureSourceSnapshot(fake) }
			submit(runtime, p.copy(sequence = p.sequence + 1)); val b = runtime.tickSnapshot()
			assertFailsWith<IllegalStateException> { runtime.captureSourceSnapshot(a) }
			val sourceB = runtime.captureSourceSnapshot(b)
			assertEquals(p.sequence, sourceA.observationsBySource.getValue(key.observationId).provenance!!.sequence)
			assertEquals(p.sequence + 1, sourceB.observationsBySource.getValue(key.observationId).provenance!!.sequence)
			runtime.mtp.invalidateSamples(); runtime.runner.invalidate("mtp")
			assertEquals("mtp", sourceA.sourceOwners[key.observationId]); assertEquals(1, sourceA.mtpContexts.size)
			assertFailsWith<UnsupportedOperationException> { (sourceA.observationsBySource as MutableMap).clear() }
			assertFailsWith<UnsupportedOperationException> { (sourceA.sourceOwners as MutableMap).clear() }
			assertFailsWith<UnsupportedOperationException> { (sourceA.mtpContexts as MutableMap).clear() }
			runtime.close(); assertFailsWith<IllegalStateException> { runtime.captureSourceSnapshot(b) }
		}
	}

	@Test fun captureUsesPinnedAssignmentEligibilityAfterLiveRegistryDrift() {
		val p = pose(); val assignments = TrackerBodyAssignments()
		assignments.configure(hip, TrackerReference("main"), TrackerReference("raw"))
		MonakaRuntime({ emptyList() }, p.coordinate_space, assignments, clock = { 100 }, maxImuSampleAgeNanos = 50).use { runtime ->
			runtime.runner.register(object : ObservationBackend {
				override val backendId = "test"; override val profileId = "slime"
				override fun poll(observedAtNanos: Long): List<PoseObservation> = listOf(PoseObservation("raw", hip, 100, rotation = Quaternion.IDENTITY))
			})
			val tick = runtime.tickSnapshot()
			assignments.configure(hip, TrackerReference("raw"))
			val source = runtime.captureSourceSnapshot(tick)
			assertEquals(tick.assignment, source.assignment)
			assertEquals(ObservationQuality.STALE, source.observationsBySource.getValue("raw").rotationQuality)
			assertEquals(ObservationQuality.TRACKED, runtime.pipeline.observations(100).single().rotationQuality)
		}
	}

	@Test fun failedNewTickRevokesOldCaptureAuthority() {
		var fail = false
		MonakaRuntime({ emptyList() }, pose().coordinate_space, clock = { if (fail) error("Clock failure") else 100 }).use { runtime ->
			val tick = runtime.tickSnapshot(); runtime.captureSourceSnapshot(tick)
			fail = true; assertFailsWith<IllegalStateException> { runtime.tickSnapshot() }
			assertFailsWith<IllegalStateException> { runtime.captureSourceSnapshot(tick) }
		}
	}

	@Test fun sourceSnapshotCopiesAllCallerOwnedCollectionsIncludingAssignment() {
		val targets = mutableMapOf(hip to MainTrackerAssignment(TrackerReference("main")))
		val observations = mutableMapOf("main" to PoseObservation("main", hip, 10))
		val owners = mutableMapOf("main" to "test")
		val source = MonakaSourceTickSnapshot(1, 10, TrackerBodyAssignments.Snapshot(1, targets), observations, owners, emptyMap())
		targets.clear(); observations.clear(); owners.clear()
		assertEquals("main", source.assignment.targets.getValue(hip).mainTracker.observationId)
		assertEquals(1, source.observationsBySource.size); assertEquals("test", source.sourceOwners["main"])
		assertFailsWith<UnsupportedOperationException> { (source.assignment.targets as MutableMap).clear() }
	}

	@Test fun pinnedPipelineObservationApiBypassesLegacyEligibilityProvider() {
		val pinned = TrackerBodyAssignments.Snapshot(2, emptyMap()); var reads = 0
		val pipeline = ConstraintPipeline(eligibility = { _, _ -> error("Legacy eligibility used") },
			pinnedEligibility = { observation, now, assignment ->
				assertSame(pinned, assignment); assertEquals(100, now); reads++; observation.copy(rotationQuality = ObservationQuality.STALE)
			})
		pipeline.ingest(PoseObservation("test", hip, 100, rotation = Quaternion.IDENTITY))
		assertEquals(ObservationQuality.STALE, pipeline.observations(100, pinned).single().rotationQuality)
		assertEquals(1, reads)
	}
}
