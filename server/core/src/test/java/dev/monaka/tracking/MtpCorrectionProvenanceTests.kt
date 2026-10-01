package dev.monaka.tracking

import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.tracking.mtp.MtpPoseAdapter
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class MtpCorrectionProvenanceTests {
	private fun fixture() = (MonakaCodec.decodeEnvelope(
		File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes(),
	) as DecodeResult.Success).value as MtpPose

	@Test fun acceptedPoseKeepsSequenceAgeSessionMappingAndSpaceAcrossReevaluation() {
		val pose = fixture()
		val adapter = MtpPoseAdapter()
		val first = adapter.adapt(pose, TrackerPosition.HIP, 12_000_000).provenance!!
		val repeated = adapter.adapt(pose, TrackerPosition.HIP, 12_000_000).provenance!!
		assertEquals(first, repeated)
		assertEquals(pose.sequence, first.sequence)
		assertEquals(12_000_000, first.sampleAtNanos)
		assertEquals(pose.mapping_revision, first.mappingRevision)
		assertEquals(pose.coordinate_space, first.space)
		assertTrue(first.sourceEpoch.contains(pose.session_id))
		assertEquals(pose.input.session_id, first.calibrationEpoch)
		assertEquals(pose.sequence + 1,
			adapter.adapt(pose.copy(sequence = pose.sequence + 1), TrackerPosition.HIP, 12_000_001).provenance!!.sequence)
		assertNotEquals(first.sourceEpoch,
			adapter.adapt(pose.copy(session_id = "new-session"), TrackerPosition.HIP, 12_000_000).provenance!!.sourceEpoch)
		assertNotEquals(first.mappingRevision,
			adapter.adapt(pose.copy(mapping_revision = pose.mapping_revision + 1), TrackerPosition.HIP, 12_000_000).provenance!!.mappingRevision)
		assertNotEquals(first.space,
			adapter.adapt(pose.copy(coordinate_space = pose.coordinate_space.copy(revision = pose.coordinate_space.revision + 1)),
				TrackerPosition.HIP, 12_000_000).provenance!!.space)
	}
}
