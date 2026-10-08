package dev.monaka.tracking

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.mtp.*
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.*

class MtpObservationBackendTests {
	private fun pose() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun key(p: MtpPose) = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
	private fun submit(inbox: MtpInbox, envelope: Envelope, at: Long = 1_000_000_000, peer: String = "in-memory") =
		assertTrue(inbox.receive((MonakaCodec.encodeEnvelope(envelope) as EncodeResult.Success).value, at, peer))
	private fun state(p: MtpPose, presence: String = "present", tracking: String = p.tracking_state, sequence: Long = 0) = MtpTrackerState(
		p.version, p.source_id, p.session_id, p.clock_id, sequence, p.timestamp_ns, p.sent_at_ns, p.timestamp_kind,
		p.tracker_id, presence, tracking, p.coordinate_space, p.capabilities, "none", p.publisher_id, null, p.mapping_revision)

	@Test fun contextOwnedByAdmissionAndImmutableAcrossNewPoseAndInvalidate() {
		val p = pose(); val inbox = MtpInbox(); val assignments = TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP))
		val backend = MtpObservationBackend(inbox, assignments, p.coordinate_space)
		submit(inbox, p); val first = backend.poll(1_000_000_000).single(); val old = backend.sourceContexts()
		val context = old.getValue(key(p))
		assertEquals(MtpSourceContextSnapshot(key(p), "${p.session_id}:${p.clock_id}", p.input.session_id, p.mapping_revision, p.coordinate_space), context)
		assertEquals(first.provenance!!.sourceEpoch, context.sourceEpoch)
		submit(inbox, p.copy(sequence = p.sequence + 1, input = p.input.copy(session_id = "33333333-3333-3333-3333-333333333333")))
		backend.poll(1_000_000_000)
		assertNotEquals(context.calibrationEpoch, backend.sourceContexts().getValue(key(p)).calibrationEpoch)
		assertEquals(context, old.getValue(key(p)))
		assertFailsWith<UnsupportedOperationException> { (old as MutableMap).clear() }
		backend.invalidateSamples(); assertTrue(backend.sourceContexts().isEmpty()); assertEquals(1, old.size)
	}

	@ParameterizedTest @ValueSource(strings = ["clock", "space", "mapping", "absent", "lost", "disconnected", "overflow", "beforeEpoch", "invalidate", "close", "pause"])
	fun contextClearedWheneverCurrentSampleIsInvalidated(case: String) {
		val p = pose(); val inbox = MtpInbox(); val backend = MtpObservationBackend(inbox, TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP)), p.coordinate_space)
		submit(inbox, p); backend.poll(1_000_000_000); assertEquals(1, backend.sourceContexts().size)
		when (case) {
			"clock" -> submit(inbox, p.copy(sequence = p.sequence + 1, clock_id = "44444444-4444-4444-4444-444444444444"))
			"space" -> submit(inbox, p.copy(sequence = p.sequence + 1, coordinate_space = p.coordinate_space.copy(revision = p.coordinate_space.revision + 1)))
			"mapping" -> submit(inbox, state(p.copy(mapping_revision = p.mapping_revision + 1)))
			"absent" -> submit(inbox, state(p, presence = "absent"))
			"lost", "disconnected" -> submit(inbox, state(p, tracking = case))
			"overflow" -> submit(inbox, p.copy(sequence = p.sequence + 1, position = listOf(Double.MAX_VALUE, 0.0, 0.0)))
			"beforeEpoch" -> submit(inbox, p.copy(sequence = p.sequence + 1, sent_at_ns = p.timestamp_ns + 2_000_000_000))
			"invalidate" -> backend.invalidateSamples()
			"close" -> backend.close()
			else -> backend.suspend(true)
		}
		backend.poll(1_000_000_000)
		assertTrue(backend.samples().isEmpty(), case); assertTrue(backend.sourceContexts().isEmpty(), case)
		if (case == "overflow") assertEquals(1, inbox.diagnostics()["FloatOverflow"])
	}

	@Test fun sessionStateInvalidatesUntilAcceptedNewPoseAndRetiredCannotRestore() {
		val p = pose(); val inbox = MtpInbox(); val backend = MtpObservationBackend(inbox, TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP)), p.coordinate_space)
		submit(inbox, p); backend.poll(1_000_000_000)
		val replacement = p.copy(session_id = "33333333-3333-3333-3333-333333333333")
		submit(inbox, state(replacement), 1_500_000_000); backend.poll(1_500_000_000)
		assertTrue(backend.sourceContexts().isEmpty())
		submit(inbox, replacement, 1_500_000_000); backend.poll(1_500_000_000)
		val current = backend.sourceContexts().getValue(key(p))
		assertEquals("${replacement.session_id}:${replacement.clock_id}", current.sourceEpoch)
		submit(inbox, p.copy(sequence = p.sequence + 1), 2_000_000_000); backend.poll(2_000_000_000)
		assertEquals(current, backend.sourceContexts().getValue(key(p)))
	}

	@Test fun mappingAdvanceClearsUntilPoseWithNewRevisionAndResumeRequiresNewPose() {
		val p = pose(); val inbox = MtpInbox(); val backend = MtpObservationBackend(inbox, TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP)), p.coordinate_space)
		submit(inbox, p); backend.poll(1_000_000_000)
		submit(inbox, state(p.copy(mapping_revision = p.mapping_revision + 1))); backend.poll(1_000_000_000)
		assertTrue(backend.sourceContexts().isEmpty())
		val next = p.copy(sequence = p.sequence + 1, mapping_revision = p.mapping_revision + 1)
		submit(inbox, next); backend.poll(1_000_000_000)
		assertEquals(next.mapping_revision, backend.sourceContexts().getValue(key(p)).mappingRevision)
		backend.suspend(true); submit(inbox, next.copy(sequence = next.sequence + 1)); backend.poll(1_000_000_000)
		backend.suspend(false); backend.poll(1_000_000_000); assertTrue(backend.sourceContexts().isEmpty())
		submit(inbox, next.copy(sequence = next.sequence + 2)); backend.poll(1_000_000_000)
		assertEquals(1, backend.sourceContexts().size)
	}

	@Test fun duplicatePeerMismatchAndOldMappingCannotRefreshContextAndStaleRetainsContext() {
		val p = pose(); val inbox = MtpInbox(); val assignments = TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP))
		val runtime = MonakaRuntime({ emptyList() }, p.coordinate_space, assignments, inbox, clock = { 2_000_000_000 })
		runtime.use {
			submit(inbox, p); val tick = runtime.tickSnapshot(); val source = runtime.captureSourceSnapshot(tick)
			assertEquals(ObservationQuality.STALE, source.observationsBySource.getValue(key(p).observationId).positionQuality)
			val context = source.mtpContexts.getValue(key(p))
			submit(inbox, p.copy(input = p.input.copy(session_id = "33333333-3333-3333-3333-333333333333")))
			submit(inbox, p.copy(sequence = p.sequence + 1), peer = "other-peer")
			runtime.tickSnapshot()
			assertEquals(context, runtime.mtp.sourceContexts().getValue(key(p)))
		}
	}
}
