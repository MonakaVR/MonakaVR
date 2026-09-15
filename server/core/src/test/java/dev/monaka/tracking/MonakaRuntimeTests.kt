package dev.monaka.tracking

import dev.monaka.protocol.v1.*
import dev.monaka.tracking.mtp.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.unit.TestTrackerSet
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class MonakaRuntimeTests {
	private fun fixture(): MtpPose = (MonakaCodec.decodeEnvelope(
		File(System.getProperty("monaka.fixtures"), "valid/mtp-pose.json").readBytes(),
	) as DecodeResult.Success).value as MtpPose
	private val key get() = LogicalTracker("backend-A", "tracker-A")
	private fun bytes(value: Envelope) = (MonakaCodec.encodeEnvelope(value) as EncodeResult.Success).value
	private fun state(p: MtpPose, sequence: Long = 0, presence: String = "present") = MtpTrackerState(
		p.version, p.source_id, p.session_id, p.clock_id, sequence, p.timestamp_ns, p.sent_at_ns,
		p.timestamp_kind, p.tracker_id, presence, p.tracking_state, p.coordinate_space, p.capabilities, null, p.mapping_revision,
	)
	private class Clock(var now: Long = 1_000_000_000) { fun read() = now }
	private fun runtime(clock: Clock, trackers: () -> Iterable<Tracker> = { emptyList() }) = MonakaRuntime(
		trackers, fixture().coordinate_space, TrackerBodyAssignments(mapOf(key to TrackerPosition.HIP)), clock = clock::read,
	)
	private fun submit(runtime: MonakaRuntime, value: Envelope, receivedAt: Long = runtime.clock()) {
		assertTrue(runtime.inbox.receive(bytes(value), receivedAt))
	}

	@Test fun fixedC1FixtureUsesXyzwAndPreservesBinary64AgeAndMetadata() {
		val p = fixture()
		assertEquals(9007199254740993L, p.timestamp_ns)
		val clock = Clock()
		runtime(clock).use { r ->
			submit(r, p)
			val result = r.tick().getValue(TrackerPosition.HIP)
			assertEquals(Vector3(1f, 2f, 3f), result.position!!.value)
			assertEquals(Quaternion.IDENTITY, result.rotation!!.value)
			assertEquals(clock.now - 100, result.position!!.observedAtNanos)
			assertEquals(p.confidence, r.mtp.samples().getValue(key).pose.confidence)
			assertEquals(p.input, r.mtp.samples().getValue(key).pose.input)
		}
	}

	@Test fun duplicateMetadataAndPollCannotRefreshFirstAdmissionAge() {
		val clock = Clock()
		runtime(clock).use { r ->
			val p = fixture(); submit(r, p); r.tick()
			val first = r.mtp.samples().getValue(key).sampleTime
			repeat(8) {
				clock.now += 100_000_000
				submit(r, p.copy(sent_at_ns = p.sent_at_ns + it * 100_000_000L))
				submit(r, state(p, it.toLong()))
				r.tick()
				assertEquals(first, r.mtp.samples().getValue(key).sampleTime)
			}
			assertNull(r.pipeline.resolve(TrackerPosition.HIP, clock.now).position)
			assertEquals(ObservationQuality.STALE, r.pipeline.observations(clock.now).single().positionQuality)
			assertEquals(8, r.inbox.diagnostics().getValue("DuplicateOrOldSequence"))
		}
	}

	@Test fun freshnessBoundaryBeforeEpochAndNewSequenceWithOlderSample() {
		val clock = Clock()
		runtime(clock).use { r ->
			val p = fixture(); submit(r, p); r.tick()
			clock.now += 500_000_000 - 100
			assertNotNull(r.tick().getValue(TrackerPosition.HIP).position)
			clock.now++
			assertNull(r.tick().getValue(TrackerPosition.HIP).position)
			submit(r, p.copy(sequence = 1, sent_at_ns = p.timestamp_ns + 900_000_000))
			r.tick()
			assertEquals(clock.now - 900_000_000, r.mtp.samples().getValue(key).sampleTime)
			submit(r, p.copy(sequence = 2, sent_at_ns = p.timestamp_ns + 2_000_000_000))
			assertNull(r.tick()[TrackerPosition.HIP]?.position)
			assertEquals(1, r.inbox.diagnostics().getValue("BeforeLocalEpoch"))
		}
	}

	@Test fun independentStreamsSessionsAndIncrementalRemoval() {
		val clock = Clock()
		runtime(clock).use { r ->
			val p = fixture().copy(sequence = 20)
			val other = p.copy(source_id = "bridge-B")
			val otherKey = LogicalTracker(other.source_id, other.tracker_id)
			r.assignments.assign(otherKey, TrackerPosition.LEFT_FOOT)
			submit(r, p); submit(r, other); r.tick()
			submit(r, state(p, 1000)); r.tick()
			submit(r, p.copy(sequence = 21)); r.tick()
			assertEquals(21, r.mtp.samples().getValue(key).pose.sequence)
			submit(r, p.copy(sequence = 19, position = listOf(99.0, 0.0, 0.0))); r.tick()
			assertEquals(21, r.mtp.samples().getValue(key).pose.sequence)
			val replacement = p.copy(session_id = "33333333-3333-3333-3333-333333333333", sequence = 0)
			submit(r, replacement); r.tick()
			submit(r, p.copy(sequence = 22)); r.tick()
			assertEquals(replacement.session_id, r.mtp.samples().getValue(key).pose.session_id)
			assertNotNull(r.mtp.samples()[otherKey])
			submit(r, state(replacement, presence = "absent")); r.tick()
			assertNull(r.pipeline.resolve(TrackerPosition.HIP, clock.now).position)
			assertNotNull(r.pipeline.resolve(TrackerPosition.LEFT_FOOT, clock.now).position)
			submit(r, replacement.copy(sequence = 1)); r.tick()
			assertNull(r.mtp.samples()[key]) // Same sample may not undo explicit absence.
		}
	}

	@Test fun spaceMappingAndClockMismatchInvalidateWithoutVendorCorrection() {
		val clock = Clock()
		runtime(clock).use { r ->
			val p = fixture(); submit(r, p); r.tick()
			submit(r, p.copy(sequence = 1, coordinate_space = p.coordinate_space.copy(revision = 1)))
			assertNull(r.tick()[TrackerPosition.HIP]?.position)
			submit(r, p.copy(sequence = 2)); assertNotNull(r.tick()[TrackerPosition.HIP]?.position)
			submit(r, state(p.copy(mapping_revision = 1))); assertNull(r.tick()[TrackerPosition.HIP]?.position)
			submit(r, p.copy(sequence = 3)); assertNull(r.tick()[TrackerPosition.HIP]?.position)
			submit(r, p.copy(sequence = 4, mapping_revision = 1)); assertNotNull(r.tick()[TrackerPosition.HIP]?.position)
			submit(r, p.copy(sequence = 5, clock_id = "44444444-4444-4444-4444-444444444444"))
			assertNull(r.tick()[TrackerPosition.HIP]?.position)
		}
	}

	@Test fun componentFallbackCombinesSimultaneousSourcesAndRemovesLostNumericFields() {
		val clock = Clock()
		runtime(clock).use { r ->
			val p = fixture()
			val other = p.copy(source_id = "vive-like", position = listOf(4.0, 5.0, 6.0))
			r.assignments.assign(LogicalTracker(other.source_id, other.tracker_id), TrackerPosition.HIP)
			submit(r, other)
			clock.now++
			submit(r, p.copy(validity = Validity(false, true), confidence = Confidence(0.0, 0.5), tracking_state = "degraded"))
			// Same state rank gives source priority/age selection; no probability fusion.
			val otherDegraded = other.copy(sequence = 1, validity = Validity(true, false), confidence = Confidence(1.0, 0.0), tracking_state = "degraded")
			submit(r, otherDegraded)
			val result = r.tick().getValue(TrackerPosition.HIP)
			assertEquals(Vector3(4f, 5f, 6f), result.position!!.value)
			assertEquals(key.observationId, result.rotation!!.sourceId)
			assertNotEquals(result.position!!.sourceId, result.rotation!!.sourceId)
			val adapted = MtpPoseAdapter().adapt(p.copy(confidence = Confidence(0.0, 0.0)), TrackerPosition.HIP, 0)
			assertNull(adapted.position); assertNull(adapted.rotation)
			assertEquals(ObservationQuality.LOST, adapted.positionQuality)
			clock.now += 500_000_001
			assertNull(r.tick().getValue(TrackerPosition.HIP).position)
			assertNull(r.tick().getValue(TrackerPosition.HIP).rotation)
		}
	}

	@Test fun assignmentMigrationIsExplicitAndIdentityIsCollisionFree() {
		assertNotEquals(LogicalTracker("a:b", "c").observationId, LogicalTracker("a", "b:c").observationId)
		assertNotEquals(LogicalTracker("A", "c").observationId, LogicalTracker("a", "c").observationId)
		val assignments = TrackerBodyAssignments()
		assertFailsWith<IllegalArgumentException> { assignments.migrateLegacy(mapOf("serial" to TrackerPosition.HIP), emptyMap()) }
		assertTrue(assignments.snapshot().entries.isEmpty())
		assignments.migrateLegacy(mapOf("serial" to TrackerPosition.HIP), mapOf("serial" to key))
		assertEquals(TrackerPosition.HIP, assignments.snapshot().entries[key])
	}

	@Test fun unassignedIsDiagnosticAndReassignmentKeepsSampleAge() {
		val clock = Clock()
		runtime(clock).use { r ->
			r.assignments.unassign(key); submit(r, fixture()); r.tick()
			assertEquals(0, r.pipeline.observationCount); assertEquals(1, r.mtp.samples().size)
			clock.now += 800_000_000
			r.assignments.assign(key, TrackerPosition.LEFT_FOOT)
			assertNull(r.tick()[TrackerPosition.LEFT_FOOT]?.position)
			assertEquals(999_999_900, r.pipeline.observations().single().observedAtNanos)
			r.close(); assertEquals(0, r.runner.size); assertTrue(r.mtp.samples().isEmpty())
		}
	}

	@Test fun floatOverflowIsRejectedAndMalformedDatagramDoesNotKillAdjacentInput() {
		val clock = Clock()
		runtime(clock).use { r ->
			val p = fixture()
			assertFalse(r.inbox.receive("{".toByteArray(), clock.now))
			submit(r, p.copy(position = listOf(1e300, 0.0, 0.0)))
			assertNull(r.tick()[TrackerPosition.HIP]?.position)
			assertEquals(1, r.inbox.diagnostics().getValue("FloatOverflow"))
			submit(r, p.copy(sequence = 1)); assertNotNull(r.tick()[TrackerPosition.HIP]?.position)
		}
	}

	@Test fun failedBackendInvalidatesOnlyItsSources() {
		val clock = Clock(); val trackers = TestTrackerSet()
		runtime(clock) { listOf(trackers.hip) }.use { r ->
			submit(r, fixture()); r.tick()
			r.runner.replace(object : ObservationBackend {
				override val backendId = "mtp"; override val profileId = "mtp"
				override fun poll(observedAtNanos: Long): List<PoseObservation> = error("test failure")
			})
			val result = r.tick().getValue(TrackerPosition.HIP)
			assertNull(result.position); assertTrue(result.rotation!!.sourceId.startsWith("slime:"))
			assertEquals(1, r.inbox.diagnostics().getValue("BackendFailure:mtp"))
		}
	}

	@Test fun bothRouteExcludesDirectSolverAndPrivateButRetainsRealHmd() {
		val clock = Clock(); val trackers = TestTrackerSet()
		val outputs = listOf("monaka-direct:x", "monaka-solver:x", "human://WAIST", "monaka-private:HIP").mapIndexed { i, name ->
			Tracker(null, 100 + i, name, trackerPosition = TrackerPosition.HIP, hasPosition = true, hasRotation = true).also { it.status = TrackerStatus.OK }
		}
		runtime(clock) { outputs + trackers.head }.use { r ->
			submit(r, fixture())
			for (o in outputs) submit(r, fixture().copy(tracker_id = o.name))
			submit(r, fixture().copy(input = fixture().input.copy(device_id = "monaka-direct:physical-mirror")))
			r.tick()
			assertEquals(setOf("slime:0", key.observationId), r.pipeline.observations().map { it.sourceId }.toSet())
			assertEquals(5, r.inbox.diagnostics().getValue("FeedbackExcluded"))
		}
	}

	@Test fun effectiveConstraintMovesComputedHipThroughExistingIkAndDoesNotRebuildPerFrame() {
		val clock = Clock(); val trackers = TestTrackerSet()
		trackers.head.position = Vector3(0f, 1.7f, 0f)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip)); hpm.setLegTweaksEnabled(false)
		hpm.skeleton.ikSolver.enabled = false; hpm.update()
		val baseline = hpm.skeleton.computedHipTracker!!.position
		runtime(clock) { listOf(trackers.head, trackers.hip) }.use { r ->
			ConstraintIkWriteback(hpm.skeleton).use { writeback ->
				fun step() { writeback.apply(r.tick(), r.assignments.snapshot()); hpm.update() }
				val p = fixture().copy(position = listOf(baseline.x.toDouble(), baseline.y.toDouble(), baseline.z.toDouble()))
				submit(r, p); step()
				hpm.skeleton.ikSolver.resetOffsets(); hpm.skeleton.ikSolver.enabled = true; step()
				val initial = hpm.skeleton.computedHipTracker!!.position
				val builds = writeback.topologyRebuilds
				submit(r, p.copy(sequence = 1, position = listOf(baseline.x + 0.1, baseline.y.toDouble(), baseline.z.toDouble())))
				repeat(5) { step() }
				assertTrue(hpm.skeleton.computedHipTracker!!.position.x - initial.x > 0.005f)
				assertEquals(builds, writeback.topologyRebuilds)
				assertEquals(trackers.head.position, hpm.skeleton.headBone.getPosition())
				// Position loss: Slime rotation survives, stale MTP position must leave IK topology.
				submit(r, p.copy(sequence = 2, validity = Validity(false, true), confidence = Confidence(0.0, 0.5), tracking_state = "degraded"))
				step()
				assertEquals(ConstraintIkWriteback.ComponentMask(false, true), writeback.masks()[TrackerPosition.HIP])
				assertTrue(kotlin.math.abs(hpm.skeleton.computedHipTracker!!.position.x - baseline.x) < 0.001f)
				// Remove rotational Slime fallback and supply position only: FK may not see an identity quaternion.
				trackers.hip.status = TrackerStatus.DISCONNECTED
				submit(r, p.copy(sequence = 3, validity = Validity(true, false), confidence = Confidence(1.0, 0.0), tracking_state = "degraded"))
				step()
				assertEquals(ConstraintIkWriteback.ComponentMask(true, false), writeback.masks()[TrackerPosition.HIP])
				assertNull(hpm.skeleton.hipTracker)
				r.assignments.unassign(key); step(); assertTrue(writeback.masks().isEmpty())
			}
		}
	}

	@Test fun pauseResumeDiscardsPoseHistoryAndRequiresNewSequence() {
		val clock = Clock(); val trackers = TestTrackerSet()
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		runtime(clock) { listOf(trackers.head, trackers.hip) }.use { r ->
			ConstraintIkWriteback(hpm.skeleton).use { writeback ->
				fun step() { writeback.apply(r.tick(hpm.skeleton.getPauseTracking()), r.assignments.snapshot()); hpm.update() }
				val p = fixture(); submit(r, p); step()
				hpm.skeleton.setPauseTracking(true, "test"); step()
				val frozen = hpm.skeleton.computedHipTracker!!.position
				submit(r, p.copy(sequence = 1)); step()
				// The existing paused skeleton still recomputes bone matrices (Float roundoff).
				assertTrue((frozen - hpm.skeleton.computedHipTracker!!.position).len() < 0.000001f)
				hpm.skeleton.setPauseTracking(false, "test"); step()
				assertFalse(writeback.masks().getValue(TrackerPosition.HIP).position)
				submit(r, p.copy(sequence = 1)); step()
				assertFalse(writeback.masks().getValue(TrackerPosition.HIP).position)
				submit(r, p.copy(sequence = 2)); step()
				assertTrue(writeback.masks().getValue(TrackerPosition.HIP).position)
			}
		}
	}

	@Test fun pauseBacklogBeyondNormalDrainAdvancesReplayWatermarkBeforeResume() {
		val clock = Clock()
		val trackers = TestTrackerSet()
		trackers.head.position = Vector3(0f, 1.7f, 0f)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		hpm.setLegTweaksEnabled(false)
		runtime(clock) { listOf(trackers.head, trackers.hip) }.use { r ->
			ConstraintIkWriteback(hpm.skeleton).use { writeback ->
				fun step(paused: Boolean) {
					val constraints = r.tick(paused)
					writeback.apply(constraints, r.assignments.snapshot(), r.mtp.historyGeneration)
					hpm.update()
				}
				val initial = fixture().copy(position = listOf(0.0, 1.0, 0.0))
				submit(r, initial)
				step(false)
				hpm.skeleton.setPauseTracking(true, "pause backlog regression")
				step(true)
				val pausedComputedHip = hpm.skeleton.computedHipTracker!!.position

				val backlogLast = 600L
				assertTrue(backlogLast > MtpInbox.NORMAL_DRAIN_LIMIT)
				assertEquals(MtpInbox.DEFAULT_CAPACITY, r.mtp.resumeDrainBound)
				for (sequence in 1L..backlogLast) {
					submit(
						r,
						initial.copy(sequence = sequence, position = listOf(10.0 + sequence, 1.0, 0.0)),
					)
				}

				// A normal paused tick consumes only 256. The resume transition must
				// server-admit the remaining 344 before leaving suspended state.
				step(true)
				assertTrue((pausedComputedHip - hpm.skeleton.computedHipTracker!!.position).len() < 0.000001f)
				hpm.skeleton.setPauseTracking(false, "pause backlog regression")
				step(false)
				assertTrue(
					kotlin.math.abs(hpm.skeleton.computedHipTracker!!.position.x) < 5f,
					"Paused backlog position was written into the computed tracker on resume",
				)
				assertFalse(writeback.masks().getValue(TrackerPosition.HIP).position)
				assertNull(r.mtp.samples()[key])

				val duplicatesBefore = r.inbox.diagnostics()["DuplicateOrOldSequence"] ?: 0L
				submit(r, initial.copy(sequence = 500, position = listOf(500.0, 1.0, 0.0)))
				submit(r, initial.copy(sequence = backlogLast, position = listOf(600.0, 1.0, 0.0)))
				step(false)
				assertEquals(duplicatesBefore + 2, r.inbox.diagnostics().getValue("DuplicateOrOldSequence"))
				assertNull(r.mtp.samples()[key])
				assertFalse(writeback.masks().getValue(TrackerPosition.HIP).position)

				submit(r, initial.copy(sequence = backlogLast + 1, position = listOf(0.1, 1.0, 0.0)))
				step(false)
				assertEquals(backlogLast + 1, r.mtp.samples().getValue(key).pose.sequence)
				assertTrue(writeback.masks().getValue(TrackerPosition.HIP).position)
			}
		}
	}

	@Test fun headPositionAndRotationLossUseIndependentMasksWithoutRetainingOldAnchor() {
		val clock = Clock()
		val hpm = HumanPoseManager(emptyList())
		hpm.setLegTweaksEnabled(false)
		runtime(clock).use { r ->
			r.assignments.assign(key, TrackerPosition.HEAD)
			ConstraintIkWriteback(hpm.skeleton).use { w ->
				fun step() { val c = r.tick(); w.apply(c, r.assignments.snapshot(), r.mtp.historyGeneration); hpm.update() }
				val p = fixture().copy(position = listOf(0.2, 1.7, 0.1))
				submit(r, p); step()
				assertEquals(Vector3(0.2f, 1.7f, 0.1f), hpm.skeleton.headBone.getPosition())
				submit(r, p.copy(sequence = 1, validity = Validity(false, true), confidence = Confidence(0.0, 0.5), tracking_state = "degraded")); step()
				assertEquals(Vector3.NULL, hpm.skeleton.headBone.getPosition())
				submit(r, p.copy(sequence = 2, validity = Validity(true, false), confidence = Confidence(1.0, 0.0), tracking_state = "degraded")); step()
				assertEquals(Vector3(0.2f, 1.7f, 0.1f), hpm.skeleton.headBone.getPosition())
				assertEquals(Quaternion.IDENTITY, hpm.skeleton.headBone.getGlobalRotation())
				val topology = w.topologyRebuilds
				val nextSession = p.copy(sequence = 0, session_id = "55555555-5555-5555-5555-555555555555")
				submit(r, nextSession); step()
				assertTrue(w.topologyRebuilds > topology)
			}
		}
	}

	@Test fun allFixedFixturesRetainCodecCompatibility() {
		val folder = File(System.getProperty("monaka.fixtures"))
		for (file in File(folder, "valid").listFiles()!!.filter { it.extension == "json" }) {
			assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(file.readBytes()), file.name)
		}
		for (file in File(folder, "invalid").listFiles()!!.filter { it.extension == "json" }) {
			assertIs<DecodeResult.Failure>(MonakaCodec.decodeEnvelope(file.readBytes()), file.name)
		}
	}

	@Test fun lostMetadataDoesNotRepeatedlyRebuildSlimeFallbackTopology() {
		val clock = Clock(); val trackers = TestTrackerSet()
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		runtime(clock) { listOf(trackers.head, trackers.hip) }.use { r ->
			ConstraintIkWriteback(hpm.skeleton).use { w ->
				fun step() { val result = r.tick(); w.apply(result, r.assignments.snapshot(), r.mtp.historyGeneration) }
				val p = fixture(); submit(r, p); step()
				submit(r, state(p.copy(tracking_state = "lost"))); step()
				val builds = w.topologyRebuilds
				repeat(20) { submit(r, state(p.copy(tracking_state = "lost"), it + 1L)); step() }
				assertEquals(builds, w.topologyRebuilds)
				assertFalse(w.masks().getValue(TrackerPosition.HIP).position)
			}
		}
	}
}
