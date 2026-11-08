package dev.monaka.tracking

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKConstraint
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

/** Actual owned writeback, ordinary user reset entry points and actual IKConstraint.
 * No producer/runtime/device operations or alternative solver implementation.
 */
class NormalizedFullBodyMountTests {
	private val left = TrackerPosition.LEFT_UPPER_LEG
	private val right = TrackerPosition.RIGHT_UPPER_LEG
	private val a = TrackerReference.mtp(LogicalTracker("source", "main-a", "publisher"))
	private val b = TrackerReference.mtp(LogicalTracker("source", "main-b", "publisher"))
	private fun closeRotation(expected: Quaternion, actual: Quaternion) {
		val e = expected.unit(); val x = actual.unit()
		assertTrue(abs(e.w * x.w + e.x * x.x + e.y * x.y + e.z * x.z) > .99999f, "expected=$expected actual=$actual")
	}
	private fun closePosition(expected: Vector3, actual: Vector3) = assertTrue((expected - actual).len() < .00001f, "expected=$expected actual=$actual")
	private inner class Fixture(val target: TrackerPosition = left) : AutoCloseable {
		val head = Tracker(null, 1, "raw-head", trackerPosition = TrackerPosition.HEAD, hasPosition = true, hasRotation = true,
			isHmd = true, isComputed = true, trackRotDirection = false).also {
			it.position = Vector3(0f, 1.7f, 0f); it.setRotation(Quaternion.IDENTITY); it.status = TrackerStatus.OK
		}
		val manager = HumanPoseManager(listOf(head)).also { it.setLegTweaksEnabled(false) }
		val assignments = TrackerBodyAssignments()
		val writeback = ConstraintIkWriteback(manager.skeleton)
		var now = 1_000_000_000L
		val rawPosition = Vector3(.29f, .81f, -.17f)
		init { manager.skeleton.updatePose() }
		fun select(q: TrackerReference? = a, six: TrackerReference? = a, body: TrackerPosition = target) {
			assignments.replaceTargets(emptyMap())
			assignments.configureSources(body, q, six, true)
		}
		fun input(raw: Quaternion, position: Vector3? = rawPosition, owner: TrackerReference = a, body: TrackerPosition = target): EffectiveConstraint {
			now += 10_000_000L
			return EffectiveConstraint(body,
				position?.let { ResolvedComponent(it, owner.observationId, ObservationQuality.TRACKED, now) },
				ResolvedComponent(raw, owner.observationId, ObservationQuality.TRACKED, now))
		}
		fun apply(value: EffectiveConstraint) = writeback.apply(mapOf(value.target to value), assignments.snapshot())
		fun proxy(body: TrackerPosition = target): Tracker = requireNotNull(when (body) {
			left -> manager.skeleton.leftUpperLegTracker
			right -> manager.skeleton.rightUpperLegTracker
			TrackerPosition.HIP -> manager.skeleton.hipTracker
			else -> error("unsupported body")
		})
		fun calibration(): IKConstraint.Calibration = requireNotNull(manager.skeleton.ikSolver.calibrationSnapshot()[proxy().name to target.bodyPart])
		fun point(): Vector3 = requireNotNull(writeback.effectivePositionTargetSnapshot(target))
		fun fullReset() = manager.resetTrackersFull("owned FULL body mount")
		override fun close() { writeback.close() }
	}

