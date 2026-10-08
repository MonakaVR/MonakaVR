package dev.monaka.tracking

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.mtp.MtpObservationBackend
import dev.monaka.tracking.mtp.MtpInbox
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class MonakaResolvedTickSnapshotTests {
	private val hip = TrackerPosition.HIP
	private val foot = TrackerPosition.LEFT_FOOT
	private fun fixture() = (MonakaCodec.decodeEnvelope(
		File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes(),
	) as DecodeResult.Success).value as MtpPose
	private fun key(p: MtpPose) = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
	private fun submit(inbox: MtpInbox, p: MtpPose, now: Long = 1_000_000_000) {
		assertTrue(inbox.receive((MonakaCodec.encodeEnvelope(p) as EncodeResult.Success).value, now))
	}
	private fun tracker(id: Int) = Tracker(null, id, "tick-$id", trackerPosition = hip,
		hasPosition = true, hasRotation = true).apply {
		status = TrackerStatus.OK; position = Vector3(id.toFloat(), 1f, 0f); setRotation(Quaternion.IDENTITY)
	}
	private fun observation(id: String, target: TrackerPosition, now: Long = 100) =
		PoseObservation(id, target, now, position = Vector3(1f, 2f, 3f), rotation = Quaternion.IDENTITY)
	private fun relation(id: String) = MainTrackerAssignment(TrackerReference(id))

	@Test fun snapshotFieldsOneClockAndSameNowSequenceIncludingPausedAndLegacyTicks() {
		val p = fixture(); val assignments = TrackerBodyAssignments(mapOf(key(p) to hip))
		var reads = 0
		MonakaRuntime({ emptyList() }, p.coordinate_space, assignments, clock = { reads++; 1_000_000_000 }).use { r ->
			submit(r.inbox, p.copy(sequence = 987))
			val pinned = assignments.snapshot()
			val first = r.tickSnapshot()
			assertEquals(1, reads); assertEquals(0, first.tickSequence)
			assertEquals(1_000_000_000, first.nowNanos); assertEquals(first.nowNanos, first.resolvedAtNanos)
			assertEquals(first.nowNanos, r.lastTickNanos); assertFalse(first.paused)
			assertSame(pinned, first.assignment)
			assertEquals(key(p).observationId, first.constraints.getValue(hip).position!!.sourceId)
			assertEquals(999_999_900, first.constraints.getValue(hip).position!!.observedAtNanos)
			val paused = r.tickSnapshot(true)
			assertTrue(paused.paused); assertEquals(1, paused.tickSequence); assertEquals(2, reads)
			r.tick(true)
			val resumed = r.tickSnapshot()
			assertFalse(resumed.paused); assertEquals(3, resumed.tickSequence); assertEquals(4, reads)
			assertNull(resumed.constraints[hip]?.position)
			submit(r.inbox, p.copy(sequence = 988))
			assertNotNull(r.tickSnapshot().constraints[hip]?.position)
		}
	}

	@Test fun runtimePinsSlimeMtpAndAllResolverTargetsUntilNextTick() {
		val p = fixture(); val other = p.copy(tracker_id = "tracker-B")
		val a = tracker(1); val b = tracker(2)
		val assignments = TrackerBodyAssignments()
		val old = mapOf(hip to MainTrackerAssignment(TrackerReference.mtp(key(p)), TrackerReference.slime(a.name)),
			foot to MainTrackerAssignment(TrackerReference.mtp(key(other)), TrackerReference.slime(b.name)))
		val next = mapOf(hip to old.getValue(foot), foot to old.getValue(hip))
		assignments.replaceTargets(old)
		var mutate = true
		MonakaRuntime({
			if (mutate) { assignments.replaceTargets(next); mutate = false }
			listOf(a, b)
		}, p.coordinate_space, assignments, clock = { 1_000_000_000 }).use { r ->
			submit(r.inbox, p); submit(r.inbox, other)
			val pinned = assignments.snapshot()
			val first = r.tickSnapshot()
			assertSame(pinned, first.assignment); assertEquals(old, first.assignment.targets)
			assertEquals(key(p).observationId, first.constraints.getValue(hip).position!!.sourceId)
			assertEquals(key(other).observationId, first.constraints.getValue(foot).position!!.sourceId)
			val polled = r.pipeline.observations().associateBy { it.sourceId }
			assertEquals(hip, polled.getValue("slime:${a.name}").target)
			assertEquals(foot, polled.getValue("slime:${b.name}").target)
			val samples = r.mtp.samples()
			val second = r.tickSnapshot()
			assertEquals(pinned.generation + 1, second.assignment.generation)
			assertEquals(key(other).observationId, second.constraints.getValue(hip).position!!.sourceId)
			assertEquals(key(p).observationId, second.constraints.getValue(foot).position!!.sourceId)
			assertEquals(samples, r.mtp.samples()) // Rebinding must not create or restamp physical samples.
			assertEquals(old, first.assignment.targets)
			assertEquals(key(p).observationId, first.constraints.getValue(hip).position!!.sourceId)
			r.pipeline.clear()
			assertNotNull(first.constraints[hip]?.position)
			assertFailsWith<UnsupportedOperationException> { (first.constraints as MutableMap).clear() }
			assertFailsWith<UnsupportedOperationException> { (first.assignment.targets as MutableMap).clear() }
		}
	}

	@Test fun slimeIterationUsesPinnedTargetsWithoutReadingLegacyProvider() {
		val a = tracker(1); val b = tracker(2); val assignments = TrackerBodyAssignments()
		val old = mapOf(hip to relation("slime:${a.name}"), foot to relation("slime:${b.name}"))
		val next = mapOf(hip to old.getValue(foot), foot to old.getValue(hip))
		assignments.replaceTargets(old); val pinned = assignments.snapshot()
		val backend = SlimeTrackerObservationBackend("slime", "slime", {
			sequence { yield(a); assignments.replaceTargets(next); yield(b) }.asIterable()
		}, assignments = { error("Pinned poll must not read live assignments") })
		assertEquals(listOf(hip, foot), backend.poll(100, pinned).map { it.target })
		assertEquals(listOf(foot, hip), backend.poll(100, assignments.snapshot()).map { it.target })
	}

	@Test fun slimeLegacyPollCapturesAssignmentsOnceBeforeTrackerProvider() {
		val a = tracker(1); val b = tracker(2); var reads = 0
		var targets = mapOf(hip to relation("slime:${a.name}"), foot to relation("slime:${b.name}"))
		val backend = SlimeTrackerObservationBackend("slime", "slime", {
			targets = mapOf(hip to relation("slime:${b.name}"), foot to relation("slime:${a.name}")); listOf(a, b)
		}, assignments = { reads++; targets })
		assertEquals(listOf(hip, foot), backend.poll(100).map { it.target }); assertEquals(1, reads)
	}

	@Test fun mtpOnlyRebindsWhenSuppliedGenerationChanges() {
		val p = fixture(); val assignments = TrackerBodyAssignments(mapOf(key(p) to hip))
		val pinned = assignments.snapshot(); val inbox = MtpInbox()
		val backend = MtpObservationBackend(inbox, assignments, p.coordinate_space)
		assignments.assign(key(p), foot); submit(inbox, p)
		val first = backend.poll(1_000_000_000, pinned).single()
		assertEquals(hip, first.target); val sample = backend.samples().getValue(key(p))
		backend.drainRemovedSources()
		assertTrue(backend.poll(1_000_000_001, pinned).isEmpty())
		assertTrue(backend.drainRemovedSources().isEmpty())
		val rebound = backend.poll(1_000_000_002, assignments.snapshot()).single()
		assertEquals(foot, rebound.target); assertEquals(first.observedAtNanos, rebound.observedAtNanos)
		assertEquals(first.provenance, rebound.provenance)
		assertSame(sample, backend.samples().getValue(key(p)))
		assertEquals(setOf(key(p).observationId), backend.drainRemovedSources())
		assertTrue(backend.poll(1_000_000_003, assignments.snapshot()).isEmpty())
	}

	@Test fun resolverExplicitAssignmentDoesNotReadProviderAndLegacyReadsOnce() {
		val observations = listOf(observation("old", hip), observation("new", hip))
		val pinned = mapOf(hip to relation("old")); var reads = 0
		val resolver = ConstraintResolver { reads++; mapOf(hip to relation("new")) }
		assertEquals("old", resolver.resolve(hip, observations, pinned).position!!.sourceId)
		assertEquals(0, reads)
		assertEquals("new", resolver.resolve(hip, observations).position!!.sourceId); assertEquals(1, reads)
	}

	@Test fun pipelineAllTargetsStayPinnedWhenEligibilityMutatesLiveRegistry() {
		val assignments = TrackerBodyAssignments()
		val old = mapOf(hip to relation("old-hip"), foot to relation("old-foot"))
		assignments.replaceTargets(old); val pinned = assignments.snapshot()
		val pipeline = ConstraintPipeline(resolver = ConstraintResolver { error("Pinned resolve must bypass provider") },
			eligibility = { observation, _ ->
				assignments.replaceTargets(mapOf(hip to relation("new-hip"), foot to relation("new-foot"))); observation
			})
		pipeline.ingestAll(listOf(observation("old-hip", hip), observation("new-hip", hip),
			observation("old-foot", foot), observation("new-foot", foot)))
		val first = pipeline.resolveAll(100, pinned)
		assertEquals("old-hip", first.getValue(hip).position!!.sourceId)
		assertEquals("old-foot", first.getValue(foot).position!!.sourceId)
		assertEquals("old-hip", pipeline.resolve(hip, 100, pinned).position!!.sourceId)
		assertEquals("new-foot", pipeline.resolveAll(100, assignments.snapshot()).getValue(foot).position!!.sourceId)
	}

	@Test fun runtimeAssignedImuFreshnessUsesPinnedFallbackRelation() {
		val assignments = TrackerBodyAssignments()
		assignments.configure(hip, TrackerReference("missing-main"), TrackerReference("fallback"))
		MonakaRuntime({ emptyList() }, fixture().coordinate_space, assignments, clock = { 100 }, maxImuSampleAgeNanos = 50).use { r ->
			r.runner.register(object : ObservationBackend {
				override val backendId = "mutator"; override val profileId = "slime"
				override fun poll(observedAtNanos: Long): List<PoseObservation> {
					assignments.configure(hip, TrackerReference("fallback"))
					// Usable fallback without provenance must be STALE under the pinned assignment.
					return listOf(PoseObservation("fallback", hip, observedAtNanos, rotation = Quaternion.IDENTITY))
				}
			})
			assertNull(r.tickSnapshot().constraints.getValue(hip).rotation)
			assertNotNull(r.tickSnapshot().constraints.getValue(hip).rotation)
		}
	}

	@Test fun runnerDispatchesPinnedAndGenericPollWithSharedOwnershipAndRemoval() {
		val pipeline = ConstraintPipeline(profileRegistry = ObservationSourceProfileRegistry(listOf(
			ObservationSourceProfile.sixDof("test", 0, 100, 100))))
		val assignment = TrackerBodyAssignments().snapshot(); var supplied: TrackerBodyAssignments.Snapshot? = null
		var now = -1L; var emit = true
		val aware = object : ObservationBackend, AssignmentSnapshotAwareObservationBackend {
			override val backendId = "aware"; override val profileId = "test"
			override val sourceSetMode = ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT
			override fun poll(observedAtNanos: Long): List<PoseObservation> = error("Legacy poll used")
			override fun poll(observedAtNanos: Long, assignment: TrackerBodyAssignments.Snapshot): List<PoseObservation> {
				supplied = assignment; now = observedAtNanos
				return if (emit) listOf(observation("aware-source", hip, observedAtNanos)) else emptyList()
			}
		}
		val generic = object : ObservationBackend {
			override val backendId = "generic"; override val profileId = "test"
			override fun poll(observedAtNanos: Long) = listOf(observation("generic-source", foot, observedAtNanos))
		}
		val runner = ObservationBackendRunner(pipeline, listOf(aware, generic))
		assertEquals(1, runner.poll("aware", 100, assignment)); assertSame(assignment, supplied); assertEquals(100, now)
		assertEquals(1, runner.poll("generic", 100, assignment))
		assertEquals(setOf("aware-source"), runner.ownedSources("aware"))
		emit = false; assertEquals(0, runner.poll("aware", 101, assignment))
		assertTrue(runner.ownedSources("aware").isEmpty())
		assertEquals("generic-source", pipeline.observations().single().sourceId)
	}

	@Test fun snapshotCopiesConstraintsAndPreventsEntryMutation() {
		val assignment = TrackerBodyAssignments().snapshot(); val constraint = EffectiveConstraint(hip)
		val original = mutableMapOf(hip to constraint)
		val snapshot = MonakaResolvedTickSnapshot(0, 100, 100, false, assignment, original, -10)
		original.clear(); assertEquals(constraint, snapshot.constraints[hip])
		assertFailsWith<UnsupportedOperationException> { (snapshot.constraints as MutableMap)[foot] = EffectiveConstraint(foot) }
		assertFailsWith<UnsupportedOperationException> {
			(snapshot.constraints.entries.single() as MutableMap.MutableEntry).setValue(EffectiveConstraint(foot))
		}
	}

	@Test fun publishedAssignmentsCopyCallerMapAndRejectMutationIncludingInitialState() {
		val p = fixture(); val assignments = TrackerBodyAssignments(mapOf(key(p) to hip))
		assertFailsWith<UnsupportedOperationException> { (assignments.snapshot().targets as MutableMap).clear() }
		val original = mutableMapOf(hip to relation("main")); assignments.replaceTargets(original)
		val pinned = assignments.snapshot(); original.clear()
		assertEquals("main", pinned.targets.getValue(hip).mainTracker.observationId)
		assignments.configure(hip, TrackerReference("new"))
		assertEquals("main", pinned.targets.getValue(hip).mainTracker.observationId)
	}

	@Test fun negativeClockFailsBeforePollConsumesSequenceAndKeepsLastValidTickTime() {
		var now = -1L; var polls = 0; var reads = 0
		MonakaRuntime({ polls++; emptyList() }, fixture().coordinate_space, clock = { reads++; now }).use { r ->
			assertFailsWith<IllegalArgumentException> { r.tickSnapshot() }
			assertEquals(1, reads); assertEquals(0, polls); assertEquals(0, r.lastTickNanos)
			now = 100; assertEquals(1, r.tickSnapshot().tickSequence)
			assertEquals(100, r.lastTickNanos)
			now = -2; assertFailsWith<IllegalArgumentException> { r.tick() }
			assertEquals(100, r.lastTickNanos); assertEquals(1, polls)
		}
	}

	@Test fun clockFailureDoesNotReuseReservedSequence() {
		var fail = true
		MonakaRuntime({ emptyList() }, fixture().coordinate_space, clock = { if (fail) error("clock failure") else 100 }).use { r ->
			assertFailsWith<IllegalStateException> { r.tickSnapshot() }; fail = false
			assertEquals(1, r.tickSnapshot().tickSequence)
		}
	}

	@Test fun overflowFailsClosedBeforeClockAndNeverWrapsOrReusesSequence() {
		var reads = 0
		MonakaRuntime({ emptyList() }, fixture().coordinate_space, clock = { reads++; 100 }).use { r ->
			MonakaRuntime::class.java.getDeclaredField("nextTickSequence").apply { isAccessible = true }.setLong(r, Long.MAX_VALUE)
			assertEquals(Long.MAX_VALUE, r.tickSnapshot().tickSequence)
			repeat(2) { assertFailsWith<IllegalStateException> { r.tickSnapshot() } }
			assertEquals(1, reads)
		}
	}

	@Test fun closedRuntimeRejectsBothApisWithoutClockRead() {
		var reads = 0
		val r = MonakaRuntime({ emptyList() }, fixture().coordinate_space, clock = { reads++; 100 })
		r.close(); assertFailsWith<IllegalStateException> { r.tick() }
		assertFailsWith<IllegalStateException> { r.tickSnapshot() }; assertEquals(0, reads)
	}

	@Test fun snapshotFailureIsolationPreservesPeerSource() {
		val p = fixture(); val a = tracker(1)
		val assignments = TrackerBodyAssignments()
		assignments.configure(hip, TrackerReference.mtp(key(p)), TrackerReference.slime(a.name))
		MonakaRuntime({ listOf(a) }, p.coordinate_space, assignments, clock = { 1_000_000_000 }).use { r ->
			submit(r.inbox, p); r.tickSnapshot()
			r.runner.replace(object : ObservationBackend {
				override val backendId = "mtp"; override val profileId = "mtp"
				override fun poll(observedAtNanos: Long): List<PoseObservation> = error("backend failure")
			})
			val result = r.tickSnapshot()
			assertEquals(1, result.tickSequence); assertNull(result.constraints.getValue(hip).position)
			assertEquals("slime:${a.name}", result.constraints.getValue(hip).rotation!!.sourceId)
			assertTrue(r.mtp.samples().isEmpty()); assertEquals(1, r.inbox.diagnostics().getValue("BackendFailure:mtp"))
		}
	}

	@Test fun backendRegistrationDuringPollIsVisibleOnNextTick() {
		val p = fixture(); var extraPolls = 0
		MonakaRuntime({ emptyList() }, p.coordinate_space, clock = { 100 }).use { r ->
			val extra = object : ObservationBackend {
				override val backendId = "extra"; override val profileId = "slime"
				override fun poll(observedAtNanos: Long): List<PoseObservation> { extraPolls++; return emptyList() }
			}
			r.runner.register(object : ObservationBackend {
				override val backendId = "registrar"; override val profileId = "slime"
				override fun poll(observedAtNanos: Long): List<PoseObservation> {
					if ("extra" !in r.runner.snapshot()) r.runner.register(extra)
					return emptyList()
				}
			})
			r.tickSnapshot(); assertEquals(0, extraPolls)
			r.tickSnapshot(); assertEquals(1, extraPolls)
		}
	}
}
