package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.Envelope
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.Validity
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Independent baseline: exercise production boundaries, never invoke another test suite. */
class ParallelRegressionTests {
	private val hip = TrackerPosition.HIP
	private fun fixture() = assertIs<MtpPose>(assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").toFile().readBytes())).value)
	private fun ref(p: MtpPose) = TrackerReference.mtp(LogicalTracker(p.source_id, p.tracker_id, p.publisher_id))
	private class Clock(var now: Long = 1_000_000_000) {
		fun advance() {
			now += 10_000_000
		}
	}
	private fun send(r: MonakaRuntime, p: Envelope) {
		assertTrue(r.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(p)).value, r.clock()))
	}
	private fun pose(p: MtpPose, seq: Long, modality: String) = p.copy(
		sequence = seq,
		timestamp_ns = p.timestamp_ns + seq * 10_000_000, sent_at_ns = p.sent_at_ns + seq * 10_000_000,
		modality = modality, position = p.position.takeIf { modality == "full" },
		orientation = p.orientation.takeIf { modality != "none" },
		validity = Validity(modality == "full", modality != "none"),
		confidence = Confidence(if (modality == "full") 1.0 else 0.0, if (modality == "none") 0.0 else 1.0),
		tracking_state = when (modality) {
			"full" -> "tracked"
			"rotation_only" -> "degraded"
			else -> "lost"
		},
	)
	private fun imu(name: String = "parallel-imu") = Tracker(
		null, 819, name, trackerPosition = hip,
		hasRotation = true, allowFiltering = false, allowReset = false, allowMounting = false,
		trackRotDirection = false,
	).also {
		it.status = TrackerStatus.OK
		it.setRotation(Quaternion.IDENTITY)
	}

	@Test fun capabilityChoicesFailClosedAndValidityNeverRewritesSavedSelection(@TempDir dir: Path) {
		val p = fixture()
		val q = pose(p, 0, "rotation_only").copy(tracker_id = "q-only", capabilities = listOf("orientation"))
		val slime = imu()
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		val clock = Clock()
		MonakaRuntime({ listOf(slime) }, p.coordinate_space, c.assignments, clock = { clock.now }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { listOf(slime) }, path)
			send(r, p)
			send(r, q)
			r.tick()
			val catalog = api.snapshot(clock.now).candidates.associateBy { it.reference }
			assertTrue(catalog.getValue(ref(p)).sixDofSelectable)
			for (reference in listOf(ref(q), TrackerReference.slime(slime.name))) {
				assertTrue(catalog.getValue(reference).qSelectable)
				assertFalse(catalog.getValue(reference).sixDofSelectable)
				assertFailsWith<IllegalArgumentException> { api.editSources(hip, reference, reference, true, 0, clock.now) }
			}
			assertFailsWith<IllegalArgumentException> { api.editSources(hip, TrackerReference.mtp(LogicalTracker("unknown", "unknown", "unknown")), null, true, 0, clock.now) }
			api.saveAndApply(api.editSources(hip, ref(p), ref(p), false, 0, clock.now))
			val saved = c.assignments.snapshot()
			val bytes = Files.readAllBytes(path)
			for ((index, modality) in listOf("rotation_only", "none", "full").withIndex()) {
				clock.advance()
				send(r, pose(p, index + 1L, modality))
				r.tick()
				val row = api.snapshot(clock.now).candidates.single { it.reference == ref(p) }
				assertTrue(row.sixDofSelectable)
				assertEquals(p.capabilities.toSet(), row.capabilities!!.components)
				assertEquals(modality == "full", row.sixDofUsableNow)
				assertEquals(modality != "none", row.qUsableNow)
				assertEquals(saved, c.assignments.snapshot())
				assertContentEquals(bytes, Files.readAllBytes(path))
			}
		}
	}

	@Test fun publisherIdentitySurvivesReconnectAndCannotOverwriteSibling(@TempDir dir: Path) {
		val p = fixture()
		val sibling = p.copy(publisher_id = "parallel-second-publisher", position = listOf(9.0, 2.0, 3.0))
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		val clock = Clock()
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { clock.now }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p)
			send(r, sibling)
			r.tick()
			assertEquals(2, api.snapshot(clock.now).candidates.map { it.reference }.toSet().size)
			api.saveAndApply(api.editSources(hip, ref(p), ref(sibling), true, 0, clock.now))
			val saved = c.assignments.snapshot()
			assertEquals(9f, r.tick().getValue(hip).position!!.value.x)
			clock.now += 600_000_000
			val reconnect = p.copy(session_id = "33333333-3333-4333-8333-333333333333", sequence = 0)
			send(r, reconnect)
			r.tick()
			send(r, p.copy(sequence = 99))
			r.tick()
			assertEquals(reconnect.session_id, r.mtp.samples().getValue(ref(p).mtp!!).pose.session_id)
			assertEquals(sibling, r.mtp.samples().getValue(ref(sibling).mtp!!).pose)
			assertEquals(1, r.inbox.diagnostics()["RetiredSession"])
			assertEquals(saved, c.assignments.snapshot())
			assertEquals(saved.targets, MonakaConfiguration.load(path).assignments.snapshot().targets)
		}
	}

	@ParameterizedTest
	@EnumSource(OutputMode::class)
	fun allModesKeepSolverAndOutputIdentityAcrossMainLoss(mode: OutputMode) {
		val p = fixture()
		val clock = Clock()
		val head = TestTrackerSet().head.also { it.position = Vector3(0f, 1.7f, 0f) }
		val imu = imu()
		val assignments = TrackerBodyAssignments().also { it.configure(hip, ref(p), TrackerReference.slime(imu.name), mode) }
		val hpm = HumanPoseManager(listOf(head, imu))
		hpm.setLegTweaksEnabled(false)
		hpm.update()
		val computed = hpm.skeleton.computedHipTracker
		var allocations = 0
		DirectConstraintOutput(assignments.snapshot(), p.coordinate_space) { 900 + allocations++ }.use { output ->
			val visible = output.trackers[hip]
			val identity = visible?.let { Triple(it.id, it.name, it.trackerPosition) }
			val controller = OutputContinuityController(hip, assignments.snapshot().targets.getValue(hip).continuity)
			val reader = BackgroundIkPoseReader(hpm.skeleton, BackgroundIkAlignment.confirmedSameSpace(p.coordinate_space))
			MonakaRuntime({ listOfNotNull(head, imu, visible) }, p.coordinate_space, assignments, clock = { clock.now }).use { r ->
				MonakaSolverComposition(hpm.skeleton, null, { error("Unexpected capture") }, { error("Unexpected body capture") }).use { solver ->
					assertEquals(ProductionPositionCorrectionGate.NORMAL_RUNTIME, solver.positionCorrectionGate)
					for ((seq, modality) in listOf("full", "rotation_only", "none", "full").withIndex()) {
						clock.advance()
						send(r, pose(p, seq.toLong(), modality))
						val tick = r.tickSnapshot()
						val resolved = r.resolvedTrackingPoses(tick).getValue(hip)
						assertIs<MonakaSolverCommitResult.GenericCommitted>(solver.commitGeneric(tick, tick.constraints, r.mtp.historyGeneration))
						assertEquals(
							MonakaSolverCommitRejection.DUPLICATE_TICK,
							assertIs<MonakaSolverCommitResult.Rejected>(solver.commitGeneric(tick, tick.constraints)).reason,
						)
						hpm.update()
						assertSame(computed, hpm.skeleton.computedHipTracker)
						if (modality == "full") {
							assertEquals(ref(p).observationId, resolved.position!!.sourceId)
							assertEquals(ref(p).observationId, resolved.rotation!!.sourceId)
							assertEquals(resolved.position!!.value, hpm.skeleton.hipTracker!!.position)
						} else {
							assertNull(resolved.position)
							assertEquals("slime:${imu.name}", resolved.rotation!!.sourceId)
						}
						val composed = controller.update(resolved, reader.read(hip, p.coordinate_space, clock.now), clock.now)
						output.applyPoses(mapOf(hip to composed))
						if (mode == OutputMode.IK) {
							assertTrue(output.trackers.isEmpty())
						} else {
							assertSame(visible, output.trackers.getValue(hip))
							assertEquals(identity, Triple(visible!!.id, visible.name, visible.trackerPosition))
							assertFalse(FeedbackExclusion.accepts(visible))
						}
						assertTrue(r.pipeline.observations().none { FeedbackExclusion.isOutput(it.sourceId.removePrefix("slime:")) })
					}
				}
			}
			assertEquals(if (mode == OutputMode.IK) 0 else 1, allocations)
		}
	}

	@Test fun hybridDwellRequiresNewPhysicalFullAndBlendsBeforeSettling() {
		val p = fixture()
		val space = p.coordinate_space
		val c = OutputContinuityController(hip, ContinuityPolicy.BACKGROUND_IK)
		fun main(at: Long, full: Boolean) = ResolvedTrackingPose(
			hip,
			space,
			if (full) ResolvedComponent(Vector3(3f, 2f, 1f), "main", ObservationQuality.TRACKED, at) else null,
			ResolvedComponent(Quaternion.IDENTITY, if (full) "main" else "imu", ObservationQuality.TRACKED, at),
			MainSampleState(if (full) TrackingModality.FULL else TrackingModality.ROTATION_ONLY, full, true),
		)
		fun step(now: Long, sampleAt: Long = now, full: Boolean = true): OutputPose = c.update(
			main(sampleAt, full),
			BackgroundIkResult(
				OutputPose(
					hip,
					space,
					ResolvedComponent(Vector3(1f, 2f, 1f), "monaka-solver:HIP", ObservationQuality.TRACKED, now),
					ResolvedComponent(Quaternion.IDENTITY, "monaka-solver:HIP", ObservationQuality.TRACKED, now),
					OutputPositionSource.BACKGROUND_IK,
				),
				"current",
			),
			now,
		)
		assertEquals(3f, step(0).position!!.value.x)
		assertEquals(3f, step(1, full = false).position!!.value.x) // continuous loss edge
		assertEquals(1f, step(200_000_000, full = false).position!!.value.x)
		step(210_000_000)
		step(500_000_000, sampleAt = 210_000_000)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		step(510_000_000)
		assertEquals(ContinuityState.REACQUIRING, c.state)
		val midway = step(560_000_000).position!!.value.x
		assertTrue(midway > 1f && midway < 3f)
		assertEquals(3f, step(2_000_000_000).position!!.value.x)
		assertEquals(ContinuityState.MAIN_DIRECT, c.state)
	}

	@Test fun unauthenticatedOrStationaryImuCannotAdvanceLearningAndEpochChangeRevokesIt() {
		val space = fixture().coordinate_space
		val c = HipRotationCorrection(
			RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, space, true),
			RotationCorrectionTuning(2_000_000, 1_000_000, .0001, .0001, 3.2, .1, 1000.0, 1, 100_000_000, 100_000_000),
		)
		fun sample(id: String, seq: Long, at: Long, full: Boolean) = PoseObservation(
			id,
			hip,
			at,
			position = if (full) Vector3(1f, 2f, 3f) else null,
			rotation = if (full) Quaternion.rotationAroundYAxis(.5f) else Quaternion.IDENTITY,
			modality = if (full) TrackingModality.FULL else TrackingModality.ROTATION_ONLY,
			provenance = ObservationSampleProvenance(seq, at, "session", "mount", 1, space),
			correctionRotation = if (full) Quaternion.rotationAroundYAxis(.5f) else Quaternion.IDENTITY,
		)
		val main = sample("mtp:main", 1, 1_000_000, true)
		val imu = sample("slime:imu", 1, 1_000_000, false)
		c.update(main, imu.copy(provenance = null), main.sourceId, 1_000_000, 1, space)
		assertFalse(c.ready)
		assertNull(c.lastLearnedAtNanos)
		c.update(main, imu, main.sourceId, 1_000_000, 1, space)
		for (seq in 2L..4L) {
			c.update(
				sample(main.sourceId, seq, seq * 1_000_000, true),
				imu.copy(provenance = imu.provenance!!.copy(sequence = seq)),
				main.sourceId,
				seq * 1_000_000,
				1,
				space,
			)
		}
		assertFalse(c.ready)
		assertNull(c.lastLearnedAtNanos)
		assertEquals(Quaternion.IDENTITY, c.correction)
		c.update(sample(main.sourceId, 5, 5_000_000, true), sample(imu.sourceId, 5, 5_000_000, false), main.sourceId, 5_000_000, 1, space)
		c.update(sample(main.sourceId, 6, 8_000_000, true), sample(imu.sourceId, 6, 8_000_000, false), main.sourceId, 8_000_000, 1, space)
		assertTrue(c.ready)
		val reconnected = sample(imu.sourceId, 7, 9_000_000, false).let { it.copy(provenance = it.provenance!!.copy(sourceEpoch = "new-session")) }
		assertNull(c.update(null, reconnected, imu.sourceId, 9_000_000, 1, space))
		assertFalse(c.ready)
	}

	@Test fun writerConflictLockAndReloadPreserveSourcesAndUnknownFields(@TempDir dir: Path) {
		val p = fixture()
		val c = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		c.save(path)
		val mapper = ObjectMapper()
		val document = mapper.readTree(path.toFile()) as ObjectNode
		document.putObject("future-extension").put("sentinel", 73)
		Files.writeString(path, document.toString())
		MonakaRuntime({ emptyList() }, p.coordinate_space, c.assignments, clock = { 1_000_000_000 }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p)
			r.tick()
			val edit = api.editSources(hip, ref(p), ref(p), false, 0, r.clock())
			val before = Files.readAllBytes(path)
			FileChannel.open(path.resolveSibling("inputs.json.input-write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
				channel.lock().use { assertFails { api.saveAndApply(edit) } }
			}
			assertContentEquals(before, Files.readAllBytes(path))
			assertTrue(c.assignments.snapshot().targets.isEmpty())
			api.saveAndApply(edit)
			val saved = c.assignments.snapshot()
			assertFailsWith<IllegalArgumentException> { api.saveAndApply(edit) }
			assertEquals(saved, c.assignments.snapshot())
			assertEquals(73, mapper.readTree(path.toFile())["future-extension"]["sentinel"].asInt())
			val clear = api.editSources(hip, ref(p), null, true, saved.generation, r.clock())
			Files.writeString(path, Files.readString(path) + "\n")
			val external = Files.readAllBytes(path)
			assertFailsWith<IllegalArgumentException> { api.saveAndApply(clear) }
			assertContentEquals(external, Files.readAllBytes(path))
			assertEquals(saved, c.assignments.snapshot())
		}
		val loaded = MonakaConfiguration.load(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, loaded.assignments, clock = { 2_000_000_000 }).use { fresh ->
			assertNull(fresh.tick()[hip]?.position)
			assertEquals(ref(p), loaded.assignments.snapshot().targets.getValue(hip).sourceSelection!!.sixDofSource)
			send(fresh, p)
			assertNotNull(fresh.tick()[hip]?.position)
		}
	}

	@Test fun directSourceChoicePersistsWithRestartWhileActiveAssignmentIsPreserved(@TempDir dir: Path) {
		val p = fixture()
		val a = TrackerBodyAssignments().also { it.configure(hip, ref(p), outputMode = OutputMode.DIRECT) }
		val c = MonakaConfiguration(p.coordinate_space, a)
		val path = dir.resolve("direct.json")
		c.save(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, a, clock = { 1_000_000_000 }).use { r ->
			val api = MonakaInputConfigurationApi(c, r.mtp, { emptyList() }, path)
			send(r, p)
			r.tick()
			val before = Files.readAllBytes(path)
			val saved = a.snapshot()
			val clear = api.editSources(hip, null, null, true, saved.generation, r.clock())
			assertEquals(saved, api.saveAndApply(clear))
			assertEquals(saved, a.snapshot(), "Active Direct topology is unchanged until restart")
			assertFalse(before.contentEquals(Files.readAllBytes(path)))
			assertTrue(MonakaConfiguration.load(path).assignments.snapshot().targets.isEmpty(), "Saved clear restores after restart")
			val receipt = ObjectMapper().readTree(api.handleJson("""{"type":"monaka_inputs_v1","id":"restart-readback","action":"snapshot"}""", r.clock()))
			assertTrue(receipt["restartRequired"].asBoolean())
			assertFailsWith<IllegalArgumentException> { api.saveAndApply(clear) }
		}
	}

	@Test fun independentImuPhysicalAgeControlsFallbackWithoutSplittingFullMainPair() {
		val a = TrackerBodyAssignments().also { it.configure(hip, TrackerReference("mtp:main"), TrackerReference("slime:imu")) }
		val freshness = AssignedImuSampleFreshness(a, 10)
		val pipeline = ConstraintPipeline(resolver = ConstraintResolver { a.snapshot().targets }, eligibility = freshness::apply)
		val main = PoseObservation("mtp:main", hip, 10, position = Vector3(1f, 2f, 3f), rotation = Quaternion.rotationAroundYAxis(.5f), modality = TrackingModality.FULL)
		val imu = PoseObservation(
			"slime:imu",
			hip,
			10,
			rotation = Quaternion.IDENTITY,
			provenance = ObservationSampleProvenance(1, 10, "imu-session", "mount"),
			correctionRotation = Quaternion.IDENTITY,
		)
		pipeline.ingest(main)
		pipeline.ingest(imu)
		assertEquals("mtp:main", pipeline.resolve(hip, 10).position!!.sourceId)
		assertEquals("mtp:main", pipeline.resolve(hip, 10).rotation!!.sourceId)
		pipeline.ingest(main.copy(observedAtNanos = 20, position = null, positionQuality = ObservationQuality.UNAVAILABLE, modality = TrackingModality.ROTATION_ONLY))
		assertNull(pipeline.resolve(hip, 20).position)
		assertEquals("slime:imu", pipeline.resolve(hip, 20).rotation!!.sourceId)
		pipeline.ingest(imu.copy(observedAtNanos = 21)) // polling cannot refresh physical sample time
		assertEquals("mtp:main", pipeline.resolve(hip, 21).rotation!!.sourceId)
		pipeline.ingest(
			main.copy(
				observedAtNanos = 22,
				position = null,
				rotation = null,
				positionQuality = ObservationQuality.UNAVAILABLE,
				rotationQuality = ObservationQuality.UNAVAILABLE,
				modality = TrackingModality.NONE,
			),
		)
		assertNull(pipeline.resolve(hip, 22).rotation)
		pipeline.ingest(imu.copy(observedAtNanos = 23, provenance = imu.provenance!!.copy(sequence = 2, sampleAtNanos = 23)))
		assertEquals("slime:imu", pipeline.resolve(hip, 23).rotation!!.sourceId)
	}

	@Test fun pauseCloseAndReconstructionNeverReviveQueuedPose() {
		val p = fixture()
		val clock = Clock()
		val a = TrackerBodyAssignments().also { it.configure(hip, ref(p)) }
		val r = MonakaRuntime({ emptyList() }, p.coordinate_space, a, clock = { clock.now })
		try {
			send(r, p)
			assertNotNull(r.tick()[hip]?.position)
			r.tick(true)
			send(r, pose(p, 1, "full"))
			r.tick(true)
			assertNull(r.tick(false)[hip]?.position)
			send(r, pose(p, 1, "full"))
			assertNull(r.tick()[hip]?.position)
			clock.advance()
			send(r, pose(p, 2, "full"))
			assertNotNull(r.tick()[hip]?.position)
		} finally {
			r.close()
		}
		r.close()
		assertEquals(0, r.runner.size)
		assertEquals(0, r.pipeline.observationCount)
		assertTrue(r.mtp.samples().isEmpty())
		assertFailsWith<IllegalStateException> { r.tick() }
		MonakaRuntime({ emptyList() }, p.coordinate_space, a, clock = { clock.now }).use { fresh ->
			assertNull(fresh.tick()[hip]?.position)
			send(fresh, pose(p, 3, "full"))
			assertNotNull(fresh.tick()[hip]?.position)
		}
	}
}
