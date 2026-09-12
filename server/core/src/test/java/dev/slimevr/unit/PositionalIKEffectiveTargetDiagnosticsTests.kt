package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKChain
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Diagnostic that compares the raw external tracker pose, the effective IK target
 * after mounting-offset compensation, the IK chain end effector, and the computed
 * output tracker. This distinguishes solver error from target/output-point mismatch.
 */
class PositionalIKEffectiveTargetDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val leftFoot: Tracker,
		val rightFoot: Tracker,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition, hasRotation: Boolean): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "effective-target-${position.name}",
			trackerPosition = position,
			trackerNum = 0,
			hasPosition = true,
			hasRotation = hasRotation,
			isComputed = false,
			allowReset = hasRotation,
			allowMounting = hasRotation,
			trackRotDirection = false,
		)
		tracker.status = TrackerStatus.OK
		if (hasRotation) tracker.setRotation(Quaternion.IDENTITY)
		return tracker
	}

	private fun createFixture(feetHaveRotation: Boolean): Fixture {
		val trackers = TestTrackerSet(positional = true)
		val leftFoot = makeFootTracker(7, TrackerPosition.LEFT_FOOT, feetHaveRotation)
		val rightFoot = makeFootTracker(8, TrackerPosition.RIGHT_FOOT, feetHaveRotation)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip, leftFoot, rightFoot))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)

		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeftFoot = hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRightFoot = hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		trackers.hip.position = computedHip.position
		leftFoot.position = computedLeftFoot.position
		rightFoot.position = computedRightFoot.position
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		leftFoot.position += Vector3(0.05f, 0f, 0f)
		rightFoot.position += Vector3(-0.05f, 0f, 0f)

		return Fixture(hpm, leftFoot, rightFoot)
	}

	@Suppress("UNCHECKED_CAST")
	private fun constrainedChains(hpm: HumanPoseManager): List<IKChain> {
		val solver = hpm.skeleton.ikSolver
		val field = solver.javaClass.getDeclaredField("chainList")
		field.isAccessible = true
		return (field.get(solver) as List<IKChain>).filter { it.tailConstraint != null }
	}

	private fun vecMm(v: Vector3): String =
		"(${v.x * 1000f},${v.y * 1000f},${v.z * 1000f})mm"

	private fun snapshot(fixture: Fixture, tick: Int): String {
		val chains = constrainedChains(fixture.hpm)
		val computedLeft = fixture.hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRight = fixture.hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		return chains
			.filter {
				it.tailConstraint?.trackerPosition == TrackerPosition.LEFT_FOOT ||
					it.tailConstraint?.trackerPosition == TrackerPosition.RIGHT_FOOT
			}
			.joinToString(" ; ") { chain ->
				val raw = chain.tailConstraint!!.position
				val effectiveTarget = chain.target
				val end = chain.bones.last().getTailPosition()
				val output = when (chain.tailConstraint!!.trackerPosition) {
					TrackerPosition.LEFT_FOOT -> computedLeft.position
					TrackerPosition.RIGHT_FOOT -> computedRight.position
					else -> error("Unexpected tracker position")
				}

				"tick=$tick ${chain.tailConstraint!!.trackerPosition} " +
					"bone=${chain.bones.last().boneType} " +
					"solverErr=${(end - effectiveTarget).len() * 1000f}mm " +
					"endToOutput=${vecMm(output - end)} " +
					"effectiveMinusRaw=${vecMm(effectiveTarget - raw)} " +
					"outputMinusEffective=${vecMm(output - effectiveTarget)}"
			}
	}

	private fun runTrace(feetHaveRotation: Boolean): String {
		val fixture = createFixture(feetHaveRotation)
		val trace = mutableListOf<String>()
		repeat(10) { index ->
			fixture.hpm.update()
			val tick = index + 1
			if (tick == 1 || tick == 5 || tick == 10) {
				trace += snapshot(fixture, tick)
			}
		}
		return trace.joinToString(" | ")
	}

	@Test
	fun reportEffectiveTargetsAndOutputPoints() {
		val sixDofTrace = runTrace(feetHaveRotation = true)
		val positionOnlyTrace = runTrace(feetHaveRotation = false)

		assertTrue(
			false,
			"Effective target diagnostic. SIX_DOF=[$sixDofTrace]; " +
				"POSITION_ONLY=[$positionOnlyTrace].",
		)
	}
}
