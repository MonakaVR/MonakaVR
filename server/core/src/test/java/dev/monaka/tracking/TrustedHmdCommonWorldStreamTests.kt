package dev.monaka.tracking

import com.google.gson.JsonParser
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.mtp.MtpInbox
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.*

internal class TrustedHmdCommonWorldStreamTests {
	private val fixtures = File(System.getProperty("monaka.fixtures"))
	private val ordered = File(fixtures, "v2.1/ordered-streams")
	private val normal = File(ordered, "common-normal.jsonl").readLines()
	private fun decode(bytes: String) = (MonakaCodec.decodeEnvelope(bytes.toByteArray()) as DecodeResult.Success).value
	private val world = decode(normal[0]) as CommonWorldAuthorityPublication
	private val mapping = decode(normal[1]) as CommonWorldMappingPublication
	private val pose = decode(normal[2]) as TrustedHmdCommonPose
	private val unavailable = decode(normal[3]) as TrustedHmdCommonUnavailable
	private val revoke = decode(File(ordered, "common-revoke-no-pose.jsonl").readLines().last()) as CommonWorldRevocation
	private val revokeMapping = decode(File(ordered, "mapping-revoke-no-pose.jsonl").readLines().last()) as CommonWorldMappingRevocation
	private val s2 = "22222222-2222-4222-8222-222222222222"
	private val s3 = "33333333-3333-4333-8333-333333333333"
	private fun state(capacity: Int = 128) = TrustedHmdCommonWorldStream(world.publisher_id, capacity)
	private fun begin(s: TrustedHmdCommonWorldStream, session: String = world.session_id) =
		assertTrue(s.beginAuthorizedPublisherSession(world.publisher_id, session, world.clock_id))
	private fun bootstrap(s: TrustedHmdCommonWorldStream) {
		begin(s); assertEquals("world", s.receiveBytes(normal[0].toByteArray(), 1))
		assertEquals("mapping", s.receiveBytes(normal[1].toByteArray(), 2))
	}
	private fun live(capacity: Int = 128) = state(capacity).also {
		bootstrap(it); assertEquals("pose", it.receiveBytes(normal[2].toByteArray(), 3))
	}
	private fun closed(s: TrustedHmdCommonWorldStream) {
		assertFalse(s.streamValid()); assertNull(s.candidate()); assertNull(s.currentWorld()); assertNull(s.currentMapping())
	}

	@TestFactory fun exactKitCommonOrderedScenarios(): List<DynamicTest> =
		JsonParser.parseString(File(ordered, "index.json").readText()).asJsonArray.mapNotNull { row ->
			val file = File(fixtures, "v2.1/" + row.asJsonObject["file"].asString)
			val bytes = file.readLines()
			if (JsonParser.parseString(bytes[0]).asJsonObject["protocol"].asString != "monaka.common_world") null
			else DynamicTest.dynamicTest(file.name) {
				val s = state(); val authorized = mutableSetOf<String>()
				bytes.forEachIndexed { i, line ->
					val json = JsonParser.parseString(line).asJsonObject
					val session = json["session_id"].asString
					// Fixture driver is the explicit caller authorization seam; receiver never self-authorizes.
					if (session !in authorized && json["sequence"].asString == "0") {
						assertTrue(session == world.session_id || session == s2)
						begin(s, session); authorized += session
					}
					assertEquals(row.asJsonObject["outcomes"].asJsonArray[i].asString,
						s.receiveBytes(line.toByteArray(), 100L + i), "${file.name}:$i")
					if (row.asJsonObject["outcomes"].asJsonArray[i].asString in listOf("revoked", "gap", "conflict")) assertNull(s.candidate())
				}
				if (file.name in listOf("common-revoke-no-pose.jsonl", "common-seq102-revoke-no-pose.jsonl")) {
					assertNull(s.currentWorld()); assertNull(s.currentMapping()); assertNull(s.candidate())
				}
			}
		}

	@Test fun explicitPublisherAuthorizationCannotBeInferredFromValidBytes() {
		val s = state(); assertEquals("unauthorized", s.receiveBytes(normal[0].toByteArray(), 1))
		assertFalse(s.beginAuthorizedPublisherSession("foreign", world.session_id, world.clock_id))
		assertFailsWith<IllegalArgumentException> { TrustedHmdCommonWorldStream(" ") }
		begin(s); assertEquals("unauthorized", s.receive(world.copy(publisher_id = "foreign"), 1))
		assertEquals("world", s.receive(world, 1)); assertNull(s.currentMapping())
	}

