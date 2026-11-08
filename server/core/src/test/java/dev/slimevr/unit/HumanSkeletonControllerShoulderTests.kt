package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigToggles
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.EulerAngles
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HumanSkeletonControllerShoulderTests {

	private class Inputs {
		val head = tracker(1, TrackerPosition.HEAD, positional = true, hmd = true)
		val chest = tracker(2, TrackerPosition.CHEST)
		val leftHand = tracker(3, TrackerPosition.LEFT_HAND, positional = true)
		val rightHand = tracker(4, TrackerPosition.RIGHT_HAND, positional = true)
		val torso = listOf(head, chest)
		val all = torso + listOf(leftHand, rightHand)
		val manager = HumanPoseManager(torso).also {
			it.setLegTweaksEnabled(false)
			it.setToggle(SkeletonConfigToggles.FORCE_ARMS_FROM_HMD, false)
			// Install FULL assignments after selecting the arm policy, matching
			// normal writeback's tracker refresh and its current IK topology.
			it.skeleton.setTrackersFromList(all)
		}
		val skeleton get() = manager.skeleton

		init {
			head.position = Vector3(0.2f, 1.7f, -0.1f)
			head.setRotation(Quaternion.IDENTITY)
		}

		fun pose(yaw: Float, frame: Int) {
			// A body turn rotates HMD and torso together; otherwise the ordinary
			// spine constraints legitimately clamp the chest relative to the head.
			head.setRotation(rotation(yaw))
			chest.setRotation(rotation(yaw))
			leftHand.position = Vector3(-0.5f + frame * 0.02f, 1.1f, -0.45f)
			rightHand.position = Vector3(0.6f + frame * 0.03f, 1.25f, -0.3f)
			leftHand.setRotation(rotation(-0.2f))
			rightHand.setRotation(rotation(0.35f))
		}
	}

	@Test
	fun controllerShouldersRefreshFromRotatedChestAndPreserveCorrespondingHands() {
		val input = Inputs()
		for ((frame, yaw) in listOf(2.1f, -1.8f, 0.7f).withIndex()) {
			input.pose(yaw, frame)
			seedStaleShoulders(input.skeleton)
			input.skeleton.updatePose()
			assertShoulderBaseline(input.skeleton)
			assertAnatomicalSides(input.skeleton)
			assertControllerHands(input)
		}
	}

	@Test
	fun controllerHmdAndReconnectTransitionsKeepShoulderSidesAndHandOwnership() {
		val input = Inputs()
		input.pose(1.6f, 0)
		seedStaleShoulders(input.skeleton)
		input.skeleton.updatePose()
		assertControllerHands(input)
		assertAnatomicalSides(input.skeleton)

		// The ordinary HMD setting changes the existing arm topology. This fix
		// must not redirect a controller's roots or swap its body assignment.
		input.manager.setToggle(SkeletonConfigToggles.FORCE_ARMS_FROM_HMD, true)
		input.skeleton.setTrackersFromList(input.all)
		input.pose(-1.3f, 1)
		seedStaleShoulders(input.skeleton)
		input.skeleton.updatePose()
		assertFalse(input.skeleton.isTrackingLeftArmFromController)
		assertFalse(input.skeleton.isTrackingRightArmFromController)
		assertSame(input.skeleton.leftHandBone, input.skeleton.leftHandTrackerBone.parent)
		assertSame(input.skeleton.rightHandBone, input.skeleton.rightHandTrackerBone.parent)
		assertAnatomicalSides(input.skeleton)

		// Removal and re-addition use the production tracker-list refresh path.
		input.manager.setToggle(SkeletonConfigToggles.FORCE_ARMS_FROM_HMD, false)
		input.skeleton.setTrackersFromList(input.torso)
		input.pose(0.9f, 2)
		seedStaleShoulders(input.skeleton)
		input.skeleton.updatePose()
		assertFalse(input.skeleton.isTrackingLeftArmFromController)
		assertFalse(input.skeleton.isTrackingRightArmFromController)
		assertShoulderBaseline(input.skeleton)
		assertAnatomicalSides(input.skeleton)

		input.skeleton.setTrackersFromList(input.all)
		input.pose(-2.2f, 3)
		seedStaleShoulders(input.skeleton)
		input.skeleton.updatePose()
		assertShoulderBaseline(input.skeleton)
		assertAnatomicalSides(input.skeleton)
		assertControllerHands(input)
	}

	@Test
	fun controllerPathKeepsExplicitShoulderTrackerRotationAndBoneOffsets() {
		val input = Inputs()
		val leftShoulder = tracker(5, TrackerPosition.LEFT_SHOULDER)
		val rightShoulder = tracker(6, TrackerPosition.RIGHT_SHOULDER)
		input.pose(1.1f, 0)
		input.skeleton.setTrackersFromList(input.all + listOf(leftShoulder, rightShoulder))
		// The shoulder constraint allows swing but no twist. Express a small
		// pure local-X swing through each existing bone offset, so preserving
		// this distinct tracker input does not conflict with that constraint.
		val chest = input.chest.getRotation()
		val leftOffset = input.skeleton.leftShoulderBone.rotationOffset
		val rightOffset = input.skeleton.rightShoulderBone.rotationOffset
		leftShoulder.setRotation(chest * leftOffset * Quaternion.rotationAroundXAxis(0.08f) * leftOffset.inv())
		rightShoulder.setRotation(chest * rightOffset * Quaternion.rotationAroundXAxis(-0.06f) * rightOffset.inv())
		seedStaleShoulders(input.skeleton)
		input.skeleton.updatePose()
		assertRotation(
			leftShoulder.getRotation() * input.skeleton.leftShoulderBone.rotationOffset,
			input.skeleton.leftShoulderBone.getLocalRotation(),
		)
		assertRotation(
			rightShoulder.getRotation() * input.skeleton.rightShoulderBone.rotationOffset,
			input.skeleton.rightShoulderBone.getLocalRotation(),
		)
		assertAnatomicalSides(input.skeleton)
		assertControllerHands(input)
	}

	private fun assertControllerHands(input: Inputs) {
		val skeleton = input.skeleton
		assertFiniteBones(skeleton)
		assertTrue(skeleton.isTrackingLeftArmFromController)
		assertTrue(skeleton.isTrackingRightArmFromController)
		assertSame(input.leftHand, skeleton.leftHandTracker)
		assertSame(input.rightHand, skeleton.rightHandTracker)
		assertNull(skeleton.leftHandTrackerBone.parent)
		assertNull(skeleton.rightHandTrackerBone.parent)
		assertPosition(input.leftHand.position, skeleton.leftHandTrackerBone.getPosition())
		assertPosition(input.rightHand.position, skeleton.rightHandTrackerBone.getPosition())
		assertPosition(input.leftHand.position, skeleton.computedLeftHandTracker!!.position)
		assertPosition(input.rightHand.position, skeleton.computedRightHandTracker!!.position)
	}

	private fun assertAnatomicalSides(skeleton: HumanSkeleton) {
		assertFiniteBones(skeleton)
		val rightMinusLeft = skeleton.rightUpperArmBone.getPosition() - skeleton.leftUpperArmBone.getPosition()
		val chestRight = skeleton.upperChestBone.getGlobalRotation().sandwich(Vector3(1f, 0f, 0f))
		val projection = rightMinusLeft.x * chestRight.x + rightMinusLeft.y * chestRight.y + rightMinusLeft.z * chestRight.z
		assertTrue(projection > 0.1f, "Anatomical shoulders crossed in chest coordinates: $projection m")
	}

	private fun assertShoulderBaseline(skeleton: HumanSkeleton) {
		assertFiniteBones(skeleton)
		val chest = skeleton.upperChestBone.getLocalRotation()
		assertRotation(chest * skeleton.leftUpperShoulderBone.rotationOffset, skeleton.leftUpperShoulderBone.getLocalRotation())
		assertRotation(chest * skeleton.rightUpperShoulderBone.rotationOffset, skeleton.rightUpperShoulderBone.getLocalRotation())
		assertRotation(chest * skeleton.leftShoulderBone.rotationOffset, skeleton.leftShoulderBone.getLocalRotation())
		assertRotation(chest * skeleton.rightShoulderBone.rotationOffset, skeleton.rightShoulderBone.getLocalRotation())
	}

	private fun seedStaleShoulders(skeleton: HumanSkeleton) {
		val stale = rotation(3.1415927f)
		skeleton.leftUpperShoulderBone.setRotationRaw(stale)
		skeleton.rightUpperShoulderBone.setRotationRaw(stale)
		skeleton.leftShoulderBone.setRotationRaw(stale)
		skeleton.rightShoulderBone.setRotationRaw(stale)
	}

	private fun assertPosition(expected: Vector3, actual: Vector3) {
		assertTrue((expected - actual).len() < 0.00001f, "Expected $expected, got $actual")
	}

	private fun assertFiniteBones(skeleton: HumanSkeleton) {
		for (bone in skeleton.allHumanBones) {
			val position = bone.getPosition()
			val rotation = bone.getGlobalRotation()
			assertTrue(
				position.x.isFinite() && position.y.isFinite() && position.z.isFinite() &&
					rotation.w.isFinite() && rotation.x.isFinite() && rotation.y.isFinite() && rotation.z.isFinite(),
				"Non-finite ${bone.boneType}: position=$position rotation=$rotation " +
					"parent=${bone.parent?.boneType} local=${bone.getLocalRotation()} " +
					"chest=${skeleton.upperChestBone.getLocalRotation()} neck=${skeleton.neckBone.getLocalRotation()}",
			)
		}
	}

	private fun assertRotation(expected: Quaternion, actual: Quaternion) {
		val dot = expected.w * actual.w + expected.x * actual.x + expected.y * actual.y + expected.z * actual.z
		assertTrue(abs(dot) > 0.99999f, "Expected rotation $expected, got $actual")
	}

	companion object {
		private fun rotation(yaw: Float) = EulerAngles(EulerOrder.YZX, 0f, yaw, 0f).toQuaternion()

		private fun tracker(id: Int, role: TrackerPosition, positional: Boolean = false, hmd: Boolean = false) = Tracker(
			device = null,
			id = id,
			name = "synthetic-${role.name}",
			trackerPosition = role,
			hasPosition = positional,
			hasRotation = true,
			isComputed = hmd,
			isHmd = hmd,
			trackRotDirection = false,
		).also { it.status = TrackerStatus.OK }
	}
}
