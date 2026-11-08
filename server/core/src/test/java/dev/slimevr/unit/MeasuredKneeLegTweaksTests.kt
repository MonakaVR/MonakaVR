package dev.slimevr.unit

import dev.monaka.tracking.*
import dev.slimevr.config.LegTweaksConfig
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class MeasuredKneeLegTweaksTests {
	private class Fixture : AutoCloseable {
		private val left = TrackerPosition.LEFT_UPPER_LEG
		private val right = TrackerPosition.RIGHT_UPPER_LEG
		private val a = TrackerReference.mtp(LogicalTracker("source", "left", "publisher"))
		private val b = TrackerReference.mtp(LogicalTracker("source", "right", "publisher"))
		private val head = Tracker(null, 1, "raw-head", trackerPosition = TrackerPosition.HEAD,
			hasPosition = true, hasRotation = true, isHmd = true, isComputed = true,
			allowReset = false, allowMounting = false, trackRotDirection = false).also {
			it.position = Vector3(0f, 1.7f, 0f); it.setRotation(Quaternion.IDENTITY); it.status = TrackerStatus.OK
		}
		val manager = HumanPoseManager(listOf(head))
		val skeleton get() = manager.skeleton
		val assignments = TrackerBodyAssignments()
		val writeback = ConstraintIkWriteback(skeleton)
		private val sensorPosition: Vector3
		init {
			skeleton.legTweaks.setConfig(LegTweaksConfig())
			manager.update()
			sensorPosition = skeleton.leftUpperLegBone.getTailPosition() + Vector3(0f, .14f, -.025f)
			apply(true)
			manager.resetTrackersFull("measured-knee postprocessing fixture")
			manager.update()
		}
		fun apply(measured: Boolean) {
			assignments.configureSources(left, a, a.takeIf { measured }, true)
			assignments.configureSources(right, b, null, true)
			fun q(ref: TrackerReference) = ResolvedComponent(Quaternion.IDENTITY, ref.observationId, ObservationQuality.TRACKED, 1L)
			writeback.apply(mapOf(
				left to EffectiveConstraint(left, sensorPosition.takeIf { measured }?.let {
					ResolvedComponent(it, a.observationId, ObservationQuality.TRACKED, 1L)
				}, q(a)),
				right to EffectiveConstraint(right, null, q(b)),
			), assignments.snapshot())
		}
		override fun close() = writeback.close()
	}

	@Test fun measuredSolvedKneeAndBufferStayExactWhileOtherSideKeepsFloorCorrection() = Fixture().use { f ->
		assertTrue(f.skeleton.legTweaks.enabled)
		assertTrue(f.skeleton.legTweaks.floorClipEnabled)
		assertTrue(f.skeleton.leftUpperLegTracker!!.preserveMeasuredPosition)
		assertFalse(f.skeleton.rightUpperLegTracker!!.preserveMeasuredPosition)
		val solved = f.skeleton.leftUpperLegBone.getTailPosition()
		val published = f.skeleton.computedLeftKneeTracker!!.position
		assertTrue((published - solved).len() < 1e-6f)
		assertEquals(published, f.skeleton.legTweaks.bufferHead.leftKneePositionCorrected)
		val rightShift = f.skeleton.computedRightKneeTracker!!.position - f.skeleton.rightUpperLegBone.getTailPosition()
		assertTrue(rightShift.y > .001f, "Unmeasured side must retain ordinary floor correction: $rightShift")
	}

	@Test fun qOnlyAndDisabledIkResumeOrdinaryKneePostprocessing() = Fixture().use { f ->
		f.apply(false)
		f.manager.resetTrackersFull("Q-only postprocessing remains active")
		f.manager.update()
		assertFalse(f.skeleton.leftUpperLegTracker!!.preserveMeasuredPosition)
		assertTrue(f.skeleton.computedLeftKneeTracker!!.position.y - f.skeleton.leftUpperLegBone.getTailPosition().y > .001f)

		f.apply(true)
		f.manager.update()
		assertTrue(f.skeleton.leftUpperLegTracker!!.preserveMeasuredPosition)
		assertTrue((f.skeleton.computedLeftKneeTracker!!.position - f.skeleton.leftUpperLegBone.getTailPosition()).len() < 1e-6f)
		// Even a previous accepted-input flag grants no authority while IK is off.
		f.skeleton.ikSolver.enabled = false
		f.manager.update()
		assertTrue(f.skeleton.computedLeftKneeTracker!!.position.y - f.skeleton.leftUpperLegBone.getTailPosition().y > .001f)
	}
}
