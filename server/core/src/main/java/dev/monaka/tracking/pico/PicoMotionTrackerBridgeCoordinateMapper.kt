package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/**
 * Converts PicoMotionTrackerBridge's PICO OutputA pose into Monaka room space.
 *
 * PicoMotionTrackerBridge exposes the backend-normalized OutputA position directly in
 * metres. The bridge's physically validated SteamVR path also keeps those position
 * components unchanged, while converting the native OutputA quaternion as follows:
 *
 * OpenVR/Monaka x = -PICO z
 * OpenVR/Monaka y = +PICO y
 * OpenVR/Monaka z = -PICO x
 * OpenVR/Monaka w = +PICO w
 *
 * Monaka's pose pipeline uses the same right-handed, Y-up room-space convention for
 * absolute 6DoF sources, so no additional position reflection is applied here.
 *
 * This mapper deliberately performs only the validated component transform. It does
 * not normalize or otherwise repair the quaternion; source validity remains owned by
 * PicoMotionTrackerBridge and the data-source quality policy.
 */
object PicoMotionTrackerBridgeCoordinateMapper : PicoMotionTrackerBridgePoseMapper {
	override fun toMonaka(state: PicoMotionTrackerBridgeState): PicoMotionTrackerBridgeMappedPose = when (state.referenceFrame) {
		PicoMotionTrackerBridgeReferenceFrame.PICO_OUTPUT_A -> PicoMotionTrackerBridgeMappedPose(
			position = state.positionMeters?.let(::mapPositionFromPicoOutputA),
			rotation = state.orientation?.let(::mapRotationFromPicoOutputA),
		)
	}

	fun mapPositionFromPicoOutputA(position: Vector3): Vector3 = position

	fun mapRotationFromPicoOutputA(rotation: Quaternion): Quaternion = Quaternion(
		rotation.w,
		negateWithoutNegativeZero(rotation.z),
		rotation.y,
		negateWithoutNegativeZero(rotation.x),
	)

	private fun negateWithoutNegativeZero(value: Float): Float = if (value == 0f) 0f else -value
}