	@Test fun poseBeforeWorldFailsBootstrapAndCannotRepairTheSession() {
		val s = state(); begin(s); assertEquals("bootstrap-failed", s.receive(pose.copy(sequence = 0), 1))
		closed(s); assertEquals("closed", s.receive(world, 2)); assertFalse(s.beginAuthorizedPublisherSession(world.publisher_id, world.session_id, world.clock_id))
	}

	@Test fun poseBeforeMappingCannotBootstrapAtALaterSequence() {
		val s = state(); begin(s); assertEquals("world", s.receive(world, 1))
		assertEquals("untrusted", s.receive(pose.copy(sequence = 1), 2)); assertNull(s.candidate())
		// A complete snapshot still requires seq1 mapping. Never silently repair a missed bootstrap event.
		assertEquals("untrusted", s.receive(pose.copy(sequence = 2), 3)); assertNull(s.currentMapping())
		assertEquals("untrusted", s.receive(mapping.copy(sequence = 3), 4)); assertNull(s.currentMapping())
	}

	@Test fun exactProjectionUsesXyzwToWxyzAndKeepsAllWireIdentities() {
		val s = live(); val w = assertNotNull(s.currentWorld()).authority; val m = assertNotNull(s.currentMapping()).snapshot
		assertEquals(world.world.owner_id, w.ownerId); assertEquals(world.world.world_epoch, w.epoch.value)
		assertEquals(world.world.coordinate_space, w.space)
		assertEquals(world.anchor_source.source_id, w.anchor.sourceId)
		assertEquals(world.anchor_source.source_authority_session_epoch, w.anchor.sourceSessionEpoch)
		assertEquals(world.anchor_source.source_space_id, w.anchor.sourceSpaceId)
		assertEquals(world.anchor_source.source_space_generation, w.anchor.generation)
		assertEquals(mapping.mapping.calibration_epoch, m.calibrationEpoch.value); assertEquals(mapping.mapping.mapping_revision, m.mappingRevision.value)
		assertEquals(Quaternion.IDENTITY, m.transform.rotation); assertEquals(Vector3(0f, 0f, 0f), m.transform.translation)
		val c = assertNotNull(s.candidate()); assertEquals(pose.source.observation_id, c.pose.source.identity.observationId)
		assertEquals(pose.source.source_locate_time_ns, c.sourceLocateTimeNs); assertEquals(pose.source.source_time_domain_id, c.sourceTimeDomainId)
		assertEquals(pose.validity, c.validity); assertEquals(3L, c.receivedAtNanos)
	}

	@ParameterizedTest @ValueSource(strings = ["owner", "world", "space", "worldRevision", "source", "session", "generation", "sourceSpace", "calibration", "mapping"])
	fun poseMustMatchEveryCurrentAuthorityFact(field: String) {
		val s = live(); var ref = pose.mapping
		ref = when (field) {
			"owner" -> ref.copy(world = ref.world.copy(owner_id = "other"))
			"world" -> ref.copy(world = ref.world.copy(world_epoch = "other"))
			"space" -> ref.copy(world = ref.world.copy(coordinate_space = ref.world.coordinate_space.copy(id = "other")))
			"worldRevision" -> ref.copy(world = ref.world.copy(coordinate_space = ref.world.coordinate_space.copy(revision = 2)))
			"source" -> ref.copy(source_space = ref.source_space.copy(source_id = "other"))
			"session" -> ref.copy(source_space = ref.source_space.copy(source_authority_session_epoch = "other"))
			"generation" -> ref.copy(source_space = ref.source_space.copy(source_space_generation = 1))
			"sourceSpace" -> ref.copy(source_space = ref.source_space.copy(source_space_id = "other"))
			"calibration" -> ref.copy(calibration_epoch = "other")
			else -> ref.copy(mapping_revision = 8)
		}
		val changed = pose.copy(sequence = 3, mapping = ref, source = pose.source.copy(source_space = ref.source_space))
		assertEquals("untrusted", s.receive(changed, 4)); assertNull(s.candidate())
	}