	@Test fun twentyAndNinetyDegreeAttachmentsAreRightMountsAndKeepRawPositionAnchors() {
		for (degrees in listOf(20f, 90f)) Fixture().use { f ->
			f.select()
			val attachment = Quaternion.rotationAroundYAxis(degrees * .01745329252f)
			val original = f.input(attachment)
			f.apply(original)
			val proxy = f.proxy()
			assertFalse(proxy.allowReset); assertFalse(proxy.allowMounting)
			f.fullReset()
			closeRotation(Quaternion.IDENTITY, proxy.getRotation())
			closeRotation(attachment, requireNotNull(proxy.positionAnchorRotation))
			assertEquals(original.rotation!!.value, attachment); assertEquals(original.position!!.value, f.rawPosition)
			f.manager.skeleton.updatePose()
			val calibration = f.calibration()
			assertTrue(calibration.offset.len() > .01f, "fixture must exercise a nonzero anatomical lever arm")
			val body = (Quaternion.rotationAroundXAxis(.31f) * Quaternion.rotationAroundYAxis(-.23f)).unit()
			val raw = body * attachment
			val moved = f.rawPosition + Vector3(.05f, -.02f, .01f)
			f.apply(f.input(raw, moved))
			closeRotation(body, f.proxy().getRotation())
			closeRotation(raw, requireNotNull(f.proxy().positionAnchorRotation))
			closePosition(moved + (raw * calibration.rotationOffset).sandwich(calibration.offset), f.point())
			closePosition(moved, f.proxy().position)
		}
	}

	@Test fun mountingReinterpretationChangesBodyRotationWithoutOrbitingExistingPositionOffset() = Fixture().use { f ->
		f.select(); val attachment = Quaternion.rotationAroundYAxis(1.0471976f)
		f.apply(f.input(attachment)); f.fullReset(); f.manager.skeleton.updatePose()
		val lean = Quaternion.rotationAroundXAxis(.52f)
		val raw = lean * attachment
		f.apply(f.input(raw))
		val beforeCalibration = f.calibration(); assertTrue(beforeCalibration.offset.len() > .01f)
		val beforePoint = f.point(); val beforeBody = f.proxy().getRotation()
		val beforeRaw = requireNotNull(f.proxy().positionAnchorRotation)
		// A different explicit reference reinterprets anatomy at the same raw p/q.
		// This deliberately changes M, unlike an ordinary unchanged-mount lean.
		f.head.setRotation(Quaternion.rotationAroundYAxis(.7f))
		f.manager.resetTrackersMounting("different consumer reference", listOf(f.target.bodyPart))
		val afterBody = f.proxy().getRotation()
		val dot = abs(beforeBody.w * afterBody.w + beforeBody.x * afterBody.x + beforeBody.y * afterBody.y + beforeBody.z * afterBody.z)
		assertTrue(dot < .999f, "mount must actually change the body rotation")
		assertEquals(beforeCalibration, f.calibration(), "Mount Reset must not recapture position in a bent pose")
		closeRotation(beforeRaw, requireNotNull(f.proxy().positionAnchorRotation))
		closePosition(f.rawPosition, f.proxy().position)
		closePosition(beforePoint, f.point())
	}

	@Test fun samePublicMainBodySurvivesQSixLossAndReappearanceWithoutLearningFromAnotherFallback() = Fixture().use { f ->
		val attachment = Quaternion.rotationAroundYAxis(1.5707964f)
		f.select(q = a, six = null); f.apply(f.input(attachment, null))
		val qOnly = f.proxy(); assertTrue(qOnly.allowReset)
		f.fullReset(); closeRotation(Quaternion.IDENTITY, qOnly.getRotation())
		// The very same physical reference gains position: keep its actual reset anchor.
		f.assignments.configureSources(f.target, a, a, true)
		f.apply(f.input(attachment)); closeRotation(Quaternion.IDENTITY, f.proxy().getRotation())
		assertFalse(f.proxy().allowReset)
		f.assignments.configureSources(f.target, b, a, true)
		val epoch = qOnly.resetsHandler.correctionCalibrationEpoch()
		val fallback = Quaternion.rotationAroundXAxis(-.4f)
		f.apply(f.input(fallback, null, b)); f.fullReset()
		assertEquals(epoch, qOnly.resetsHandler.correctionCalibrationEpoch(), "independent fallback cannot recalibrate Main")
		closeRotation(fallback, f.proxy().getRotation())
		f.writeback.apply(emptyMap(), f.assignments.snapshot()); f.fullReset()
		assertEquals(epoch, qOnly.resetsHandler.correctionCalibrationEpoch(), "loss cannot calibrate a held Main")
		f.apply(f.input(attachment)); closeRotation(Quaternion.IDENTITY, f.proxy().getRotation())
		f.assignments.configureSources(f.target, a, null, true); f.apply(f.input(attachment, null))
		assertSame(qOnly, f.proxy()); closeRotation(Quaternion.IDENTITY, f.proxy().getRotation())
		// A different Main must start with Identity interpretation.
		f.assignments.configureSources(f.target, b, b, true); f.apply(f.input(attachment, owner = b))
		closeRotation(attachment, f.proxy().getRotation())
		// Moving the original reference to another body does not transfer its mount.
		f.select(q = a, six = a, body = right); f.apply(f.input(attachment, body = right))
		closeRotation(attachment, f.proxy(right).getRotation())
	}

