package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class HybridDiagnosticStressTests {
	private val space = CoordinateSpace("stress-world", "rh_y_up_neg_z_forward", 2)
	private val mainId = "mtp:main"
	private val imuId = "slime:imu"
	private val tuning = RotationCorrectionTuning(2_000_000, 5_000_000, .01, .01,
		3.2, .5, 100.0, 1, 20_000_000, 20_000_000)

	private fun snapshot(sequence: Long, age: Long, modality: TrackingModality = TrackingModality.FULL) =
		HybridTrackingDiagnosticSnapshot(MainSampleState(modality, modality == TrackingModality.FULL,
			modality != TrackingModality.NONE), mainId, sequence, age, imuId, true, sequence, age,
			true, true, true, mainId, mainId, true, RotationCorrectionState.TRACKING, true,
			age, .1, LearningDecision.ACCEPTED, "accepted", ApplicationDecision.NOT_REQUESTED,
			"owner_not_fallback", ContinuityState.MAIN_DIRECT, true,
			OutputPositionSource.RESOLVED_MAIN, mainId, "main_full")

	@Test fun semanticEventsIgnoreSequenceAgeResidualAndDoNotFlood() {
		val events = mutableListOf<HybridTrackingDiagnosticEvent>()
		val recorder = HybridTrackingDiagnosticRecorder(events::add)
		repeat(100) { i -> recorder.record(snapshot(i.toLong(), i * 1_000_000L).copy(correctionResidualRadians = i.toDouble())) }
		assertEquals(1, events.size)
		assertEquals(99L, recorder.latest!!.mainSequence)
		for (mode in listOf(TrackingModality.ROTATION_ONLY, TrackingModality.NONE, TrackingModality.FULL))
			recorder.record(snapshot(100, 0, mode))
		assertEquals(4, events.size)
		assertTrue(events.first().snapshot.correctionEnabled)
		assertTrue(events.first().compactLine().startsWith("[MonakaHIL] HIP "))
		assertFalse(events.first().compactLine().contains("Quaternion"))
	}

	@Test fun failedDiagnosticSinkCannotInterruptSnapshotUpdates() {
		val recorder = HybridTrackingDiagnosticRecorder { throw IllegalStateException("log unavailable") }
		recorder.record(snapshot(1, 0))
		recorder.record(snapshot(2, 1_000_000, TrackingModality.ROTATION_ONLY))
		assertEquals(2L, recorder.latest!!.mainSequence)
	}

	@Test fun deterministicCoreCorrectionAndPolicyStress() {
		val seeds = 32; val steps = 300
		val generatedEvents = mutableSetOf<Int>()
		val observedModalities = mutableSetOf<TrackingModality>()
		repeat(seeds) { seed ->
			val random = Random(seed)
			val assignments = TrackerBodyAssignments().also {
				it.configure(TrackerPosition.HIP, TrackerReference(mainId), TrackerReference(imuId))
			}
			val filter = AssignedImuSampleFreshness(assignments, tuning.maxImuSampleAgeNanos)
			val pipeline = ConstraintPipeline(resolver = ConstraintResolver { assignments.snapshot().targets },
				eligibility = filter::apply)
			val correction = HipRotationCorrection(RotationCorrectionFrames(Quaternion.IDENTITY,
				Quaternion.IDENTITY, space, true), tuning)
			var now = 1_000_000_000L
			var mainSeq = 0L; var imuSeq = 0L; var mainAt = now; var imuAt = now
			var mainEpoch = 0; var imuEpoch = 0; var mountEpoch = 0; var mapping = 0L
			for (step in 0 until steps) {
				val event = random.nextInt(16)
				generatedEvents += event
				now += random.nextLong(1_000_000, 5_000_001)
				when (event) {
					0 -> { mainEpoch++; mainSeq = 0; mainAt = now }
					1 -> { imuEpoch++; imuSeq = 0; imuAt = now }
					2 -> { mountEpoch++; imuSeq = 0; imuAt = now }
					3 -> { mapping++; mainSeq = 0; mainAt = now }
					4 -> mainAt = now - 50_000_000 // teacher rollback
					5 -> imuAt = now + 1_000_000 // future physical sample
					6 -> imuAt = now - 50_000_000 // stalled physical sample
					7 -> if (mainSeq > 0) mainSeq--
					8 -> if (imuSeq > 0) imuSeq--
					10 -> { mainSeq++; mainAt = now }
					11 -> { imuSeq++; imuAt = now }
					else -> {
						if (random.nextBoolean()) { mainSeq++; mainAt = now }
						if (random.nextBoolean()) { imuSeq++; imuAt = now }
					}
				}
				val modality = TrackingModality.entries[random.nextInt(3)]
				observedModalities += modality
				val mainRotation = if (modality == TrackingModality.NONE) null else Quaternion.rotationAroundYAxis(.5f)
				val imuRotation = if (event == 9) Quaternion(Float.NaN, 0f, 0f, 0f)
					else (Quaternion.rotationAroundXAxis(.2f) * Quaternion.rotationAroundYAxis(.1f)).unit()
				val mainSpace = if (event == 10) space.copy(revision = 3) else space
				val imuSpace = if (event == 11) space.copy(revision = 3) else space
				val main = PoseObservation(mainId, TrackerPosition.HIP, now,
					position = if (modality == TrackingModality.FULL) Vector3(1f, 2f, 3f) else null,
					rotation = mainRotation, modality = modality,
					provenance = ObservationSampleProvenance(mainSeq, mainAt.coerceAtLeast(0), "session:$mainEpoch",
						"mount", mapping, mainSpace), correctionRotation = mainRotation)
				val imu = PoseObservation(imuId, TrackerPosition.HIP, now, rotation = imuRotation,
					provenance = ObservationSampleProvenance(imuSeq, imuAt.coerceAtLeast(0), "session:$imuEpoch",
						"mount:$mountEpoch", 0, imuSpace), correctionRotation = imuRotation)
				val detail = "seed=$seed step=$step event=$event main=$modality/$mainSeq/$mainAt " +
					"imu=$imuSeq/$imuAt corr=${correction.state}/${correction.ready}"
				try {
					pipeline.ingest(main); pipeline.ingest(imu)
					val resolved = pipeline.resolve(TrackerPosition.HIP, now)
					val eligible = pipeline.observations(now).associateBy { it.sourceId }
					val selectedImu = eligible.getValue(imuId)
					val beforeCorrection = correction.correction
					val beforeLearnedAt = correction.lastLearnedAtNanos
					val beforeReady = correction.ready
					val corrected = correction.update(eligible[mainId], selectedImu, resolved.rotation?.sourceId,
						now, assignments.snapshot().generation, space)
					val context = "$detail resolver=${resolved.rotation?.sourceId} " +
						"corr=${correction.state}/${correction.ready} learn=${correction.learningReason} " +
						"apply=${correction.applicationReason} continuity=not-in-core visible=not-in-core"
					if (modality == TrackingModality.FULL)
						assertEquals(mainId, resolved.rotation?.sourceId, context)
					if (!selectedImu.rotationQuality.usable) {
						assertNotEquals(imuId, resolved.rotation?.sourceId, context)
						assertNull(corrected, context)
						if (modality == TrackingModality.ROTATION_ONLY)
							assertEquals(mainId, resolved.rotation?.sourceId, context)
						if (modality == TrackingModality.NONE) assertNull(resolved.rotation, context)
					}
					if (corrected?.rotation != null) {
						assertEquals(imuId, resolved.rotation?.sourceId, context)
						assertTrue(correction.ready, context)
						val expected = (correction.correction * imuRotation).unit()
						assertTrue(abs(expected.dot(corrected.rotation.value.unit())) > .9999f, context)
						assertTrue(corrected.rotation.value.lenSq().isFinite(), context)
					}
					if (correction.lastLearnedAtNanos != null && correction.learningDecision != LearningDecision.ACCEPTED)
						assertEquals(beforeLearnedAt, correction.lastLearnedAtNanos, context)
					if (modality == TrackingModality.NONE && beforeReady && correction.ready)
						assertEquals(beforeCorrection, correction.correction, context)
				} catch (failure: AssertionError) { throw AssertionError(detail, failure) }
			}
		}
		assertEquals((0..15).toSet(), generatedEvents)
		assertEquals(TrackingModality.entries.toSet(), observedModalities)
	}
}