	@Test fun unavailableRequiresNewObservationAndNeverRefreshesAge() {
		val s = live(); assertEquals("unavailable", s.receive(unavailable, 100)); assertNull(s.candidate()); assertNotNull(s.currentMapping())
		assertEquals("duplicate-observation", s.receive(pose.copy(sequence = 4), 101)); assertNull(s.candidate())
		val new = pose.copy(sequence = 5, source = pose.source.copy(observation_id = pose.source.observation_id + 1))
		assertEquals("pose", s.receive(new, 102)); assertEquals(102L, s.candidate()!!.receivedAtNanos)
	}

	@Test fun mappingUpdateRevalidatesTheSameSourceWithoutRefreshingAge() {
		val s = live(); val m = mapping.mapping.copy(mapping_revision = 8)
		assertEquals("mapping", s.receive(mapping.copy(sequence = 3, mapping = m), 100)); assertNull(s.candidate())
		assertEquals("pose", s.receive(pose.copy(sequence = 4, mapping = m), 101)); assertEquals(3L, s.candidate()!!.receivedAtNanos)
		assertEquals("unavailable", s.receive(unavailable.copy(sequence = 5, mapping = m), 102))
		assertEquals("duplicate-observation", s.receive(pose.copy(sequence = 6, mapping = m), 103)); assertNull(s.candidate())
	}

	@Test fun streamResendDuplicateDoesNotRefreshAndUnknownFieldsAreDiscarded() {
		val s = live(); assertEquals("duplicate", s.receive(pose.copy(sent_at_ns = pose.sent_at_ns + 100), 200))
		val json = JsonParser.parseString(normal[2]).asJsonObject; json.addProperty("future", "ignored")
		assertEquals("duplicate", s.receiveBytes(json.toString().toByteArray(), 300)); assertEquals(3L, s.candidate()!!.receivedAtNanos)
	}

	@ParameterizedTest @ValueSource(strings = ["timestamp", "version", "position", "validity", "clock", "type"])
	fun sameSequenceChangedImmutableContentClosesImmediately(field: String) {
		val s = live()
		val m = when (field) {
			"timestamp" -> pose.copy(timestamp_ns = pose.timestamp_ns - 1)
			"version" -> pose.copy(version = Version(2, 2))
			"position" -> pose.copy(common_position = listOf(2.0, 2.0, 3.0))
			"validity" -> pose.copy(validity = pose.validity.copy(position_tracked = false))
			"clock" -> pose.copy(clock_id = s3)
			else -> unavailable.copy(sequence = 2)
		}
		assertEquals("conflict", s.receive(m, 100)); closed(s)
		assertEquals(CommonWorldRevocationReason.EVENT_STREAM_LOST, s.lastRevocation()!!.reason)
		assertEquals("closed", s.receive(unavailable, 101))
	}

	@Test fun gapClosesWorldAndMappingEvenIfMissingRevocationArrivesLater() {
		val s = live(); val oldWorld = s.currentWorld(); val oldMapping = s.currentMapping()
		assertEquals("gap", s.receive(pose.copy(sequence = 4), 100)); closed(s)
		assertEquals("closed", s.receive(revoke, 101)); begin(s, s2)
		assertEquals("untrusted", s.receive(world.copy(session_id = s2), 102))
		assertNull(s.currentWorld()); assertNull(s.currentMapping()); assertNotNull(oldWorld); assertNotNull(oldMapping)
	}

	@Test fun freshSessionAfterGapNeedsFreshWorldAndCalibration() {
		val s = live(); assertEquals("gap", s.receive(pose.copy(sequence = 4), 4)); begin(s, s2)
		val w = world.world.copy(world_epoch = "world-B", coordinate_space = world.world.coordinate_space.copy(revision = 2))
		assertEquals("world", s.receive(world.copy(session_id = s2, world = w), 5))
		val m = mapping.mapping.copy(world = w, calibration_epoch = "calibration-B")
		assertEquals("mapping", s.receive(mapping.copy(session_id = s2, mapping = m), 6))
		assertEquals("pose", s.receive(pose.copy(session_id = s2, mapping = m), 7))
		assertEquals(3L, s.candidate()!!.receivedAtNanos)
		assertEquals("retired", s.receive(pose, 8)); assertNotNull(s.candidate())
	}

