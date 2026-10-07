package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.*

class PositionCorrectionLearningLawTests {
	private val space = CoordinateSpace("synthetic-world", "rh_y_up_neg_z_forward", 7)
	private val epoch = PositionPredictionEpoch("hmd", "h:1", "hc:1", 1, "imu", "i:1", "ic:1", 1,
		"body", "b:1", "fixed", "f:1", space, 3)
	private val teacherEpoch = PositionTeacherEpoch("main", "m:1", "mc:1", 1, space, 3,
		PositionBodyReference.HIP_CENTER, MainTrackerMountCalibrationIdentity("mount", "mt:1"))
	private val zero = Vector3(0f, 0f, 0f)
	private val policy = PositionTemporalPairingPolicy(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
	// Synthetic test policy only. Every production constructor argument is required.
	private fun tuning() = PositionCorrectionTuning(1.0, 2.0, 10.0, 5.0, 10.0, 2.0, 2_000_000_000,
		5_000_000_000, 1.0, 10.0, 10.0, 1_000_000_000, 2, .001)
	private fun sample(sequence: Long = 1, at: Long = sequence * 1_000_000_000,
		error: Vector3 = Vector3(1f, 0f, 0f), hmd: Long = sequence, imu: Long = sequence,
		hmdAt: Long = at, imuAt: Long = at, evaluated: Long = maxOf(at, hmdAt, imuAt),
		invocation: Long = sequence, predictionEpoch: PositionPredictionEpoch = epoch,
		mainEpoch: PositionTeacherEpoch = teacherEpoch): PositionErrorSample {
		val prediction = PositionPrediction.available(TrackerPosition.HIP, zero, predictionEpoch.coordinateSpace,
			PositionBodyReference.HIP_CENTER, PositionPredictionProvenance(invocation, evaluated, minOf(hmdAt, imuAt),
				maxOf(hmdAt, imuAt), predictionEpoch, hmd, hmdAt, imu, imuAt),
			setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
				PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION))
		val teacher = MainHipCenterPositionTeacher(mainEpoch.sourceId, error, ObservationQuality.TRACKED,
			ObservationSampleProvenance(sequence, at, mainEpoch.sourceEpoch, mainEpoch.calibrationEpoch,
				mainEpoch.mappingRevision, mainEpoch.coordinateSpace), RawSourceIdentity(mainEpoch.sourceId, RawSourceKind.RAW_BACKEND),
			mainEpoch.mountCalibration)
		return assertIs<PositionErrorMeasurementResult.Measured>(PositionErrorMeasurement.evaluate(
			PositionCorrectionInput(teacher, prediction, predictionEpoch.coordinateSpace, predictionEpoch,
				predictionEpoch.assignmentGeneration, evaluated), mainEpoch, policy)).sample
	}
	private fun near(expected: Double, actual: Float, tolerance: Double = 2e-6) = assertEquals(expected, actual.toDouble(), tolerance)
	private fun norm(v: Vector3) = sqrt(v.x.toDouble()*v.x + v.y.toDouble()*v.y + v.z.toDouble()*v.z)
	private fun trained(t: PositionCorrectionTuning = tuning()): PositionCorrectionLearningLaw = PositionCorrectionLearningLaw(t).also {
		it.observe(sample()); assertEquals(PositionCorrectionPhase.TRACKING, it.observe(sample(2)).state.phase)
	}

	@Test fun firstSampleHasNoJumpAndSnapshotIsImmutableNumericalState() {
		val law = PositionCorrectionLearningLaw(tuning())
		val before = law.snapshot()
		val input = sample(error = Vector3(1f, 2f, 3f)); val copy = input.copy()
		val result = law.observe(input)
		assertEquals(PositionCorrectionLearningDecision.SEEDED, result.decision)
		assertEquals(PositionCorrectionPhase.REACQUIRING, result.state.phase)
		assertEquals(zero, result.state.correctionWorld)
		assertEquals(space, result.state.coordinateSpace)
		assertEquals(PositionCorrectionLearningLineage(teacherEpoch, epoch), result.state.lineage)
		assertEquals(input, copy)
		law.observe(sample(2))
		assertEquals(zero, before.correctionWorld); assertEquals(zero, result.state.correctionWorld)
	}

