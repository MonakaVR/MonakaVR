package dev.monaka.tracking

import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import dev.slimevr.tracking.trackers.TrackerPosition

/** Characterizes a release-blocking contract gap, NOT a proof of conforming admission.
 * Removing the guard without trusted provenance would also remove a safety boundary.
 */
class FeedbackContractGapTests {
	/** The graph labels belong to this independent oracle; they are absent from the wire. */
	@Test fun externalAndRelayedOutputAreIndistinguishableUnderTheCurrentWire() {
		val fixture = File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()
		val p = (MonakaCodec.decodeEnvelope(fixture) as DecodeResult.Success).value as MtpPose
		val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), outputMode = OutputMode.DIRECT)
		}
		MonakaRuntime({ emptyList() }, p.coordinate_space, assignments, clock = { 1_000_000_000L }).use { runtime ->
			assertTrue(runtime.inbox.receive(fixture, 1_000_000_000L))
			DirectConstraintOutput(assignments.snapshot(), p.coordinate_space) { 10 }.use { output ->
				output.apply(runtime.tick())
				val published = assertNotNull(output.trackers.getValue(TrackerPosition.HIP).monakaOutputPose)
				val position = assertNotNull(published.position).value
				val rotation = assertNotNull(published.rotation).value
				val relay = p.copy(
					publisher_id = "unreserved-alias", tracker_id = "arbitrary-output-name",
					position = listOf(position.x.toDouble(), position.y.toDouble(), position.z.toDouble()),
					orientation = listOf(rotation.x.toDouble(), rotation.y.toDouble(), rotation.z.toDouble(), rotation.w.toDouble()),
				)
				val external = relay.copy()
				val relayBytes = (MonakaCodec.encodeEnvelope(relay) as EncodeResult.Success).value
				val externalBytes = (MonakaCodec.encodeEnvelope(external) as EncodeResult.Success).value
				assertContentEquals(externalBytes, relayBytes)
				val relayKey = LogicalTracker(relay.source_id, relay.tracker_id, relay.publisher_id)
				val relayAssignments = TrackerBodyAssignments(mapOf(relayKey to TrackerPosition.HIP))
				MonakaRuntime({ emptyList() }, p.coordinate_space, relayAssignments, clock = { 1_000_000_000L }).use { receiver ->
					assertTrue(receiver.inbox.receive(relayBytes, 1_000_000_000L))
					assertNotNull(receiver.tick().getValue(TrackerPosition.HIP).position)
				}
			}
		}
	}

	@Test fun validOpaquePublisherCanStillBeRejectedByPreexistingPrefixGuard() {
		val fixture = File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()
		val original = (MonakaCodec.decodeEnvelope(fixture) as DecodeResult.Success).value as MtpPose
		val p = original.copy(publisher_id = "monaka-direct:unreserved-opaque-publisher")
		val encoded = MonakaCodec.encodeEnvelope(p)
		assertTrue(encoded is EncodeResult.Success)
		assertTrue(MonakaCodec.decodeEnvelope(encoded.value) is DecodeResult.Success)
		kotlin.test.assertFailsWith<IllegalArgumentException> {
			TrackerReference.mtp(LogicalTracker(p.source_id, p.tracker_id, p.publisher_id))
		}
		val assignments = TrackerBodyAssignments()
		MonakaRuntime({ emptyList() }, p.coordinate_space, assignments, clock = { 1_000_000_000L }).use { runtime ->
			assertTrue(runtime.inbox.receive(encoded.value, 1_000_000_000L))
			runtime.tickSnapshot()
			assertTrue(runtime.mtp.samples().isEmpty())
			assertEquals(1, runtime.inbox.diagnostics().getValue("FeedbackExcluded"))
		}
	}
}