	@Test fun legacyInputsRetainNullOverrideAndOrdinaryIKConstraintRotation() = Fixture().use { f ->
		val legacy = TrackerReference.slime("already-adjusted")
		f.assignments.configure(f.target, legacy)
		val q = Quaternion.rotationAroundYAxis(.8f)
		f.apply(f.input(q, owner = legacy))
		assertNull(f.proxy().positionAnchorRotation)
		val direct = IKConstraint(f.proxy()); val node = f.rawPosition + Vector3(.1f, .2f, -.1f)
		direct.reset(node); closePosition(node, direct.getPosition())
		f.fullReset(); assertNull(f.proxy().positionAnchorRotation); closeRotation(q, f.proxy().getRotation())
	}

	private fun resetGeneration(tracker: Tracker): Long = tracker.resetsHandler
		.diagnosticResetState(false).getValue("correction_reset_generation").toString().toLong()

	@Test fun qOnlyOrdinaryResetRunsOnceAndCloseDetachesTheOwnedCallback() = Fixture().use { f ->
		f.select(q = a, six = null)
		val raw = Quaternion.rotationAroundYAxis(1.0471976f)
		f.apply(f.input(raw, null))
		val helper = f.proxy()
		val initial = resetGeneration(helper)
		f.fullReset()
		assertEquals(initial + 1, resetGeneration(helper), "Q-only loop must not also reset through callback")
		f.apply(f.input(Quaternion.rotationAroundXAxis(.52f) * raw, null))
		f.manager.resetTrackersMounting("ordinary Q-only mount", listOf(f.target.bodyPart))
		assertEquals(initial + 2, resetGeneration(helper), "Q-only Mount Reset must run exactly once")
		val beforeClose = helper.resetsHandler.correctionCalibrationEpoch()
		f.writeback.close()
		assertNull(f.manager.skeleton.leftUpperLegTracker)
		f.fullReset()
		f.manager.resetTrackersMounting("closed callback", listOf(f.target.bodyPart))
		assertEquals(beforeClose, helper.resetsHandler.correctionCalibrationEpoch(), "closed owner must never receive later reset events")
	}