	@Test fun recoveryExponentialHasAnalyticalGoldenAndTeacherClock() {
		val law = PositionCorrectionLearningLaw(tuning())
		law.observe(sample(evaluated = 1_400_000_000))
		val result = law.observe(sample(2, evaluated = 2_900_000_000))
		near(1-exp(-.5), result.state.correctionWorld.x)
		assertEquals(2_000_000_000, result.state.lastAccepted!!.teacherSampleAtNanos)
	}

	@Test fun trackingTauDiffersAndShortHoldingResumesTrackingTau() {
		val law = trained(); val start = law.snapshot().correctionWorld.x.toDouble()
		law.advanceWithoutMeasurement(2_500_000_000, epoch)
		val result = law.observe(sample(3))
		assertEquals(PositionCorrectionPhase.TRACKING, result.state.phase)
		near(start+(1-start)*(1-exp(-1.0)), result.state.correctionWorld.x)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun stepAndRateBoundsPreserveThreeAxisDirection(which: Int) {
		val t = when(which) {
			0 -> tuning().copy(maxUpdateStepMeters = .1, maxCorrectionRateMetersPerSecond = 10.0)
			1 -> tuning().copy(maxUpdateStepMeters = 2.0, maxCorrectionRateMetersPerSecond = .05)
			else -> tuning().copy(maxUpdateStepMeters = .1, maxCorrectionRateMetersPerSecond = .05)
		}
		val e = Vector3(1f, 2f, 2f); val law = PositionCorrectionLearningLaw(t)
		law.observe(sample(error=e)); val c = law.observe(sample(2,error=e)).state.correctionWorld
		val limit = if(which==0) .1 else .05
		assertTrue(norm(c)<=limit); near(limit/3,c.x); near(limit*2/3,c.y); near(limit*2/3,c.z)
	}

	@Test fun absoluteSphereBoundIsNotComponentClamp() {
		val law = PositionCorrectionLearningLaw(tuning().copy(maxCorrectionMagnitudeMeters = .2))
		val e = Vector3(1f, 2f, 2f)
		law.observe(sample(error=e))
		for (i in 2L..12L) {
			val c=law.observe(sample(i,error=e)).state.correctionWorld
			assertTrue(norm(c)<=.2); near(.2/3,c.x); near(.4/3,c.y)
		}
	}

	@Test fun invocationOnlyChangeAndDuplicateTeacherCannotRelearnOrRefresh() {
		val law=trained(); val start=law.snapshot()
		val a=law.observe(sample(2,invocation=900, evaluated=3_000_000_000))
		assertEquals(PositionCorrectionLearningReason.DUPLICATE_PHYSICAL_PAIR,a.reason)
		assertEquals(start.correctionWorld,a.state.correctionWorld); assertEquals(start.lastAccepted,a.state.lastAccepted)
		val b=law.observe(sample(2,hmd=3,imu=3,hmdAt=3_000_000_000,imuAt=3_000_000_000,evaluated=3_000_000_000))
		assertEquals(PositionCorrectionLearningDecision.DUPLICATE,b.decision)
		assertEquals(start.lastAccepted,b.state.lastAccepted)
		assertEquals(3,b.state.lastObserved!!.hmdSequence)
	}

	@Test fun newTeacherWithUnchangedRawPairDoesNotLearnAndObservedStillProgresses() {
		val law=trained(); val start=law.snapshot()
		val result=law.observe(sample(3,hmd=2,imu=2,hmdAt=2_000_000_000,imuAt=2_000_000_000))
		assertEquals(PositionCorrectionLearningReason.PREDICTION_PHYSICAL_PAIR_NOT_ADVANCED,result.reason)
		assertEquals(start.lastAccepted,result.state.lastAccepted)
		assertEquals(3,result.state.lastObserved!!.teacherSequence)
		assertEquals(PositionCorrectionLearningDecision.DUPLICATE,law.observe(sample(3)).decision)
	}

	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun eitherRawSourceCanAdvanceAlone(hmdAdvances: Boolean) {
		val law=trained()
		val r=law.observe(sample(3,hmd=if(hmdAdvances)3 else 2,imu=if(hmdAdvances)2 else 3,
			hmdAt=if(hmdAdvances)3_000_000_000 else 2_000_000_000,imuAt=if(hmdAdvances)2_000_000_000 else 3_000_000_000))
		assertEquals(PositionCorrectionLearningDecision.UPDATED,r.decision)
	}

	@ParameterizedTest @ValueSource(ints = [0,1,2,3,4,5,6,7,8])
	fun rollbackAndPhysicalIdentityMismatchRejectWithoutRefreshing(which: Int) {
		val law=trained(); val before=law.snapshot(); val fresh=sample(3)
		val (bad,reason)=when(which) {
			0 -> fresh.copy(teacherSequence=1) to PositionCorrectionLearningReason.TEACHER_SEQUENCE_ROLLBACK
			1 -> fresh.copy(predictionHmdSequence=1) to PositionCorrectionLearningReason.HMD_SEQUENCE_ROLLBACK
			2 -> fresh.copy(predictionImuSequence=1) to PositionCorrectionLearningReason.IMU_SEQUENCE_ROLLBACK
			3 -> fresh.copy(teacherSequence=2) to PositionCorrectionLearningReason.TEACHER_SAMPLE_IDENTITY_MISMATCH
			4 -> fresh.copy(predictionHmdSequence=2) to PositionCorrectionLearningReason.HMD_SAMPLE_IDENTITY_MISMATCH
			5 -> fresh.copy(predictionImuSequence=2) to PositionCorrectionLearningReason.IMU_SAMPLE_IDENTITY_MISMATCH
			6 -> fresh.copy(teacherSampleAtNanos=2_000_000_000) to PositionCorrectionLearningReason.TEACHER_TIME_ROLLBACK
			7 -> sample(3,hmdAt=1_000_000_000) to PositionCorrectionLearningReason.HMD_TIME_ROLLBACK
			else -> sample(3,imuAt=1_000_000_000) to PositionCorrectionLearningReason.IMU_TIME_ROLLBACK
		}
		val r=law.observe(bad)
		assertEquals(reason,r.reason); assertEquals(before.lastAccepted,r.state.lastAccepted)
		assertEquals(before.correctionWorld,r.state.correctionWorld); assertEquals(0,r.state.recoveryGoodSamples)
	}

	@Test fun outlierStaysValidMeasurementButDoesNotRefreshAndReplayIsDuplicate() {
		val law=trained(); val before=law.snapshot()
		val outlier=sample(3,error=Vector3(100f,0f,0f))
		val r=law.observe(outlier)
		assertEquals(PositionCorrectionLearningReason.MEASUREMENT_RESIDUAL_OUTLIER,r.reason)
		assertEquals(before.lastAccepted,r.state.lastAccepted); assertEquals(3,r.state.lastObserved!!.teacherSequence)
		assertEquals(before.correctionWorld,r.state.correctionWorld)
		assertEquals(PositionCorrectionLearningDecision.DUPLICATE,law.observe(outlier).decision)
		val later=law.observe(outlier.copy(evaluatedAtNanos=8_000_000_000))
		assertEquals(PositionCorrectionPhase.DECAYING,later.state.phase)
		assertTrue(norm(later.state.correctionWorld)<norm(before.correctionWorld))
	}

	@Test fun observedOutlierDoesNotReplaceAcceptedTeacherLearningClock() {
		val t=tuning().copy(maxLearningDtNanos=4_000_000_000)
		val law=trained(t); val c=law.snapshot().correctionWorld.x.toDouble()
		law.observe(sample(3,error=Vector3(100f,0f,0f)))
		near(c+(1-c)*(1-exp(-2.0)),law.observe(sample(4)).state.correctionWorld.x)
	}

	@ParameterizedTest @ValueSource(booleans=[true,false])
	fun maximumLearningDtIsInclusiveAndLargerDtReseedsWithoutJump(boundary: Boolean) {
		val law=trained(); val before=law.snapshot().correctionWorld
		val r=law.observe(sample(3,at=4_000_000_000+if(boundary)0 else 1))
		assertEquals(if(boundary)PositionCorrectionLearningDecision.UPDATED else PositionCorrectionLearningDecision.SEEDED,r.decision)
		if(!boundary) { assertEquals(before,r.state.correctionWorld); assertEquals(PositionCorrectionPhase.REACQUIRING,r.state.phase)
			assertEquals(PositionCorrectionLearningReason.LEARNING_DT_TOO_LARGE,r.reason) }
	}

	@Test fun recoverySampleCountAndStableWindowBothRequiredWithInclusiveThreshold() {
		val law=PositionCorrectionLearningLaw(tuning().copy(recoveryResidualMeters=1.0,recoverySamples=3,recoveryStableNanos=3_000_000_000))
		law.observe(sample())
		assertEquals(PositionCorrectionPhase.REACQUIRING,law.observe(sample(2)).state.phase)
		assertEquals(PositionCorrectionPhase.REACQUIRING,law.observe(sample(3)).state.phase)
		assertEquals(PositionCorrectionPhase.TRACKING,law.observe(sample(4)).state.phase)
	}

	@Test fun seedNeverPromotesEvenWithSingleSampleZeroWindow() {
		val law=PositionCorrectionLearningLaw(tuning().copy(recoverySamples=1,recoveryStableNanos=0))
		assertEquals(PositionCorrectionPhase.REACQUIRING,law.observe(sample()).state.phase)
		assertEquals(PositionCorrectionPhase.TRACKING,law.observe(sample(2)).state.phase)
	}

	@Test fun remainingResidualGateAndFailureResetContinuousWindow() {
		val law=PositionCorrectionLearningLaw(tuning().copy(recoveryResidualMeters=.5,recoverySamples=2))
		assertEquals(0,law.observe(sample()).state.recoveryGoodSamples)
		assertEquals(0,law.observe(sample(2)).state.recoveryGoodSamples)
		assertEquals(1,law.observe(sample(3)).state.recoveryGoodSamples)
		law.observe(sample(4,error=Vector3(20f,0f,0f)))
		assertEquals(0,law.snapshot().recoveryGoodSamples)
		assertEquals(PositionCorrectionPhase.REACQUIRING,law.observe(sample(5)).state.phase)
		assertEquals(PositionCorrectionPhase.TRACKING,law.observe(sample(6)).state.phase)
	}

	@Test fun holdBoundaryInclusiveAndDecayUsesOnlyTimeAfterBoundary() {
		val law=trained(); val c=law.snapshot().correctionWorld
		val held=law.advanceWithoutMeasurement(7_000_000_000,epoch)
		assertEquals(PositionCorrectionPhase.HOLDING,held.state.phase); assertEquals(c,held.state.correctionWorld)
		val next=law.advanceWithoutMeasurement(7_000_000_001,epoch)
		assertEquals(PositionCorrectionPhase.DECAYING,next.state.phase)
		val decayed=law.advanceWithoutMeasurement(8_000_000_000,epoch)
		near(c.x.toDouble()*exp(-1.0),decayed.state.correctionWorld.x)
	}

	@Test fun zeroHoldIsLegalAndOneNanosecondLaterStartsDecay() {
		val t=tuning().copy(holdNanos=0)
		val law=PositionCorrectionLearningLaw(t); law.observe(sample());law.observe(sample(2))
		assertEquals(PositionCorrectionPhase.HOLDING,law.advanceWithoutMeasurement(2_000_000_000,epoch).state.phase)
		assertEquals(PositionCorrectionPhase.DECAYING,law.advanceWithoutMeasurement(2_000_000_001,epoch).state.phase)
	}

	@Test fun decayRateBoundAndIrregularTicksAreMonotonicWithoutOvershoot() {
		val law=trained(tuning().copy(maxDecayRateMetersPerSecond=.02,zeroEpsilonMeters=0.0))
		var previous=norm(law.snapshot().correctionWorld);var previousAt=7_000_000_000L
		for(now in listOf(7_100_000_000L,7_600_000_000L,8_000_000_000L,20_000_000_000L)) {
			val c=law.advanceWithoutMeasurement(now,epoch).state.correctionWorld;val current=norm(c)
			assertTrue(current<=previous); assertTrue(previous-current<=.02*(now-previousAt)/1e9+1e-7)
			assertTrue(c.x>=0);previous=current;previousAt=now
		}
	}

	@Test fun pureExponentialDecayIsIndependentOfTickPartition() {
		val a=trained();val b=trained()
		a.advanceWithoutMeasurement(9_000_000_000,epoch)
		for(now in listOf(7_200_000_000L,7_700_000_000L,8_100_000_000L,9_000_000_000L)) b.advanceWithoutMeasurement(now,epoch)
		near(a.snapshot().correctionWorld.x.toDouble(),b.snapshot().correctionWorld.x)
	}

	@Test fun expirationSnapsExactZeroAndReturnUsesRecoveryTauWithoutSnap() {
		val law=trained(tuning().copy(maxLearningDtNanos=20_000_000_000))
		assertEquals(PositionCorrectionPhase.EXPIRED,law.advanceWithoutMeasurement(20_000_000_000,epoch).state.phase)
		assertEquals(zero,law.snapshot().correctionWorld)
		val returned=law.observe(sample(3,at=20_000_000_000))
		assertEquals(PositionCorrectionPhase.REACQUIRING,returned.state.phase)
		assertTrue(returned.state.correctionWorld.x<1f)
	}

	@Test fun lossReacquisitionSequenceAndPostDecayRecoveryTau() {
		val t=tuning().copy(holdNanos=1_000_000_000,maxLearningDtNanos=5_000_000_000,recoveryStableNanos=1_000_000_000)
		val law=trained(t)
		assertEquals(PositionCorrectionPhase.HOLDING,law.advanceWithoutMeasurement(3_000_000_000,epoch).state.phase)
		assertEquals(PositionCorrectionPhase.DECAYING,law.advanceWithoutMeasurement(4_000_000_000,epoch).state.phase)
		val start=law.snapshot().correctionWorld.x.toDouble()
		val returned=law.observe(sample(3,at=4_000_000_000))
		assertEquals(PositionCorrectionPhase.REACQUIRING,returned.state.phase)
		near(start+(1-start)*(1-exp(-1.0)),returned.state.correctionWorld.x)
		assertEquals(PositionCorrectionPhase.TRACKING,law.observe(sample(4,at=5_000_000_000)).state.phase)
	}

	@Test fun noGapTickStillRequiresRecoveryAfterLongPhysicalGap() {
		val law=trained(tuning().copy(holdNanos=1_000_000_000,maxLearningDtNanos=5_000_000_000))
		assertEquals(PositionCorrectionPhase.REACQUIRING,law.observe(sample(3,at=4_000_000_000)).state.phase)
	}

	@Test fun gapWithoutAcceptedSampleDoesNotInventDecayingState() {
		val law=PositionCorrectionLearningLaw(tuning())
		assertEquals(PositionCorrectionPhase.UNINITIALIZED,law.advanceWithoutMeasurement(10,epoch).state.phase)
		assertEquals(zero,law.snapshot().correctionWorld)
	}

	@Test fun timeRollbackRejectsWithoutChangingSnapshot() {
		val law=trained(); val before=law.snapshot()
		assertEquals(PositionCorrectionLearningReason.STATE_TIME_ROLLBACK,law.advanceWithoutMeasurement(-1,epoch).reason)
		assertEquals(before,law.snapshot())
		assertEquals(PositionCorrectionLearningReason.STATE_TIME_ROLLBACK,law.advanceWithoutMeasurement(0,epoch).reason)
		assertEquals(before,law.snapshot())
		assertEquals(PositionCorrectionLearningReason.STATE_TIME_ROLLBACK,law.observe(sample(3).copy(evaluatedAtNanos=1)).reason)
		assertEquals(before,law.snapshot())
	}

	@ParameterizedTest @ValueSource(ints=[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15])
	fun everyPredictionEpochComponentHardInvalidatesOnMeasurementAndLoss(which: Int) {
		val next=when(which) {
			0->epoch.copy(hmdSourceId="h2");1->epoch.copy(hmdSourceEpoch="h2");2->epoch.copy(hmdCalibrationEpoch="h2")
			3->epoch.copy(hmdMappingRevision=2);4->epoch.copy(imuSourceId="i2");5->epoch.copy(imuSourceEpoch="i2")
			6->epoch.copy(imuCalibrationEpoch="i2");7->epoch.copy(imuMappingRevision=null)
			8->epoch.copy(bodyModelId="b2");9->epoch.copy(bodyModelEpoch="b2");10->epoch.copy(fixedCalibrationId="f2")
			11->epoch.copy(fixedCalibrationEpoch="f2");12->epoch.copy(coordinateSpace=space.copy(revision=8))
			13->epoch.copy(coordinateSpace=space.copy(id="new"));14->epoch.copy(coordinateSpace=space.copy(convention="new"))
			else->epoch.copy(assignmentGeneration=4)
		}
		val gap=trained().advanceWithoutMeasurement(3_000_000_000,next)
		assertEquals(PositionCorrectionLearningDecision.INVALIDATED,gap.decision); assertEquals(zero,gap.state.correctionWorld)
		assertNull(gap.state.lineage);assertNull(gap.state.lastAccepted)
		val main=teacherEpoch.copy(coordinateSpace=next.coordinateSpace,assignmentGeneration=next.assignmentGeneration)
		val measurement=trained().observe(sample(3,predictionEpoch=next,mainEpoch=main))
		assertEquals(PositionCorrectionLearningReason.PREDICTION_EPOCH_CHANGED,measurement.reason)
		assertEquals(zero,measurement.state.correctionWorld);assertEquals(PositionCorrectionPhase.REACQUIRING,measurement.state.phase)
	}

	@ParameterizedTest @ValueSource(ints=[0,1,2,3,4])
	fun everyTeacherEpochChangeHardInvalidatesEvenForIdenticalNumericError(which: Int) {
		val next=when(which) {
			0->teacherEpoch.copy(sourceId="new");1->teacherEpoch.copy(sourceEpoch="new");2->teacherEpoch.copy(calibrationEpoch="new")
			3->teacherEpoch.copy(mappingRevision=null);else->teacherEpoch.copy(mountCalibration=MainTrackerMountCalibrationIdentity("mount","new"))
		}
		val result=trained().observe(sample(3,mainEpoch=next))
		assertEquals(PositionCorrectionLearningReason.TEACHER_EPOCH_CHANGED,result.reason)
		assertEquals(zero,result.state.correctionWorld);assertEquals(next,result.state.lineage!!.teacherEpoch)
	}

	@Test fun unavailablePredictionContextInvalidatesEvenOnClockRollback() {
		val result=trained().advanceWithoutMeasurement(0,null)
		assertEquals(PositionCorrectionLearningReason.PREDICTION_CONTEXT_UNAVAILABLE,result.reason)
		assertEquals(zero,result.state.correctionWorld);assertNull(result.state.lastObserved);assertNull(result.state.lastStateAdvanceAtNanos)
	}

	@Test fun longBoundaryTimesDoNotOverflowHoldOrLearningDt() {
		val t=tuning().copy(maxLearningDtNanos=Long.MAX_VALUE,holdNanos=Long.MAX_VALUE,maxCorrectionRateMetersPerSecond=.01)
		val law=PositionCorrectionLearningLaw(t)
		law.observe(sample(1,at=0)); assertEquals(PositionCorrectionLearningDecision.UPDATED,law.observe(sample(2,at=Long.MAX_VALUE)).decision)
		assertEquals(PositionCorrectionPhase.HOLDING,law.advanceWithoutMeasurement(Long.MAX_VALUE,epoch).state.phase)
		val b=PositionCorrectionLearningLaw(tuning().copy(holdNanos=10))
		b.observe(sample(1,at=Long.MAX_VALUE-5))
		assertEquals(PositionCorrectionPhase.HOLDING,b.advanceWithoutMeasurement(Long.MAX_VALUE,epoch).state.phase)
	}

	@Test fun extremeFiniteMeasurementUsesSafeDoubleNormAndRemainsBounded() {
		val t=tuning().copy(maxResidualMeters=1e40,maxUpdateStepMeters=.1,maxCorrectionMagnitudeMeters=.2)
		val law=PositionCorrectionLearningLaw(t);val e=Vector3(Float.MAX_VALUE,-Float.MAX_VALUE,Float.MAX_VALUE)
		law.observe(sample(error=e));val r=law.observe(sample(2,error=e))
		assertEquals(PositionCorrectionLearningDecision.UPDATED,r.decision)
		assertTrue(norm(r.state.correctionWorld)<=.1)
		near(.1/sqrt(3.0),r.state.correctionWorld.x);near(-.1/sqrt(3.0),r.state.correctionWorld.y)
	}

	@ParameterizedTest @ValueSource(floats=[Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY])
	fun nonfiniteMeasurementFailsClosed(bad: Float) {
		val law=trained();val c=law.snapshot().correctionWorld
		val r=law.observe(sample(3).copy(errorWorld=Vector3(bad,0f,0f)))
		assertEquals(PositionCorrectionLearningReason.NUMERIC_INVALID,r.reason);assertEquals(c,r.state.correctionWorld)
	}

	@Test fun nonfiniteRateProductFailsClosedInLearningAndDecay() {
		val law=trained(tuning().copy(maxCorrectionRateMetersPerSecond=Double.MAX_VALUE))
		val before=law.snapshot().lastAccepted
		val r=law.observe(sample(3,at=4_000_000_000))
		assertEquals(PositionCorrectionLearningReason.NUMERIC_INVALID,r.reason);assertEquals(before,r.state.lastAccepted)
		val decay=trained(tuning().copy(maxDecayRateMetersPerSecond=Double.MAX_VALUE))
		val c=decay.snapshot().correctionWorld
		assertEquals(PositionCorrectionLearningReason.NUMERIC_INVALID,decay.advanceWithoutMeasurement(10_000_000_000,epoch).reason)
		assertEquals(c,decay.snapshot().correctionWorld)
	}

	@Test fun deterministicReplayWithMixedAcceptedRejectedGapAndContextEvents() {
		val a=PositionCorrectionLearningLaw(tuning());val b=PositionCorrectionLearningLaw(tuning())
		for(i in 1L..100L) {
			val e=if(i%7==0L)Vector3(20f,0f,0f) else Vector3(.3f,.4f,-.2f)
			val s=sample(i,error=e)
			assertEquals(a.observe(s),b.observe(s))
			if(i%3==0L)assertEquals(a.advanceWithoutMeasurement(s.evaluatedAtNanos+10,epoch),b.advanceWithoutMeasurement(s.evaluatedAtNanos+10,epoch))
			val c=a.snapshot().correctionWorld; assertTrue(listOf(c.x,c.y,c.z).all{it.isFinite()});assertTrue(norm(c)<=tuning().maxCorrectionMagnitudeMeters)
		}
		assertEquals(a.advanceWithoutMeasurement(200_000_000_000,null),b.advanceWithoutMeasurement(200_000_000_000,null))
	}

	@Test fun lawHasOnlyMeasurementAndGapApisAndNoRuntimeOrClockDependencies() {
		val forbidden=setOf("Tracker","VRServer","HumanSkeleton","HumanPoseManager","PoseObservation","PositionCorrectionInput")
		assertTrue(PositionCorrectionLearningLaw::class.java.declaredFields.none{it.type.simpleName in forbidden})
		assertEquals(listOf(PositionErrorSample::class.java),PositionCorrectionLearningLaw::class.java.declaredMethods.single{it.name=="observe"}.parameterTypes.toList())
		val path=Path.of("src/main/java/dev/monaka/tracking/PositionCorrectionLearningLaw.kt")
		val source=Files.readString(path)
		for(clock in listOf("System.nanoTime", "System.currentTimeMillis", "Instant.now"))assertFalse(source.contains(clock))
		assertFalse(PoseObservation::class.java.isAssignableFrom(PositionCorrectionStateSnapshot::class.java))
	}

	@Test fun tuningRejectsEveryInvalidFieldWithoutDefaults() {
		val t=tuning()
		for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,0.0,-1.0)) {
			for(change in listOf<(Double)->PositionCorrectionTuning>(
				{t.copy(trackingTauSeconds=it)},{t.copy(recoveryTauSeconds=it)},{t.copy(decayTauSeconds=it)},
				{t.copy(maxResidualMeters=it)},{t.copy(maxCorrectionMagnitudeMeters=it)},
				{t.copy(maxCorrectionRateMetersPerSecond=it)},{t.copy(maxUpdateStepMeters=it)},{t.copy(maxDecayRateMetersPerSecond=it)}))
				assertFailsWith<IllegalArgumentException>{change(bad)}
		}
		for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,11.0))assertFailsWith<IllegalArgumentException>{t.copy(recoveryResidualMeters=bad)}
		for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,6.0))assertFailsWith<IllegalArgumentException>{t.copy(zeroEpsilonMeters=bad)}
		assertFailsWith<IllegalArgumentException>{t.copy(maxLearningDtNanos=0)}
		assertFailsWith<IllegalArgumentException>{t.copy(holdNanos=-1)}
		assertFailsWith<IllegalArgumentException>{t.copy(recoveryStableNanos=-1)}
		assertFailsWith<IllegalArgumentException>{t.copy(recoverySamples=0)}
		assertNotNull(t.copy(holdNanos=0,recoveryStableNanos=0,recoveryResidualMeters=0.0,zeroEpsilonMeters=0.0))
		assertEquals(1,PositionCorrectionTuning::class.java.declaredConstructors.size)
	}
}
