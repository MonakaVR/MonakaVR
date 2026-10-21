package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.Envelope
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.MtpTrackerState
import dev.monaka.protocol.v2.Validity
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaticInputSelectionTests {
	private fun fixture() = (MonakaCodec.decodeEnvelope(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").toFile().readBytes()) as DecodeResult.Success).value as MtpPose
	private fun ref(p: MtpPose) = TrackerReference.mtp(LogicalTracker(p.source_id, p.tracker_id, p.publisher_id))
	private fun state(p: MtpPose, seq: Long = 0, presence: String = "present", tracking: String = "initializing") = MtpTrackerState(
		p.version, p.source_id, p.session_id, p.clock_id, seq, p.timestamp_ns, p.sent_at_ns, p.timestamp_kind,
		p.tracker_id, presence, tracking, p.coordinate_space, p.capabilities, "none", p.publisher_id, null, p.mapping_revision,
	)
	private fun send(r: MonakaRuntime, p: Envelope, at: Long) {
		assertTrue(r.inbox.receive((MonakaCodec.encodeEnvelope(p) as EncodeResult.Success).value, at))
		r.tick()
	}
	private fun q(p: MtpPose, id: String = "q-only") = p.copy(
		tracker_id = id,
		capabilities = listOf("orientation"),
		modality = "rotation_only",
		position = null,
		validity = Validity(false, true),
		confidence = Confidence(0.0, 1.0),
		tracking_state = "degraded",
	)

	@Test fun stateFirstCatalogKeepsSupportWithoutPoseAndNeverAssigns(@TempDir dir: Path) {
		var now = 1_000_000_000L
		val p = fixture()
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { now }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			val before = Files.readAllBytes(path)
			send(r, state(p), now)
			val first = api.snapshot(now).candidates.single()
			assertTrue(first.sixDofSelectable)
			assertFalse(first.qUsableNow)
			assertEquals(0, c.assignments.snapshot().generation)
			assertTrue(c.assignments.snapshot().targets.isEmpty())
			send(r, p, now)
			assertTrue(api.snapshot(now).candidates.single().qUsableNow)
			now += 600_000_000
			send(r, state(p, 1), now)
			val stale = api.snapshot(now).candidates.single()
			assertTrue(stale.sixDofSelectable)
			assertFalse(stale.qUsableNow)
			assertContentEquals(before, Files.readAllBytes(path))
			assertEquals(0, c.assignments.snapshot().generation)
		}
	}

	@ParameterizedTest
	@ValueSource(strings = ["rotation_only", "none", "confidence", "candidate"])
	fun supportAndDynamicComponentGatesRemainSeparate(mode: String, @TempDir dir: Path) {
		var now = 1_000_000_000L
		val p = fixture()
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { now }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p, now)
			val next = when (mode) {
				"rotation_only" -> p.copy(sequence = p.sequence + 1, modality = mode, position = null, validity = Validity(false, true), confidence = Confidence(0.0, 1.0), tracking_state = "degraded")

				"confidence" -> p.copy(sequence = p.sequence + 1, confidence = Confidence(0.0, 0.0))

				else -> p.copy(
					sequence = p.sequence + 1,
					modality = "none",
					position = null,
					orientation = null,
					validity = Validity(false, false),
					confidence = Confidence(0.0, 0.0),
					tracking_state = "lost",
				)
			}
			if (mode == "confidence") {
				// Canonical wire already rejects valid=true/confidence=0. Keep that guard,
				// and exercise the adapter's independent defensive gate without admission.
				assertIs<EncodeResult.Failure>(MonakaCodec.encodeEnvelope(next))
				val adapted = dev.monaka.tracking.mtp.MtpPoseAdapter().adapt(next, TrackerPosition.HIP, now)
				assertNull(adapted.position)
				assertNull(adapted.rotation)
				now += 600_000_000
				r.tick()
			} else {
				now += 10_000_000
				send(r, next, now)
			}
			val catalog = api.snapshot(now).candidates.single()
			assertEquals(p.capabilities.toSet(), catalog.capabilities!!.components)
			assertTrue(catalog.sixDofSelectable)
			assertFalse(catalog.sixDofUsableNow)
			assertEquals(mode == "rotation_only", catalog.qUsableNow)
			now += 10_000_000
			send(r, p.copy(sequence = p.sequence + 2), now)
			assertTrue(api.snapshot(now).candidates.single().sixDofUsableNow)
		}
	}

	@Test fun explicitPosQFirstDefaultManualClearSaveReloadAndSixDofOnly(@TempDir dir: Path) {
		val p = fixture()
		val at = 1_000_000_000L
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { at }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p, at)
			val first = api.editSources(TrackerPosition.HIP, ref(p), ref(p), false, 0, at)
			assertTrue(c.assignments.snapshot().targets.isEmpty()) // Cancelled/unsaved preview changes nothing.
			api.saveAndApply(first)
			assertEquals(ref(p), c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!.sixDofSource)
			api.saveAndApply(api.editSources(TrackerPosition.HIP, ref(p), null, true, api.snapshot(at).generation, at))
			val cleared = c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!
			assertNull(cleared.sixDofSource)
			assertTrue(cleared.sixDofManuallySelected)
			assertEquals(c.assignments.snapshot().targets, MonakaConfiguration.load(path).assignments.snapshot().targets)
			send(r, p.copy(sequence = p.sequence + 1), at)
			assertNull(c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!.sixDofSource)
			api.saveAndApply(api.editSources(TrackerPosition.HIP, null, ref(p), true, api.snapshot(at).generation, at))
			assertNull(c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!.qSource)
			assertEquals(ref(p), c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).mainTracker)
			assertEquals(c.assignments.snapshot().targets, MonakaConfiguration.load(path).assignments.snapshot().targets)
		}
	}

	@Test fun qOnlyAndSlimeNeverAutoFillSixDofAndFeedbackIsExcluded(@TempDir dir: Path) {
		val p = q(fixture())
		val at = 1_000_000_000L
		val slime = Tracker(null, 123, "legacy-q", trackerPosition = null, hasRotation = true)
		val feedback = Tracker(null, 124, "monaka-direct:excluded", trackerPosition = null, hasRotation = true)
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ listOf(slime, feedback) }, p.coordinate_space, c.assignments, clock = { at }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { listOf(slime, feedback) }, path)
			send(r, p, at)
			val candidates = api.snapshot(at).candidates
			assertEquals(2, candidates.size)
			assertTrue(candidates.none { it.sixDofSelectable })
			api.saveAndApply(api.editSources(TrackerPosition.HIP, ref(p), null, false, 0, at))
			api.saveAndApply(api.editSources(TrackerPosition.CHEST, TrackerReference.slime(slime.name), null, false, api.snapshot(at).generation, at))
			assertTrue(c.assignments.snapshot().targets.values.all { it.sourceSelection!!.sixDofSource == null })
			assertFailsWith<IllegalArgumentException> { api.editSources(TrackerPosition.HIP, ref(p), ref(p), true, api.snapshot(at).generation, at) }
		}
	}

	@Test fun sameNamedPublishersRemainDistinctAndManualReferencesSurviveReconnect(@TempDir dir: Path) {
		var now = 1_000_000_000L
		val p = fixture()
		val other = p.copy(publisher_id = "another-publisher")
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { now }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p, now)
			send(r, other, now)
			assertEquals(2, api.snapshot(now).candidates.map { it.reference }.distinct().size)
			api.saveAndApply(api.editSources(TrackerPosition.HIP, ref(p), ref(other), true, 0, now))
			val saved = c.assignments.snapshot()
			now += 600_000_000
			val restarted = p.copy(session_id = "33333333-3333-4333-8333-333333333333", sequence = 0)
			send(r, state(restarted), now)
			send(r, restarted, now)
			assertEquals(saved, c.assignments.snapshot())
			assertEquals(saved.targets, MonakaConfiguration.load(path).assignments.snapshot().targets)
			send(r, p.copy(sequence = p.sequence + 99), now)
			assertEquals(1, r.inbox.diagnostics()["RetiredSession"])
			assertEquals(saved, c.assignments.snapshot())
		}
	}

	@Test fun conflictingDeclarationsFailClosedWithoutRewritingAssignments(@TempDir dir: Path) {
		val p = fixture()
		val at = 1_000_000_000L
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { at }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, state(p), at)
			send(r, q(p, p.tracker_id), at)
			val row = api.snapshot(at).candidates.single()
			assertTrue(row.conflict)
			assertNull(row.capabilities)
			assertFalse(row.qSelectable)
			assertFalse(row.qUsableNow)
			assertEquals(1, r.inbox.diagnostics()["CapabilityConflict"])
			assertFailsWith<IllegalArgumentException> { api.editSources(TrackerPosition.HIP, ref(p), null, true, 0, at) }
		}
	}

	@Test fun staleGenerationExternalWriterAndHeldLockPreserveFileAndLiveState(@TempDir dir: Path) {
		val p = fixture()
		val at = 1_000_000_000L
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { at }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p, at)
			val edit = api.editSources(TrackerPosition.HIP, ref(p), ref(p), false, 0, at)
			val before = Files.readAllBytes(path)
			FileChannel.open(path.resolveSibling(path.fileName.toString() + ".input-write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
				ch.lock().use { assertFails { api.saveAndApply(edit) } }
			}
			assertContentEquals(before, Files.readAllBytes(path))
			assertTrue(c.assignments.snapshot().targets.isEmpty())
			api.saveAndApply(edit)
			val saved = c.assignments.snapshot()
			assertFailsWith<IllegalArgumentException> { api.saveAndApply(edit) }
			assertEquals(saved, c.assignments.snapshot())
			val clearing = api.editSources(TrackerPosition.HIP, ref(p), null, true, saved.generation, at)
			Files.writeString(path, Files.readString(path) + "\n")
			val external = Files.readAllBytes(path)
			assertFailsWith<IllegalArgumentException> { api.saveAndApply(clearing) }
			assertContentEquals(external, Files.readAllBytes(path))
			assertEquals(saved, c.assignments.snapshot())
		}
	}

	@Test fun unknownExtensionsSurviveValidatedSaveAndOfflineChoicesReload(@TempDir dir: Path) {
		val p = fixture()
		val at = 1_000_000_000L
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		val mapper = ObjectMapper()
		val doc = mapper.readTree(path.toFile()) as com.fasterxml.jackson.databind.node.ObjectNode
		doc.putObject("future-extension").put("proof", 42)
		Files.writeString(path, doc.toString())
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { at }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p, at)
			api.saveAndApply(api.editSources(TrackerPosition.HIP, ref(p), ref(p), false, 0, at))
			assertEquals(42, mapper.readTree(path.toFile())["future-extension"]["proof"].asInt())
		}
		val restored = MonakaConfiguration.load(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, restored.assignments, clock = { at }).use { r ->
			val api = MonakaInputConfigurationApi(restored, r.mtp, { emptyList() }, path)
			assertTrue(api.snapshot(at).candidates.isEmpty())
			api.saveAndApply(api.editSources(TrackerPosition.HIP, ref(p), null, true, api.snapshot(at).generation, at))
			assertNull(restored.assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!.sixDofSource)
		}
	}

	@Test fun qOnlyChoiceMasksPositionAtSelectionBoundaryWithoutEditingSample(@TempDir dir: Path) {
		val p = fixture()
		val at = 1_000_000_000L
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { at }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p, at)
			api.saveAndApply(api.editSources(TrackerPosition.HIP, ref(p), null, true, 0, at))
			val result = r.tick().getValue(TrackerPosition.HIP)
			assertNull(result.position)
			assertNotNull(result.rotation)
			assertEquals(p, r.mtp.samples().values.single().pose)
		}
	}

	@Test fun rpcQueuesOnlyBoundedLocalRequestsAndDisabledNeverStartsRuntime() {
		val tasks = mutableListOf<Runnable>()
		var calls = 0
		val replies = mutableListOf<String>()
		val dispatcher = MonakaInputRpcDispatcher({ tasks += it }, {
			{
				calls++
				"reply"
			}
		})
		val raw = """{"type":"monaka_inputs_v1","id":"1","action":"snapshot"}"""
		assertFalse(dispatcher.receive("{}", true, { true }, replies::add))
		dispatcher.receive(raw, false, { true }, replies::add)
		assertTrue(tasks.isEmpty())
		assertEquals(0, calls)
		repeat(17) { dispatcher.receive(raw, true, { true }, replies::add) }
		assertEquals(16, tasks.size)
		assertEquals(0, calls)
		tasks.forEach(Runnable::run)
		assertEquals(16, calls)
		val disabled = MonakaInputRpcDispatcher({ it.run() }, { null })
		disabled.receive(raw, true, { true }, replies::add)
		assertTrue(replies.last().contains("disabled"))
		dispatcher.receive(raw, true, { false }, replies::add)
		tasks.last().run()
		assertEquals(16, calls)
	}
}