	@Test fun resetCaptureRequiresCurrentUsableMatchingBodySample() = Fixture().use { f ->
		val raw = Quaternion.rotationAroundYAxis(1.0471976f)
		f.select(q = a, six = null); f.apply(f.input(raw, null)); f.fullReset()
		val helper = f.proxy()
		f.assignments.configureSources(f.target, a, a, true); f.apply(f.input(raw))
		val epoch = helper.resetsHandler.correctionCalibrationEpoch()
		f.manager.resetTrackersMounting("different body only", listOf(right.bodyPart))
		assertEquals(epoch, helper.resetsHandler.correctionCalibrationEpoch())
		for (quality in listOf(ObservationQuality.STALE, ObservationQuality.LOST, ObservationQuality.UNAVAILABLE)) {
			val invalid = f.input(Quaternion.rotationAroundXAxis(.4f) * raw)
			f.apply(invalid.copy(rotation = invalid.rotation!!.copy(quality = quality)))
			f.fullReset()
			assertEquals(epoch, helper.resetsHandler.correctionCalibrationEpoch(), "cannot capture $quality")
		}
		f.apply(f.input(Quaternion(Float.NaN, 0f, 0f, 0f))); f.fullReset()
		assertEquals(epoch, helper.resetsHandler.correctionCalibrationEpoch(), "rejected quaternion cannot calibrate a held helper")
		f.apply(f.input(raw)); closeRotation(Quaternion.IDENTITY, f.proxy().getRotation())
		f.manager.skeleton.setPauseTracking(true, "core reset guard")
		f.apply(f.input(Quaternion.rotationAroundXAxis(.4f) * raw)); f.fullReset()
		assertEquals(epoch, helper.resetsHandler.correctionCalibrationEpoch(), "paused input cannot recalibrate")
		f.manager.skeleton.setPauseTracking(false, "core reset guard")
		f.apply(f.input(raw)); closeRotation(Quaternion.IDENTITY, f.proxy().getRotation())
	}

	@Test fun typedHipProjectionAndPrecompensationUseTheRawAnchorAfterBodyMount() = Fixture(TrackerPosition.HIP).use { f ->
		f.select(); val attachment = Quaternion.rotationAroundYAxis(1.5707964f)
		f.apply(f.input(attachment)); f.fullReset(); f.manager.skeleton.updatePose()
		val calibration = f.calibration()
		assertTrue(calibration.offset.len() > .01f)
		val body = Quaternion.rotationAroundXAxis(.37f) * Quaternion.rotationAroundYAxis(.23f)
		val rawRotation = body * attachment
		val raw = f.input(rawRotation, f.rawPosition + Vector3(.04f, -.01f, .03f))
		f.apply(raw)
		closeRotation(body, f.proxy().getRotation())
		val projected = assertIs<MainEffectiveHipTargetResult.Available>(
			f.writeback.projectMainEffectiveHipTarget(raw, f.assignments.snapshot(), f.now),
		).target
		closePosition(f.point(), projected.position)
		val desired = projected.position + Vector3(.02f, .03f, -.01f)
		val typed = SolverEffectiveConstraint(f.target,
			raw.position!!.copy(value = desired), raw.rotation, SolverPositionReference.IK_EFFECTIVE_TARGET)
		assertEquals(ConstraintIkWriteback.ApplyResult.Applied,
			f.writeback.applySolver(mapOf(f.target to typed), f.assignments.snapshot())[f.target])
		closePosition(desired, f.point())
		closeRotation(body, f.proxy().getRotation())
		closeRotation(rawRotation, requireNotNull(f.proxy().positionAnchorRotation))
		assertEquals(calibration, f.calibration(), "typed correction must retain the same raw-anchor calibration")
	}

