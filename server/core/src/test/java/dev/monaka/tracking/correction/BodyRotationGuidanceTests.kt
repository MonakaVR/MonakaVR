package dev.monaka.tracking.correction

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.DeclaredTrackerCapabilities
import dev.monaka.tracking.LearningDecision
import dev.monaka.tracking.RotationCorrectionTuning
import dev.monaka.tracking.TrackingModality
import dev.monaka.tracking.revision.RotationOwner
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyRotationGuidanceTests {
	private val id = Quaternion.IDENTITY
	private val mainQ = Quaternion.rotationAroundYAxis(.5f)
	private val space = CoordinateSpace("approved-world", "rh_y_up_neg_z_forward", 2)
	private val main = GuidanceSource(GuidanceIdentity("publisher", "main", "same-display-name"), epoch("main"))
	private val imu = GuidanceSource(GuidanceIdentity("physical", "imu", "same-display-name"), epoch("imu"))
	private fun epoch(source: String) = GuidanceEpoch("session", source, "calibration", 1, space)
	private fun binding(body: String = "HIP") = GuidanceBinding(body, 1, main, imu, GuidanceFrames(id, id, "approval-1", true, main, imu))
	private fun tuning(rate: Double = 1000.0, residual: Double = PI, maxDt: Long = 100_000_000) = RotationCorrectionTuning(2_000_000, 1_000_000, .0001, .0001, residual, .01, rate, 1, maxDt, 100_000_000)
	private fun sample(
		source: GuidanceSource,
		b: GuidanceBinding,
		seq: Long,
		at: Long,
		q: Quaternion,
		origin: GuidanceOrigin,
	) = GuidanceReceipt(seq, at, at, source.epoch).let {
		GuidanceSample(b.bodyId, source.identity, q, it, GuidanceEvidence(origin, source.identity, it, q))
	}
	private fun teacher(
		b: GuidanceBinding,
		seq: Long,
		at: Long,
		q: Quaternion = mainQ,
		modality: TrackingModality = TrackingModality.FULL,
	) = GuidanceTeacher(
		sample(b.main, b, seq, at, q, GuidanceOrigin.INDEPENDENT_MAIN_ACCEPTANCE),
		modality,
		if (modality == TrackingModality.FULL) Vector3(1f, 2f, 3f) else null,
		DeclaredTrackerCapabilities(listOf("position", "orientation")),
		modality == TrackingModality.FULL,
		modality != TrackingModality.NONE,
	)
	private fun imu(b: GuidanceBinding, seq: Long, at: Long, q: Quaternion = id) = GuidanceImu(sample(b.imu, b, seq, at, q, GuidanceOrigin.PHYSICAL_IMU_ACCEPTANCE), true)
	private fun input(
		b: GuidanceBinding = binding(),
		seq: Long = 1,
		at: Long = 1_000_000,
		q: Quaternion = mainQ,
		iq: Quaternion = id,
		modality: TrackingModality = TrackingModality.FULL,
		owner: RotationOwner = if (modality == TrackingModality.FULL) RotationOwner.MAIN else RotationOwner.FALLBACK,
	) = GuidanceInput(at, b, teacher(b, seq, at, q, modality), imu(b, seq, at, iq), owner)
	private fun advance(
		s: BodyRotationState = BodyRotationState(),
		i: GuidanceInput = input(),
		t: RotationCorrectionTuning = tuning(),
	) = BodyRotationGuidance.advance(s, i, t)
	private fun ready(b: GuidanceBinding = binding(), t: RotationCorrectionTuning = tuning()): BodyRotationState {
		val s = advance(i = input(b), t = t).state
		return advance(s, input(b, 2, 4_000_000), t).state.also {
			assertTrue(it.ready)
			assertEquals(GuidancePhase.TRACKING, it.phase)
		}
	}
	private fun equivalent(expected: Quaternion, actual: Quaternion, tolerance: Double = 1e-6) = assertTrue(RotationGuidanceMath.angle(expected, actual) <= tolerance, "$expected != $actual")
	private fun restamp(s: GuidanceSample, p: GuidanceReceipt) = s.copy(receipt = p, evidence = s.evidence?.copy(receipt = p))

	@Test fun fullPairsLearnWithoutChangingMainOwnershipOrApplyingToMain() {
		val first = advance()
		assertEquals(GuidanceReason.FIRST_PAIR, first.learningReason)
		assertFalse(first.state.ready)
		val r = advance(first.state, input(seq = 2, at = 4_000_000))
		assertEquals(LearningDecision.ACCEPTED, r.learning)
		assertTrue(r.state.ready)
		equivalent(mainQ, r.state.correction)
		assertEquals(RotationOwner.MAIN, r.ownership.rotationOwner)
		assertNotNull(r.correctedImu)
		assertNull(r.fallbackCandidate)
		assertEquals(4_000_000, r.state.lastLearnedAtNanos)
	}

	@Test fun fullRotationOnlyNoneFullAndRelossKeepReadyOwnerAndPhysicalHoldTime() {
		val b = binding()
		val s = ready(b)
		val degraded = advance(s, input(b, 3, 5_000_000, q = Quaternion.rotationAroundYAxis(1f), modality = TrackingModality.ROTATION_ONLY))
		assertEquals(GuidancePhase.DEGRADED, degraded.state.phase)
		assertEquals(LearningDecision.NOT_ATTEMPTED, degraded.learning)
		assertEquals(s.correction, degraded.state.correction)
		assertEquals(s.lastLearnedAtNanos, degraded.state.lastLearnedAtNanos)
		assertTrue(degraded.state.ready)
		assertEquals(b.imu.identity, degraded.fallbackCandidate!!.owner)
		assertEquals(5_000_000, degraded.fallbackCandidate.sampleAtNanos)
		val none = advance(degraded.state, GuidanceInput(6_000_000, b, null, imu(b, 3, 5_000_000), RotationOwner.FALLBACK))
		assertEquals(GuidancePhase.IMU_ONLY, none.state.phase)
		equivalent(mainQ, none.fallbackCandidate!!.orientation)
		assertEquals(5_000_000, none.fallbackCandidate.sampleAtNanos)
		val singleFull = advance(none.state, input(b, 4, 7_000_000))
		assertEquals(GuidancePhase.REACQUIRING, singleFull.state.phase)
		assertNull(singleFull.fallbackCandidate)
		val ticks = advance(singleFull.state, input(b, 4, 7_000_000).copy(nowNanos = 50_000_000))
		assertEquals(GuidancePhase.REACQUIRING, ticks.state.phase)
		val recovered = advance(ticks.state, input(b, 5, 51_000_000))
		assertEquals(GuidancePhase.TRACKING, recovered.state.phase)
		val relost = advance(recovered.state, input(b, 6, 52_000_000, modality = TrackingModality.NONE))
		assertEquals(GuidancePhase.IMU_ONLY, relost.state.phase)
		assertTrue(relost.state.ready)
		assertNotNull(relost.fallbackCandidate)
	}

	@Test fun completeLossNeverManufacturesPoseEvenWhenCorrectionReady() {
		val s = ready()
		val r = advance(s, GuidanceInput(5_000_000, binding(), null, null, RotationOwner.NONE))
		assertEquals(GuidancePhase.UNAVAILABLE, r.state.phase)
		assertTrue(r.state.ready)
		assertNull(r.correctedImu)
		assertNull(r.fallbackCandidate)
		assertFalse(r.ownership.positionFromMain)
		assertEquals(RotationOwner.NONE, r.ownership.rotationOwner)
	}

	@Test fun oneFullSampleAndTicksCannotLearnOrCompleteDwell() {
		var r = advance()
		for (now in listOf(4_000_000L, 50_000_000, 200_000_000)) {
			r = advance(r.state, input().copy(nowNanos = now))
			assertFalse(r.state.ready)
			assertEquals(id, r.state.correction)
			assertNull(r.state.lastLearnedAtNanos)
		}
	}

	@Test fun duplicatePairDoesNotRefreshLearnedTimeAndBothSourcesMustAdvance() {
		val s = ready()
		val b = binding()
		val duplicate = advance(s, input(seq = 2, at = 4_000_000).copy(nowNanos = 5_000_000))
		assertEquals(GuidanceReason.SAME_PAIR, duplicate.learningReason)
		assertEquals(s.lastLearnedAtNanos, duplicate.state.lastLearnedAtNanos)
		val one = advance(duplicate.state, input(seq = 3, at = 6_000_000).copy(imu = imu(b, 2, 4_000_000)))
		assertEquals(GuidanceReason.PAIR_WINDOW, one.learningReason)
		assertEquals(s.correction, one.state.correction)
	}

	@Test fun bothSourcesMustAdvanceEvenWhenTimeWindowAllowsReusedImu() {
		val b = binding()
		val s = advance().state
		val r = advance(s, input(seq = 2, at = 1_500_000).copy(imu = imu(b, 1, 1_000_000)))
		assertEquals(GuidanceReason.SAME_PAIR, r.learningReason)
		assertFalse(r.state.ready)
	}

	@Test fun futureAndStalePhysicalSamplesBlockApplicationWithoutErasingCalibration() {
		val s = ready()
		val b = binding()
		for ((now, at, reason) in listOf(
			Triple(5_000_000L, 6_000_000L, GuidanceReason.TIME_INVALID),
			Triple(200_000_000L, 5_000_000L, GuidanceReason.STALE),
		)) {
			val r = advance(s, GuidanceInput(now, b, null, imu(b, 3, at), RotationOwner.FALLBACK))
			assertEquals(reason, r.imuReason)
			assertNull(r.fallbackCandidate)
			assertTrue(r.state.ready)
			assertEquals(s.correction, r.state.correction)
			val resumed = advance(r.state, GuidanceInput(now + 1, b, null, imu(b, 4, now + 1), RotationOwner.FALLBACK))
			assertNotNull(resumed.fallbackCandidate)
		}
	}

	@Test fun receivedTimeMustBindToSampleAndCannotBeFuture() {
		val b = binding()
		val i = input()
		val s = i.imu!!.sample
		for (p in listOf(
			s.receipt!!.copy(receivedAtNanos = 999_999),
			s.receipt.copy(receivedAtNanos = 1_000_001),
			s.receipt.copy(sequence = -1),
			s.receipt.copy(sampleAtNanos = -1),
		)) {
			val r = advance(i = i.copy(imu = imu(b, 1, 1_000_000).copy(sample = restamp(s, p))))
			assertEquals(GuidanceReason.TIME_INVALID, r.imuReason)
		}
	}

	@Test fun sequenceTimestampAndReceiptRollbackRejectWithoutMovingWatermark() {
		val s = ready()
		val b = binding()
		for (bad in listOf(imu(b, 1, 5_000_000), imu(b, 3, 3_000_000), imu(b, 3, 4_000_000))) {
			val r = advance(s, input(seq = 3, at = 5_000_000).copy(imu = bad))
			assertEquals(GuidanceReason.SAMPLE_ROLLBACK, r.imuReason)
			assertEquals(s.imuCursor, r.state.imuCursor)
			assertNull(r.correctedImu)
			assertTrue(r.state.ready)
		}
	}

	@Test fun changedSamePacketTimestampOrOrientationCannotRefreshOrTeach() {
		val s = ready()
		val b = binding()
		for (bad in listOf(imu(b, 2, 5_000_000), imu(b, 2, 4_000_000, mainQ))) {
			val r = advance(s, input(seq = 3, at = 5_000_000).copy(imu = bad))
			assertEquals(GuidanceReason.SAMPLE_MUTATED, r.imuReason)
			assertNull(r.fallbackCandidate)
			assertEquals(s.lastLearnedAtNanos, r.state.lastLearnedAtNanos)
		}
	}

	@Test fun mainRollbackStopsLearningWhileHealthyFallbackCanApply() {
		val b = binding()
		val s = ready()
		val i = GuidanceInput(5_000_000, b, teacher(b, 1, 5_000_000), imu(b, 3, 5_000_000), RotationOwner.FALLBACK)
		val r = advance(s, i)
		assertEquals(GuidanceReason.SAMPLE_ROLLBACK, r.teacherReason)
		assertEquals(s.lastLearnedAtNanos, r.state.lastLearnedAtNanos)
		assertNotNull(r.fallbackCandidate)
	}

	@Test fun pairWindowAndLargeDtRejectTrainingButRetainHealthyApplication() {
		val b = binding()
		val s = ready()
		val unpaired = advance(s, GuidanceInput(8_000_000, b, teacher(b, 3, 5_000_000), imu(b, 3, 8_000_000), RotationOwner.FALLBACK))
		assertEquals(GuidanceReason.PAIR_WINDOW, unpaired.learningReason)
		assertEquals(s.lastLearnedAtNanos, unpaired.state.lastLearnedAtNanos)
		// FULL retains Main ownership, so the correction is diagnostic only.
		assertNull(unpaired.fallbackCandidate)
		assertNotNull(unpaired.correctedImu)
		val gap = advance(s, input(seq = 3, at = 20_000_000), tuning(maxDt = 5_000_000))
		assertEquals(GuidanceReason.LARGE_DT, gap.learningReason)
		assertEquals(s.correction, gap.state.correction)
		assertTrue(gap.state.ready)
		assertEquals(s.lastLearnedAtNanos, gap.state.lastLearnedAtNanos)
	}

	@Test fun residualOutliersNeverEnterTheCorrectionOrDwell() {
		val first = advance(i = input(q = mainQ), t = tuning(residual = .1))
		assertEquals(GuidanceReason.RESIDUAL_OUTLIER, first.learningReason)
		assertNull(first.state.firstFullAtNanos)
		assertFalse(first.state.ready)
		val s = ready()
		val r = advance(s, input(seq = 3, at = 5_000_000, q = Quaternion.rotationAroundYAxis(2f)), tuning(residual = .1))
		assertEquals(GuidanceReason.RESIDUAL_OUTLIER, r.learningReason)
		assertEquals(s.correction, r.state.correction)
		assertTrue(r.state.ready)
	}

	@Test fun rateBoundAndExponentialTimeConstantUseAcceptedPairDt() {
		val t = tuning(rate = 1.0)
		val first = advance(t = t).state
		val r = advance(first, input(seq = 2, at = 4_000_000), t)
		assertTrue(RotationGuidanceMath.angle(id, r.state.correction) <= .003 + 1e-7)
		assertFalse(r.state.ready)
		val slow = RotationGuidanceMath.learn(id, mainQ, .1, 1.0, 100.0)
		assertEquals(.5 * (1 - kotlin.math.exp(-.1)), RotationGuidanceMath.angle(id, slow), 1e-6)
	}

	@Test fun signsAndNearPiChooseEquivalentShortestArc() {
		val q = Quaternion.rotationAroundYAxis((PI - .00001).toFloat())
		val t = tuning(rate = 2000.0) // 3 ms permits the complete near-pi arc.
		val a = advance(i = input(q = q), t = t).state
		val b = advance(i = input(q = -q, iq = -id), t = t).state
		val x = advance(a, input(seq = 2, at = 4_000_000, q = -q), t)
		val y = advance(b, input(seq = 2, at = 4_000_000, q = q, iq = -id), t)
		equivalent(x.state.correction, y.state.correction)
		equivalent(q, x.state.correction)
		assertTrue(x.state.ready)
	}

	@Test fun nonCommutingMountsAndWorldSideCompositionMatchNumericalModel() {
		val b = binding().copy(frames = GuidanceFrames(Quaternion.rotationAroundZAxis(.2f), Quaternion.rotationAroundXAxis(-.3f), "approved", true, main, imu))
		val qm = Quaternion.rotationAroundXAxis(.3f)
		val qi = Quaternion.rotationAroundYAxis(.4f)
		val first = advance(i = input(b, q = qm, iq = qi)).state
		val r = advance(first, input(b, 2, 4_000_000, qm, qi))
		val mb = RotationGuidanceMath.body(qm, b.frames.mainTrackerToBody)
		val ib = RotationGuidanceMath.body(qi, b.frames.imuToBody)
		equivalent(mb, r.correctedImu!!.orientation)
		equivalent(RotationGuidanceMath.target(mb, ib), r.state.correction)
		assertTrue(RotationGuidanceMath.angle(mb, RotationGuidanceMath.body(ib, r.state.correction)) > .01)
	}

	@Test fun invalidNaNInfinityAndZeroQuaternionsRejectBothSources() {
		for (bad in listOf(
			Quaternion(0f, 0f, 0f, 0f),
			Quaternion(Float.NaN, 0f, 0f, 0f),
			Quaternion(1f, Float.POSITIVE_INFINITY, 0f, 0f),
			Quaternion(1f, 0f, Float.NEGATIVE_INFINITY, 0f),
		)) {
			assertEquals(GuidanceReason.ROTATION_INVALID, advance(i = input(q = bad)).teacherReason)
			assertEquals(GuidanceReason.ROTATION_INVALID, advance(i = input(iq = bad)).imuReason)
		}
	}

	@Test fun finiteNonUnitHugeAndQuantizedQuaternionsNormalize() {
		for (q in listOf(
			Quaternion(2f, 0f, 0f, 0f),
			Quaternion(Float.MAX_VALUE, 0f, 0f, 0f),
			Quaternion(.9689f, 0f, .2474f, 0f),
		)) {
			val r = advance(i = input(iq = q))
			assertEquals(GuidanceReason.NONE, r.imuReason)
			assertEquals(1.0, RotationGuidanceMath.normalize(q)!!.lenSq().toDouble(), 1e-6)
		}
	}

	@Test fun physicalImuEvidenceCannotBeReplacedWithPoseOrOutputOrAbsentProvenance() {
		val i = input()
		val physical = i.imu!!
		for (origin in listOf(GuidanceOrigin.POSE_ORIENTATION, GuidanceOrigin.INDEPENDENT_MAIN_ACCEPTANCE, GuidanceOrigin.OUTPUT)) {
			val r = advance(i = i.copy(imu = physical.copy(sample = physical.sample.copy(evidence = physical.sample.evidence!!.copy(origin = origin)))))
			assertEquals(GuidanceReason.ORIGIN_NOT_INDEPENDENT, r.imuReason)
		}
		for (sample in listOf(physical.sample.copy(evidence = null), physical.sample.copy(receipt = null))) {
			assertEquals(GuidanceReason.PROVENANCE_MISSING, advance(i = i.copy(imu = physical.copy(sample = sample))).imuReason)
		}
	}

	@Test fun independentReceiptSequenceTimeCalibrationAndOrientationMustMatchInput() {
		val i = input()
		val physical = i.imu!!
		val proof = physical.sample.evidence!!
		for (bad in listOf(
			proof.copy(receipt = proof.receipt.copy(sequence = 90)),
			proof.copy(receipt = proof.receipt.copy(receivedAtNanos = 100)),
			proof.copy(receipt = proof.receipt.copy(epoch = proof.receipt.epoch.copy(calibrationEpoch = "other"))),
			proof.copy(identity = main.identity),
			proof.copy(orientation = mainQ),
		)) {
			val r = advance(i = i.copy(imu = physical.copy(sample = physical.sample.copy(evidence = bad))))
			assertEquals(GuidanceReason.RECEIPT_MISMATCH, r.imuReason)
		}
	}

	@Test fun mainRequiresIndependentFullValidPositionAndKnownStaticPosQCapability() {
		val i = input()
		val teacher = i.teacher!!
		for (cap in listOf(null, DeclaredTrackerCapabilities.orientationOnly(), DeclaredTrackerCapabilities(emptyList()))) {
			assertEquals(GuidanceReason.CAPABILITIES_UNKNOWN, advance(i = i.copy(teacher = teacher.copy(capabilities = cap))).teacherReason)
		}
		for (modality in listOf(TrackingModality.ROTATION_ONLY, TrackingModality.NONE)) {
			val r = advance(i = input(modality = modality))
			assertNotEquals(LearningDecision.ACCEPTED, r.learning)
			assertFalse(r.state.ready)
		}
		for (position in listOf(null, Vector3(Float.NaN, 0f, 0f), Vector3(0f, Float.POSITIVE_INFINITY, 0f))) {
			assertEquals(GuidanceReason.POSITION_INVALID, advance(i = i.copy(teacher = teacher.copy(position = position))).teacherReason)
		}
		val derived = teacher.sample.copy(evidence = teacher.sample.evidence!!.copy(origin = GuidanceOrigin.OUTPUT))
		assertEquals(GuidanceReason.ORIGIN_NOT_INDEPENDENT, advance(i = i.copy(teacher = teacher.copy(sample = derived))).teacherReason)
	}

	@Test fun sameDisplayNameDifferentIdentityAndWrongBodyAreRejected() {
		val i = input()
		val physical = i.imu!!
		for (sample in listOf(
			physical.sample.copy(identity = physical.sample.identity.copy(publisher = "other-device")),
			physical.sample.copy(bodyId = "LEFT_FOOT"),
		)) {
			assertEquals(GuidanceReason.IDENTITY_MISMATCH, advance(i = i.copy(imu = physical.copy(sample = sample))).imuReason)
		}
		assertEquals(GuidanceReason.UNASSIGNED, advance(i = i.copy(binding = null)).learningReason)
	}

	@Test fun authorityChangesInvalidateReadyEvenWhileMainAndImuAreAbsent() {
		val b = binding()
		val s = ready()
		val epochs = listOf(
			b.main.epoch.copy(session = "new"),
			b.main.epoch.copy(sourceEpoch = "new"),
			b.main.epoch.copy(mappingRevision = 9),
			b.main.epoch.copy(calibrationEpoch = "new"),
			b.main.epoch.copy(space = space.copy(id = "other")),
			b.main.epoch.copy(space = space.copy(revision = 3)),
		)
		val bindings = epochs.map { b.copy(main = b.main.copy(epoch = it)) } +
			listOf(
				b.copy(imu = b.imu.copy(epoch = b.imu.epoch.copy(sourceEpoch = "restart"))),
				b.copy(imu = b.imu.copy(epoch = b.imu.epoch.copy(calibrationEpoch = "reset"))),
				b.copy(assignmentRevision = 2),
				b.copy(frames = b.frames.copy(approvalRevision = "new")),
				b.copy(frames = b.frames.copy(imuToBody = mainQ)),
			)
		for (changed in bindings) {
			val r = advance(s, GuidanceInput(5_000_000, changed, null, null, RotationOwner.NONE))
			assertFalse(r.state.ready)
			assertEquals(id, r.state.correction)
			assertNull(r.fallbackCandidate)
		}
	}

	@Test fun unexpectedSampleEpochRevokesReadinessUntilAuthorityIsReestablished() {
		val b = binding()
		val s = ready()
		val i = input(seq = 3, at = 5_000_000)
		val wrong = i.imu!!.sample.let { restamp(it, it.receipt!!.copy(epoch = it.receipt.epoch.copy(sourceEpoch = "restart"))) }
		val r = advance(s, i.copy(imu = i.imu.copy(sample = wrong)))
		assertEquals(GuidanceReason.EPOCH_MISMATCH, r.imuReason)
		assertFalse(r.state.ready)
		val mt = i.teacher!!.sample.let { restamp(it, it.receipt!!.copy(epoch = it.receipt.epoch.copy(session = "new"))) }
		val mr = advance(s, i.copy(teacher = i.teacher.copy(sample = mt), selectedOwner = RotationOwner.FALLBACK))
		assertFalse(mr.state.ready)
		assertNull(mr.fallbackCandidate)
		val lost = advance(mr.state, GuidanceInput(6_000_000, b, null, imu(b, 4, 6_000_000), RotationOwner.FALLBACK))
		assertFalse(lost.state.ready)
		assertNull(lost.fallbackCandidate)
	}

	@Test fun unconfirmedInvalidMountAndInvalidSpaceFailClosed() {
		val b = binding()
		for (frames in listOf(
			b.frames.copy(confirmed = false),
			b.frames.copy(approvalRevision = ""),
			b.frames.copy(mainTrackerToBody = Quaternion(0f, 0f, 0f, 0f)),
		)) {
			assertEquals(GuidanceReason.FRAME_UNAPPROVED, advance(i = input(b.copy(frames = frames))).learningReason)
		}
		val invalid = b.main.epoch.copy(space = space.copy(convention = "unknown"))
		assertEquals(GuidanceReason.BINDING_INVALID, advance(i = input(b.copy(main = b.main.copy(epoch = invalid)))).learningReason)
	}

	@Test fun feedbackExcludedAcrossPublisherSourceTrackerAndSamples() {
		for (prefix in listOf("monaka-direct:", "monaka-solver:", "monaka-private:", "human://")) {
			val b = binding()
			for (identity in listOf(
				b.main.identity.copy(publisher = prefix + "x"),
				b.main.identity.copy(source = prefix + "x"),
				b.main.identity.copy(tracker = prefix + "x"),
			)) {
				assertEquals(GuidanceReason.FEEDBACK, advance(i = input(b.copy(main = b.main.copy(identity = identity)))).learningReason)
			}
			val i = input()
			val sample = i.imu!!.sample.copy(identity = i.imu.sample.identity.copy(tracker = prefix + "x"))
			assertEquals(GuidanceReason.FEEDBACK, advance(i = i.copy(imu = i.imu.copy(sample = sample))).imuReason)
		}
	}

	@Test fun hipAndNonHipShareNumericalModelAndKeepIndependentStates() {
		val hip = binding()
		val foot = binding("LEFT_FOOT")
		val h = ready(hip)
		val f = ready(foot)
		equivalent(h.correction, f.correction)
		val changed = advance(h, input(hip, 3, 5_000_000, q = Quaternion.rotationAroundXAxis(.3f)))
		assertNotEquals(h.correction, changed.state.correction)
		equivalent(mainQ, f.correction)
		val out = advance(f, input(foot, 3, 5_000_000, modality = TrackingModality.NONE))
		equivalent(mainQ, out.fallbackCandidate!!.orientation)
		assertEquals("LEFT_FOOT", out.state.binding!!.bodyId)
	}

	@Test fun deterministicReplayDoesNotMutatePriorStateAndRejectsReordering() {
		val inputs = listOf(
			input(),
			input(seq = 2, at = 4_000_000),
			input(seq = 3, at = 5_000_000, modality = TrackingModality.NONE),
			input(seq = 4, at = 6_000_000),
			input(seq = 5, at = 9_000_000),
		)
		fun replay() = inputs.runningFold(advance(i = input().copy(binding = null))) { r, i -> advance(r.state, i) }
		assertEquals(replay(), replay())
		val s = ready()
		val snapshot = s.copy()
		val r = advance(s, input(seq = 1, at = 5_000_000))
		assertEquals(snapshot, s)
		assertEquals(GuidanceReason.SAMPLE_ROLLBACK, r.teacherReason)
		val time = advance(s, input().copy(nowNanos = 3_000_000))
		assertEquals(GuidanceReason.CLOCK_ROLLBACK, time.learningReason)
		assertEquals(s, time.state)
		assertNull(time.fallbackCandidate)
	}

	@Test fun resolverOwnerMustBeFallbackAndFullNeverAcceptsFallbackOverride() {
		val s = ready()
		for (owner in listOf(RotationOwner.MAIN, RotationOwner.NONE)) {
			val r = advance(s, input(seq = 3, at = 5_000_000, modality = TrackingModality.NONE, owner = owner))
			assertNull(r.fallbackCandidate)
			assertEquals(GuidanceReason.OWNER_NOT_FALLBACK, r.applicationReason)
		}
		assertNull(advance(s, input(seq = 3, at = 5_000_000, owner = RotationOwner.FALLBACK)).fallbackCandidate)
	}

	@Test fun staleImuLetsExistingPolicySelectRotationOnlyMainAndNoCorrectedCandidate() {
		val b = binding()
		val s = ready()
		val i = input(b, 3, 200_000_000, modality = TrackingModality.ROTATION_ONLY, owner = RotationOwner.MAIN)
		val r = advance(s, i.copy(imu = imu(b, 2, 4_000_000)))
		assertEquals(RotationOwner.MAIN, r.ownership.rotationOwner)
		assertFalse(r.ownership.positionFromMain)
		assertNull(r.correctedImu)
		assertNull(r.fallbackCandidate)
		assertTrue(r.state.ready)
	}

	@Test fun trackingContinuesToLearnEachNewFullPair() {
		val s = ready()
		val desired = Quaternion.rotationAroundXAxis(.3f)
		val r = advance(s, input(seq = 3, at = 5_000_000, q = desired))
		assertEquals(GuidancePhase.TRACKING, r.state.phase)
		assertEquals(LearningDecision.ACCEPTED, r.learning)
		assertEquals(5_000_000, r.state.lastLearnedAtNanos)
		equivalent(desired, r.state.correction, .0001) // Exponential response retains a small finite residual.
		assertNull(r.fallbackCandidate)
	}

	@Test fun recoveryRequiresConfiguredCountOfNewPairsAfterDwell() {
		val t = tuning().copy(recoveryPairs = 2)
		val first = advance(t = t)
		val second = advance(first.state, input(seq = 2, at = 4_000_000), t)
		assertFalse(second.state.ready)
		assertEquals(1, second.state.goodRecoveryPairs)
		val repeated = advance(second.state, input(seq = 2, at = 4_000_000).copy(nowNanos = 5_000_000), t)
		assertFalse(repeated.state.ready)
		assertEquals(1, repeated.state.goodRecoveryPairs)
		val third = advance(repeated.state, input(seq = 3, at = 6_000_000), t)
		assertTrue(third.state.ready)
		assertEquals(2, third.state.goodRecoveryPairs)
	}

	@Test fun unusableFullOrImuAndOldTeacherCannotTeach() {
		val i = input()
		assertEquals(GuidanceReason.SAMPLE_UNUSABLE, advance(i = i.copy(imu = i.imu!!.copy(usable = false))).imuReason)
		assertEquals(GuidanceReason.SAMPLE_UNUSABLE, advance(i = i.copy(teacher = i.teacher!!.copy(rotationUsable = false))).teacherReason)
		assertEquals(GuidanceReason.POSITION_INVALID, advance(i = i.copy(teacher = i.teacher!!.copy(positionUsable = false))).teacherReason)
		val s = ready()
		val b = binding()
		val r = advance(s, GuidanceInput(200_000_000, b, teacher(b, 3, 5_000_000), imu(b, 4, 200_000_000), RotationOwner.FALLBACK))
		assertEquals(GuidanceReason.STALE, r.teacherReason)
		assertEquals(s.lastLearnedAtNanos, r.state.lastLearnedAtNanos)
		assertNotNull(r.fallbackCandidate)
	}

	@Test fun exactDtAndPairWindowLimitsAreInclusive() {
		val b = binding()
		val t = tuning(maxDt = 5_000_000)
		val first = advance(t = t)
		val r = advance(first.state, input(seq = 2, at = 6_000_000).copy(imu = imu(b, 2, 5_000_000)), t)
		assertEquals(LearningDecision.ACCEPTED, r.learning)
		assertTrue(r.state.ready)
	}

	@Test fun frameApprovalMustMatchExactIdentitiesEpochsAndSpaceBeforeRelearning() {
		val old = binding()
		val s = ready()
		val newImu = old.imu.copy(epoch = old.imu.epoch.copy(calibrationEpoch = "new-mount"))
		val changed = old.copy(imu = newImu)
		val blocked = advance(s, input(changed, 3, 5_000_000))
		assertEquals(GuidanceReason.FRAME_UNAPPROVED, blocked.learningReason)
		assertFalse(blocked.state.ready)
		val approved = changed.copy(frames = changed.frames.copy(approvedImu = newImu, approvalRevision = "approval-2"))
		val first = advance(blocked.state, input(approved, 4, 6_000_000))
		assertFalse(first.state.ready)
		val second = advance(first.state, input(approved, 5, 9_000_000))
		assertTrue(second.state.ready)
	}
}