	@Test fun healthyReconnectRequiresSnapshotAndKeepsFrozenMappingHighWater() {
		val s = live(); begin(s, s2); assertNull(s.currentWorld()); assertNull(s.currentMapping()); assertNull(s.candidate())
		assertEquals("world", s.receive(world.copy(session_id = s2), 100))
		assertEquals("mapping", s.receive(mapping.copy(session_id = s2), 101))
		assertEquals("duplicate-observation", s.receive(pose.copy(session_id = s2), 102)); assertNull(s.candidate())
		assertEquals("pose", s.receive(pose.copy(session_id = s2, sequence = 3, source = pose.source.copy(observation_id = pose.source.observation_id + 1)), 103))
		assertEquals("retired", s.receive(pose, 104)); assertNotNull(s.candidate())
	}

	@Test fun transportDisconnectIsLocalRevocationAndOldEpochCannotReactivate() {
		val s = live(); s.onTransportDisconnected(); closed(s)
		assertEquals(CommonWorldRevocationReason.EVENT_STREAM_LOST, s.lastRevocation()!!.reason)
		assertFalse(s.beginAuthorizedPublisherSession(world.publisher_id, world.session_id, world.clock_id)); begin(s, s2)
		assertEquals("untrusted", s.receive(world.copy(session_id = s2), 100)); assertNull(s.currentWorld())
	}

