package dev.monaka.tracking

import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertNull
import kotlin.test.assertFalse
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import dev.slimevr.tracking.trackers.TrackerPosition

/** Characterizes a release-blocking contract gap, NOT a proof of conforming admission.
 * Local owned-object exclusion is tested separately. Wire ancestry remains unknown.
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

	@Test fun opaqueIdsPassCatalogSaveReloadAssignmentAndResolvedOutput(@TempDir dir: Path) {
		val original = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
		for ((index, prefix) in listOf("monaka-direct:", "monaka-solver:", "monaka-private:", "human://").withIndex()) {
			val source = prefix + "source"
			val p = original.copy(
				publisher_id = prefix + "publisher", source_id = source, tracker_id = prefix + "tracker",
				input = original.input.copy(source_id = source, device_id = prefix + "device"),
			)
			val bytes = (MonakaCodec.encodeEnvelope(p) as EncodeResult.Success).value
			val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
			val ref = TrackerReference.mtp(key)
			val config = MonakaConfiguration(p.coordinate_space)
			val path = dir.resolve("inputs-$index.json")
			config.save(path)
			MonakaRuntime({ emptyList() }, p.coordinate_space, config.assignments, clock = { 1_000_000_000L }).use { runtime ->
				val api = MonakaInputConfigurationApi(config, runtime.mtp, { emptyList() }, path)
				assertTrue(runtime.inbox.receive(bytes, 1_000_000_000L))
				runtime.tickSnapshot()
				val row = api.snapshot(1_000_000_000L).candidates.single()
				assertEquals(ref, row.reference)
				assertTrue(row.sixDofSelectable)
				api.saveAndApply(api.editSources(TrackerPosition.HIP, ref, ref, true, 0, 1_000_000_000L))
				assertEquals(config.assignments.snapshot().targets, MonakaConfiguration.load(path).assignments.snapshot().targets)
				val resolved = runtime.tick().getValue(TrackerPosition.HIP)
				assertEquals(ref.observationId, assertNotNull(resolved.position).sourceId)
				assertEquals(ref.observationId, assertNotNull(resolved.rotation).sourceId)
				val direct = config.assignments.snapshot().let { it.copy(targets = it.targets.mapValues { (_, value) -> value.copy(outputMode = OutputMode.DIRECT) }) }
				DirectConstraintOutput(direct, p.coordinate_space) { 10 }.use { output ->
					output.apply(mapOf(TrackerPosition.HIP to resolved))
					val visible = output.trackers.getValue(TrackerPosition.HIP)
					assertEquals(resolved.position, assertNotNull(visible.monakaOutputPose).position)
					assertEquals(resolved.rotation, visible.monakaOutputPose!!.rotation)
					assertFalse(FeedbackExclusion.accepts(visible))
				}
				assertEquals(1, runtime.inbox.diagnostics().getValue("WireOriginUnknown"))
			}
		}
	}

	@Test fun tupleCollisionsPeerAndRetiredSessionNeverRefreshAnotherPublisher() {
		val original = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
		val packets = listOf("a" to "same-source", "b" to "same-source", "ab" to "c", "a" to "bc").map { (publisher, source) ->
			original.copy(publisher_id = publisher, source_id = source, tracker_id = "same-name", input = original.input.copy(source_id = source))
		}
		fun key(p: MtpPose) = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val keys = packets.map(::key)
		assertEquals(keys.size, keys.map { it.observationId }.distinct().size)
		assertEquals(keys.size, keys.map { it.lifetimeId }.distinct().size)
		val bodies = listOf(TrackerPosition.HIP, TrackerPosition.CHEST, TrackerPosition.LEFT_FOOT, TrackerPosition.RIGHT_FOOT)
		val assignments = TrackerBodyAssignments(keys.zip(bodies).toMap())
		var now = 1_000_000_000L
		MonakaRuntime({ emptyList() }, original.coordinate_space, assignments, clock = { now }).use { runtime ->
			fun send(p: MtpPose, peer: String = p.publisher_id) {
				assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(p) as EncodeResult.Success).value, now, peer))
			}
			packets.forEach { send(it) }
			val firstTick = runtime.tickSnapshot()
			assertTrue(bodies.all { firstTick.constraints[it]?.position != null })
			val initial = runtime.mtp.samples()
			now += 100_000_000L
			send(packets[0]) // Duplicate does not regenerate age.
			send(packets[0].copy(sequence = packets[0].sequence + 1), "foreign-peer")
			runtime.tickSnapshot()
			assertEquals(initial, runtime.mtp.samples())
			assertEquals(1, runtime.inbox.diagnostics().getValue("DuplicateOrOldSequence"))
			assertEquals(1, runtime.inbox.diagnostics().getValue("PeerMismatch"))
			now += 500_000_000L
			val replacement = packets[0].copy(session_id = "33333333-3333-3333-3333-333333333333", sequence = 0)
			send(replacement)
			val replaced = runtime.tickSnapshot()
			assertNotNull(replaced.constraints[bodies[0]]?.position)
			assertNull(replaced.constraints[bodies[1]]?.position) // Stale peer is not refreshed by replacement.
			val fresh = runtime.mtp.samples().getValue(keys[0])
			send(packets[0].copy(sequence = packets[0].sequence + 100))
			runtime.tickSnapshot()
			assertEquals(fresh, runtime.mtp.samples().getValue(keys[0]))
			assertEquals(1, runtime.inbox.diagnostics().getValue("RetiredSession"))
			for (key in keys.drop(1)) assertEquals(initial.getValue(key), runtime.mtp.samples().getValue(key))
		}
	}
	/** Limitation characterization: nested/multiple relays do not acquire wire ancestry. */
	@Test fun renamedMultihopOutputsStillHaveNoDistinguishableWireOrigin() {
		var packet = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
		repeat(3) { hop ->
			val key = LogicalTracker(packet.source_id, packet.tracker_id, packet.publisher_id)
			val assignments = TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), outputMode = OutputMode.DIRECT) }
			MonakaRuntime({ emptyList() }, packet.coordinate_space, assignments, clock = { 1_000_000_000L }).use { runtime ->
				assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(packet) as EncodeResult.Success).value, 1_000_000_000L))
				DirectConstraintOutput(assignments.snapshot(), packet.coordinate_space) { 10 }.use { output ->
					output.apply(runtime.tick())
					val pose = assertNotNull(output.trackers.getValue(TrackerPosition.HIP).monakaOutputPose)
					val position = assertNotNull(pose.position).value
					val rotation = assertNotNull(pose.rotation).value
					packet = packet.copy(
						publisher_id = "relay-$hop", tracker_id = "renamed-$hop",
						position = listOf(position.x.toDouble(), position.y.toDouble(), position.z.toDouble()),
						orientation = listOf(rotation.x.toDouble(), rotation.y.toDouble(), rotation.z.toDouble(), rotation.w.toDouble()),
					)
					val independent = packet.copy() // Same bytes could also come from an unrelated source.
					assertContentEquals((MonakaCodec.encodeEnvelope(packet) as EncodeResult.Success).value, (MonakaCodec.encodeEnvelope(independent) as EncodeResult.Success).value)
				}
			}
		}
	}

}