	@Test fun onlyCurrentUsablePairedMainKneesProtectTheirSolvedMeasuredPositions() {
		for (target in listOf(left, right)) Fixture(target).use { f ->
			f.select(); val raw = Quaternion.rotationAroundYAxis(.8f)
			fun fresh(): Tracker {
				f.apply(f.input(raw))
				return f.proxy().also { assertTrue(it.preserveMeasuredPosition, "current full Main $target") }
			}
			var previous = fresh()
			f.apply(f.input(raw, null))
			assertFalse(previous.preserveMeasuredPosition, "old full proxy clears on rotation-only frame")
			assertFalse(f.proxy().preserveMeasuredPosition)
			f.assignments.configureSources(target, a, null, true)
			f.apply(f.input(raw, null)); assertFalse(f.proxy().preserveMeasuredPosition, "explicit Q-only selection")
			f.assignments.configureSources(target, a, a, true)
			previous = fresh()
			f.writeback.apply(emptyMap(), f.assignments.snapshot())
			assertFalse(previous.preserveMeasuredPosition, "loss clears even a detached old proxy")
			previous = fresh()
			f.assignments.configureSources(target, b, a, true)
			f.apply(f.input(raw, null, b))
			assertFalse(previous.preserveMeasuredPosition)
			assertFalse(f.proxy().preserveMeasuredPosition, "independent Q fallback is not a measured position")
			f.assignments.configureSources(target, a, a, true)
			for (quality in listOf(ObservationQuality.STALE, ObservationQuality.LOST, ObservationQuality.UNAVAILABLE)) {
				fresh()
				val input = f.input(raw)
				f.apply(input.copy(position = input.position!!.copy(quality = quality)))
				assertFalse(f.proxy().preserveMeasuredPosition, "unusable position $quality")
				fresh()
				f.apply(input.copy(rotation = input.rotation!!.copy(quality = quality)))
				assertFalse(f.proxy().preserveMeasuredPosition, "unusable rotation $quality")
			}
			for (differentPosition in listOf(true, false)) {
				fresh(); val input = f.input(raw)
				f.apply(if (differentPosition) input.copy(position = input.position!!.copy(sourceId = b.observationId))
					else input.copy(rotation = input.rotation!!.copy(sourceId = b.observationId)))
				assertFalse(f.proxy().preserveMeasuredPosition, "position and rotation must both belong to Main")
			}
			previous = fresh()
			val input = f.input(raw)
			f.writeback.applySolver(mapOf(target to SolverEffectiveConstraint(target, input.position, input.rotation,
				SolverPositionReference.IK_EFFECTIVE_TARGET)), f.assignments.snapshot())
			assertFalse(f.proxy().preserveMeasuredPosition, "derived effective target must keep ordinary post-processing")
			previous = fresh()
			f.writeback.applySolver(mapOf(target to SolverEffectiveConstraint(target, input.position, input.rotation,
				SolverPositionReference.NONE)), f.assignments.snapshot())
			assertFalse(previous.preserveMeasuredPosition, "unknown positional semantics reject and clear old metadata")
			previous = fresh()
			f.apply(f.input(raw, Vector3(Float.NaN, 0f, 0f)))
			assertFalse(previous.preserveMeasuredPosition, "nonfinite position cannot preserve a prior flag")
			previous = fresh()
			f.apply(f.input(Quaternion(Float.NaN, 0f, 0f, 0f)))
			assertFalse(previous.preserveMeasuredPosition, "nonfinite rotation cannot preserve a prior flag")
			previous = fresh()
			f.manager.skeleton.ikSolver.enabled = false; f.apply(f.input(raw))
			assertFalse(previous.preserveMeasuredPosition, "disabled IK cannot claim a solved measured knee")
			f.manager.skeleton.ikSolver.enabled = true
			previous = fresh()
			f.manager.skeleton.setPauseTracking(true, "measured knee metadata")
			f.apply(f.input(raw))
			assertFalse(previous.preserveMeasuredPosition, "clear must precede paused early return")
			f.manager.skeleton.setPauseTracking(false, "measured knee metadata")
			previous = fresh()
			f.assignments.configure(target, a, useAsIkConstraint = false)
			f.apply(f.input(raw))
			assertFalse(previous.preserveMeasuredPosition, "non-IK assignment cannot retain protection")
		}
	}

	@Test fun measuredPositionFlagDefaultsOffForLegacyAndNonKneeTargets() {
		Fixture().use { f ->
			assertFalse(f.head.preserveMeasuredPosition)
			val legacy = TrackerReference.slime("ordinary-position-input")
			f.assignments.configure(f.target, legacy)
			f.apply(f.input(Quaternion.IDENTITY, owner = legacy))
			assertFalse(f.proxy().preserveMeasuredPosition, "legacy inputs keep their existing post-processing")
		}
		Fixture(TrackerPosition.HIP).use { f ->
			f.select(); f.apply(f.input(Quaternion.IDENTITY))
			assertFalse(f.proxy().preserveMeasuredPosition, "HIP is not a measured knee")
		}
	}
}
