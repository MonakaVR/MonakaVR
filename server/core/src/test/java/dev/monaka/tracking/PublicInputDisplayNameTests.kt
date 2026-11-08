package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.Envelope
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.MtpTrackerState
import dev.monaka.tracking.mtp.MtpInbox
import dev.monaka.tracking.mtp.MtpObservationBackend
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.Tracker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PublicInputDisplayNameTests {
	private fun pose() = (MonakaCodec.decodeEnvelope(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").toFile().readBytes()) as DecodeResult.Success).value as MtpPose
	private fun key(p: MtpPose) = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
	private fun state(p: MtpPose, sequence: Long = 0, presence: String = "present") = MtpTrackerState(
		p.version, p.source_id, p.session_id, p.clock_id, sequence, p.timestamp_ns, p.sent_at_ns, p.timestamp_kind,
		p.tracker_id, presence, if (presence == "absent") "lost" else "initializing", p.coordinate_space,
		p.capabilities, "none", p.publisher_id, null, p.mapping_revision,
	)
	private fun send(inbox: MtpInbox, backend: MtpObservationBackend, message: Envelope, now: Long, peer: String = "public-test") {
		assertTrue(inbox.receive((MonakaCodec.encodeEnvelope(message) as EncodeResult.Success).value, now, peer))
		backend.poll(now)
	}

	@Test fun publicDeviceLabelIsAdditiveAndNeverChangesSelectionOrConfiguration(@TempDir dir: Path) {
		val p = pose().let { it.copy(input = it.input.copy(device_id = "physical-device-17")) }
		val ref = TrackerReference.mtp(key(p))
		val assignments = TrackerBodyAssignments().also { it.configureSources(TrackerPosition.HIP, ref, ref, true) }
		val configuration = MonakaConfiguration(p.coordinate_space, assignments)
		val path = dir.resolve("inputs.json"); configuration.save(path)
		val original = Files.readAllBytes(path)
		val generation = assignments.snapshot().generation
		MonakaRuntime({ emptyList() }, p.coordinate_space, assignments).use { runtime ->
			val api = MonakaInputConfigurationApi(configuration, runtime.mtp, { emptyList() }, path)
			send(runtime.inbox, runtime.mtp, state(p), 1_000_000_000)
			assertNull(api.snapshot(1_000_000_000).candidates.single().displayName, "state-only cannot invent a device ID")
			send(runtime.inbox, runtime.mtp, p, 1_000_000_000)
			assertEquals("physical-device-17", api.snapshot(1_000_000_000).candidates.single().displayName)
			val mapper = ObjectMapper()
			val response = mapper.readTree(api.handleJson("""{"type":"monaka_inputs_v1","id":"labels","action":"snapshot"}""", 1_000_000_000))
			val candidate = response["candidates"].single()
			assertEquals(ref.observationId, candidate["id"].asText())
			assertEquals(p.publisher_id, candidate["publisher"].asText())
			assertEquals(p.source_id, candidate["source"].asText())
			assertEquals(p.tracker_id, candidate["tracker"].asText())
			assertEquals("physical-device-17", candidate["displayName"].asText())
			val updated = p.copy(sequence = p.sequence + 1, input = p.input.copy(device_id = "physical-device-18"))
			send(runtime.inbox, runtime.mtp, updated, 1_010_000_000)
			assertEquals("physical-device-18", api.snapshot(1_010_000_000).candidates.single().displayName)
			assertEquals(generation, assignments.snapshot().generation)
			assertEquals(ref, assignments.snapshot().targets.getValue(TrackerPosition.HIP).mainTracker)
			assertContentEquals(original, Files.readAllBytes(path))
		}
	}

	@Test fun metadataSurvivesLossPauseAndSameKeySessionWithoutRevivingSamples() {
		val p = pose().let { it.copy(input = it.input.copy(device_id = "public-device")) }
		val inbox = MtpInbox(); val backend = MtpObservationBackend(inbox, TrackerBodyAssignments(), p.coordinate_space)
		fun label() = backend.capabilitiesSnapshot().getValue(key(p)).displayName
		send(inbox, backend, p, 1_000_000_000)
		send(inbox, backend, state(p, presence = "absent"), 1_010_000_000)
		assertEquals("public-device", label()); assertTrue(backend.samples().isEmpty())
		backend.invalidateSamples(); assertEquals("public-device", label())
		backend.suspend(true)
		send(inbox, backend, p.copy(sequence = p.sequence + 1, timestamp_ns = p.timestamp_ns + 1, sent_at_ns = p.sent_at_ns + 1,
			input = p.input.copy(device_id = "not-admitted-during-pause")), 1_020_000_000)
		assertEquals("public-device", label()); assertTrue(backend.samples().isEmpty())
		backend.suspend(false)
		val next = p.copy(session_id = "99999999-9999-4999-8999-999999999999", input = p.input.copy(device_id = "after-reconnect"))
		send(inbox, backend, state(next), 1_700_000_000)
		assertEquals("public-device", label()); assertTrue(backend.samples().isEmpty())
		send(inbox, backend, next, 1_710_000_000)
		assertEquals("after-reconnect", label()); assertTrue(backend.samples().isNotEmpty())
		send(inbox, backend, p.copy(sequence = p.sequence + 99, input = p.input.copy(device_id = "retired-injection")), 2_400_000_000)
		assertEquals("after-reconnect", label())
		backend.close(); assertTrue(backend.capabilitiesSnapshot().isEmpty())
		// Reusing this closed test backend proves metadata itself was cleared too.
		send(inbox, backend, state(next), 2_500_000_000)
		assertNull(label()); assertTrue(backend.samples().isEmpty())
		backend.close()
	}

	@Test fun legacyDescriptionUsesExistingPublicTrackerNameWithoutChangingPersistentReference(@TempDir dir: Path) {
		val p = pose(); val configuration = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json"); configuration.save(path)
		val original = Files.readAllBytes(path)
		val tracker = Tracker(null, 3, "opaque-persistent-name", displayName = "public tracker description", trackerPosition = TrackerPosition.HIP, hasRotation = true)
		MonakaRuntime({ emptyList() }, p.coordinate_space, configuration.assignments).use { runtime ->
			val api = MonakaInputConfigurationApi(configuration, runtime.mtp, { listOf(tracker) }, path)
			assertEquals("public tracker description", api.snapshot(1).candidates.single().displayName)
			tracker.customName = "my public consumer name"
			val candidate = api.snapshot(2).candidates.single()
			assertEquals("my public consumer name", candidate.displayName)
			assertEquals(TrackerReference.slime("opaque-persistent-name"), candidate.reference)
			assertContentEquals(original, Files.readAllBytes(path))
		}
	}

	@Test fun rejectedReplayPeerMappingAndConflictCannotReplaceAnAdmittedLabel() {
		val p = pose().let { it.copy(mapping_revision = 10, input = it.input.copy(device_id = "accepted")) }
		val inbox = MtpInbox(); val backend = MtpObservationBackend(inbox, TrackerBodyAssignments(), p.coordinate_space)
		fun inject(value: MtpPose, peer: String = "public-test") {
			send(inbox, backend, value, 1_010_000_000, peer)
			assertEquals("accepted", backend.capabilitiesSnapshot().getValue(key(p)).displayName)
		}
		send(inbox, backend, p, 1_000_000_000)
		val bad = p.copy(input = p.input.copy(device_id = "injection"))
		inject(bad)
		inject(bad.copy(sequence = p.sequence + 1), "different-peer")
		inject(bad.copy(sequence = p.sequence + 2, mapping_revision = 9))
		inject(bad.copy(sequence = p.sequence + 4, capabilities = p.capabilities + "charging"))
		backend.close()
	}

	@Test fun admittedMetadataDoesNotDependOnPoseComponentUsabilityOrFloatConversion() {
		val p = pose()
		val inbox = MtpInbox()
		val assignments = TrackerBodyAssignments(mapOf(key(p) to TrackerPosition.HIP))
		val backend = MtpObservationBackend(inbox, assignments, p.coordinate_space)
		send(inbox, backend, p, 1_000_000_000)
		val overflow = p.copy(sequence = p.sequence + 1, position = listOf(1e100, 0.0, 0.0), input = p.input.copy(device_id = "admitted-metadata-bad-position"))
		send(inbox, backend, overflow, 1_010_000_000)
		val afterOverflow = backend.capabilitiesSnapshot().getValue(key(p))
		assertEquals("admitted-metadata-bad-position", afterOverflow.displayName)
		assertFalse(afterOverflow.positionValid); assertFalse(afterOverflow.orientationValid)
		assertTrue(backend.samples().isEmpty(), "existing Float conversion guard still clears the invalid tracking sample")
		val unusable = p.copy(sequence = p.sequence + 2, modality = "none", position = null, orientation = null,
			validity = dev.monaka.protocol.v2.Validity(false, false), confidence = dev.monaka.protocol.v2.Confidence(0.0, 0.0),
			tracking_state = "lost", input = p.input.copy(device_id = "admitted-metadata-lost"))
		send(inbox, backend, unusable, 1_020_000_000)
		val afterLost = backend.capabilitiesSnapshot().getValue(key(p))
		assertEquals("admitted-metadata-lost", afterLost.displayName)
		assertFalse(afterLost.positionValid); assertFalse(afterLost.orientationValid)
		assertEquals("none", afterLost.modality)
		backend.close()
	}

	@Test fun displayHistoryIsBoundedWithoutRetainingOldDeviceAdmissionState() {
		val p = pose()
		val inbox = MtpInbox(); val backend = MtpObservationBackend(inbox, TrackerBodyAssignments(), p.coordinate_space)
		for (index in 0 until 1024) send(inbox, backend, p.copy(tracker_id = "channel-$index", input = p.input.copy(device_id = "device-$index")), 1_000_000_000)
		val next = p.copy(session_id = "99999999-9999-4999-8999-999999999999", tracker_id = "new-channel", input = p.input.copy(device_id = "new-device"))
		send(inbox, backend, next, 1_700_000_000)
		assertEquals(1, backend.capabilitiesSnapshot().size, "old devices are still removed on session change")
		send(inbox, backend, state(next.copy(tracker_id = "channel-0")), 1_710_000_000)
		assertNull(backend.capabilitiesSnapshot().getValue(key(next.copy(tracker_id = "channel-0"))).displayName, "oldest display-only entry was evicted")
		send(inbox, backend, state(next.copy(tracker_id = "channel-1023")), 1_720_000_000)
		assertEquals("device-1023", backend.capabilitiesSnapshot().getValue(key(next.copy(tracker_id = "channel-1023"))).displayName)
		backend.close()
	}
}
