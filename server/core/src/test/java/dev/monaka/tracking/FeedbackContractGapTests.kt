package dev.monaka.tracking

import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Characterizes a release-blocking contract gap, NOT a proof of conforming admission.
 * Removing the guard without trusted provenance would also remove a safety boundary.
 */
class FeedbackContractGapTests {
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