	@ParameterizedTest @ValueSource(strings = ["positionOverflow", "translationOverflow", "sourceQuaternion", "mappingQuaternion", "commonQuaternion", "commonPosition", "commonOrientation"])
	fun floatValidationAndExactRelationRejectValidWireThatCannotProject(field: String) {
		val s = state(); bootstrap(s)
		val m: Envelope = when (field) {
			"translationOverflow" -> mapping.copy(sequence = 2, mapping = mapping.mapping.copy(mapping_revision = 8), transform = mapping.transform.copy(translation_xyz = listOf(Double.MAX_VALUE, 0.0, 0.0)))
			"mappingQuaternion" -> mapping.copy(sequence = 2, mapping = mapping.mapping.copy(mapping_revision = 8), transform = mapping.transform.copy(rotation_xyzw = listOf(0.0, 0.0, 0.0, 1.000009)))
			"positionOverflow" -> pose.copy(source_position = listOf(Double.MAX_VALUE, 0.0, 0.0))
			"sourceQuaternion" -> pose.copy(source_orientation = listOf(0.0, 0.0, 0.0, 1.000009))
			"commonQuaternion" -> pose.copy(common_orientation = listOf(0.0, 0.0, 0.0, 1.000009))
			"commonPosition" -> pose.copy(common_position = listOf(Math.nextUp(1f).toDouble(), 2.0, 3.0))
			else -> pose.copy(common_orientation = listOf(0.0, 0.0, 0.0, -1.0))
		}
		assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(m))
		assertEquals("untrusted", s.receive(m, 100)); assertNull(s.candidate())
	}

	@Test fun nonidentityRelationUsesFloatHamiltonMathAndRawBitEquality() {
		val s = state(); begin(s); assertEquals("world", s.receive(world, 1))
		val r = Quaternion(0f, 0f, 1f, 0f); val t = Vector3(2f, -3f, 4f)
		val tr = RigidTransform(listOf(r.x.toDouble(), r.y.toDouble(), r.z.toDouble(), r.w.toDouble()), listOf(t.x.toDouble(), t.y.toDouble(), t.z.toDouble()))
		assertEquals("mapping", s.receive(mapping.copy(transform = tr), 2))
		val cp = r.sandwich(Vector3(1f, 2f, 3f)) + t; val cq = r * Quaternion.IDENTITY
		val p = pose.copy(common_position = listOf(cp.x.toDouble(), cp.y.toDouble(), cp.z.toDouble()), common_orientation = listOf(cq.x.toDouble(), cq.y.toDouble(), cq.z.toDouble(), cq.w.toDouble()))
		assertEquals("pose", s.receive(p, 3)); assertEquals(cp, s.candidate()!!.pose.position); assertEquals(cq, s.candidate()!!.pose.orientation)
	}

	@Test fun knownObservationReplayCannotRefreshAndChangedSourceFactsRevoke() {
		val s = live(); assertEquals("old-observation", s.receive(pose.copy(sequence = 3, source = pose.source.copy(observation_id = pose.source.observation_id - 1)), 100))
		assertEquals(3L, s.candidate()!!.receivedAtNanos)
		assertEquals("duplicate-observation", s.receive(pose.copy(sequence = 4), 101)); assertEquals(3L, s.candidate()!!.receivedAtNanos)
		assertEquals("observation-conflict", s.receive(pose.copy(sequence = 5, source_position = listOf(2.0, 2.0, 3.0), common_position = listOf(2.0, 2.0, 3.0)), 102)); closed(s)
	}

	@Test fun wrongProtocolDoesNotMutateEitherAuthorityOrMtpInbox() {
		val s = live(); val old = s.candidate()
		for (name in listOf("v2/mtp-pose.json", "v2/tracker-state.json", "v2/observation.json", "v2/device-state.json")) {
			assertEquals("wrong-protocol", s.receiveBytes(File(fixtures, name).readBytes(), 100))
		}
		for (line in File(ordered, "source-normal.jsonl").readLines()) assertEquals("wrong-protocol", s.receiveBytes(line.toByteArray(), 100))
		assertSame(old, s.candidate()); assertEquals(2L, s.sequenceHighWater())
		val inbox = MtpInbox()
		for (line in normal) assertFalse(inbox.receive(line.toByteArray(), 100))
		assertEquals(normal.size.toLong(), inbox.diagnostics()["WrongProtocol"]); assertTrue(inbox.drainAllBounded().isEmpty())
	}

	@Test fun malformedAuthorizedBytesMayHideRevocationAndCloseTrust() {
		val s = live(); assertEquals("malformed", s.receiveBytes("{".toByteArray(), 100)); closed(s)
	}

	@Test fun lowerReplayChangedClockAndContentCannotInvalidateCurrentTrust() {
		val s = live(); val candidate = s.candidate()
		assertEquals("old", s.receive(mapping.copy(clock_id = s3, transform = mapping.transform.copy(translation_xyz = listOf(99.0, 0.0, 0.0))), 100))
		assertSame(candidate, s.candidate()); assertTrue(s.streamValid())
	}

	@Test fun retentionCapacityNeverEvictsAndClosesTrustPermanently() {
		val s = live(1); assertFalse(s.beginAuthorizedPublisherSession(world.publisher_id, s2, world.clock_id)); closed(s)
		assertFalse(s.beginAuthorizedPublisherSession(world.publisher_id, s3, world.clock_id))
	}

	@Test fun maxSequenceDoesNotWrapAndFreshnessIsNeverRenewedByOldSequence() {
		val s = live()
		// Reach the terminal boundary without iterating 2^63 valid events; keep the accepted message coherent.
		val terminal = pose.copy(sequence = Long.MAX_VALUE - 1)
		s.javaClass.getDeclaredField("lastSequence").also { it.isAccessible = true }.set(s, terminal.sequence)
		s.javaClass.getDeclaredField("last").also { it.isAccessible = true }.set(s, terminal)
		assertEquals("pose", s.receive(pose.copy(sequence = Long.MAX_VALUE, source = pose.source.copy(observation_id = pose.source.observation_id + 1)), 100))
		assertEquals("old", s.receive(pose.copy(sequence = 0), 101)); assertEquals(100L, s.candidate()!!.receivedAtNanos)
	}

	@Test fun immutableDoubleMappingConflictCannotHideInFloatRounding() {
		val s = live()
		val changed = mapping.copy(sequence = 3, transform = mapping.transform.copy(translation_xyz = listOf(Double.MIN_VALUE, 0.0, 0.0)))
		assertEquals(0f, changed.transform.translation_xyz[0].toFloat())
		assertEquals("untrusted", s.receive(changed, 100)); assertNull(s.currentMapping()); assertNull(s.candidate()); assertNotNull(s.currentWorld())
		begin(s, s2); assertEquals("world", s.receive(world.copy(session_id = s2), 101))
		assertEquals("untrusted", s.receive(mapping.copy(session_id = s2), 102)); assertNull(s.currentMapping())
	}

	@Test fun mappingRevocationWithoutPoseDoesNotRetireWorldButCannotResurrectCalibration() {
		val s = live(); assertEquals("revoked", s.receive(revokeMapping, 100))
		assertNotNull(s.currentWorld()); assertNull(s.currentMapping()); assertNull(s.candidate())
		assertEquals("untrusted", s.receive(mapping.copy(sequence = 4, mapping = mapping.mapping.copy(mapping_revision = 8)), 101))
		val new = mapping.mapping.copy(mapping_revision = 8, calibration_epoch = "calibration-B")
		assertEquals("mapping", s.receive(mapping.copy(sequence = 5, mapping = new), 102))
		assertEquals("pose", s.receive(pose.copy(sequence = 6, mapping = new), 103)); assertEquals(3L, s.candidate()!!.receivedAtNanos)
	}

	@Test fun worldReplacementWithoutOrderedRevocationCannotAcquireTrust() {
		val s = live(); val old = s.currentWorld()
		val next = world.world.copy(world_epoch = "world-B", coordinate_space = world.world.coordinate_space.copy(revision = 2))
		assertEquals("untrusted", s.receive(world.copy(sequence = 3, world = next), 100))
		assertSame(old, s.currentWorld()); assertNull(s.currentMapping()); assertNull(s.candidate())
	}

	@Test fun sourceGenerationHighWaterSurvivesWorldAndPublisherReplacement() {
		val s = live(); assertEquals("revoked", s.receive(revoke.copy(reason = "source_space_changed"), 100))
		begin(s, s2)
		val nextWorld = world.world.copy(world_epoch = "world-B", coordinate_space = world.world.coordinate_space.copy(revision = 2))
		assertEquals("untrusted", s.receive(world.copy(session_id = s2, world = nextWorld), 101))
		begin(s, s3)
		val nextSource = world.anchor_source.copy(source_space_generation = 1)
		assertEquals("world", s.receive(world.copy(session_id = s3, world = nextWorld, anchor_source = nextSource), 102))
		val m = mapping.mapping.copy(world = nextWorld, source_space = nextSource, calibration_epoch = "calibration-B")
		assertEquals("mapping", s.receive(mapping.copy(session_id = s3, mapping = m), 103))
		val oldObservation = pose.copy(session_id = s3, mapping = m, source = pose.source.copy(source_space = nextSource, observation_id = pose.source.observation_id - 1))
		assertEquals("old-observation", s.receive(oldObservation, 104)); assertNull(s.candidate())
	}

	@Test fun lostSourceSessionTokenCannotReturnAtHigherGeneration() {
		val s = live(); assertEquals("revoked", s.receive(revoke.copy(reason = "source_session_changed"), 100)); begin(s, s2)
		val nextWorld = world.world.copy(world_epoch = "world-B", coordinate_space = world.world.coordinate_space.copy(revision = 2))
		assertEquals("untrusted", s.receive(world.copy(session_id = s2, world = nextWorld, anchor_source = world.anchor_source.copy(source_space_generation = 999)), 101))
		assertNull(s.currentWorld()); assertNull(s.candidate())
	}

	@Test fun sourceRetentionSaturationClosesFoundation() {
		val s = live(1)
		assertEquals("revoked", s.receive(revoke, 4))
		val nextWorld = world.world.copy(world_epoch = "world-B", coordinate_space = world.world.coordinate_space.copy(revision = 2))
		assertEquals("untrusted", s.receive(world.copy(sequence = 4, world = nextWorld, anchor_source = world.anchor_source.copy(source_authority_session_epoch = "another-token")), 5))
		closed(s)
		assertFalse(s.beginAuthorizedPublisherSession(world.publisher_id, s2, world.clock_id))
	}

	@Test fun retiredWorldCapacitySaturationClosesReceiverPermanently() {
		val s = live(1); assertEquals("revoked", s.receive(revoke, 4))
		val nextWorld = world.world.copy(world_epoch = "world-B", coordinate_space = world.world.coordinate_space.copy(revision = 2))
		assertEquals("world", s.receive(world.copy(sequence = 4, world = nextWorld), 5))
		assertEquals("revoked", s.receive(revoke.copy(sequence = 5, world = nextWorld), 6)); closed(s)
		assertEquals(CommonWorldRevocationReason.CAPACITY_EXHAUSTED, s.lastRevocation()!!.reason)
	}

	@Test fun callerListMutationCannotRewriteFrozenStreamOrMappingFacts() {
		val s = state(); begin(s); assertEquals("world", s.receive(world, 1))
		val translation = mutableListOf(0.0, 0.0, 0.0)
		val input = mapping.copy(transform = mapping.transform.copy(translation_xyz = translation))
		assertEquals("mapping", s.receive(input, 2)); translation[0] = 1.0
		assertEquals("duplicate", s.receive(mapping, 100)); assertEquals(Vector3(0f, 0f, 0f), s.currentMapping()!!.snapshot.transform.translation)
	}
}

