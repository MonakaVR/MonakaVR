package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/**
 * Coordinate convention carried by PICO OT transport frames.
 *
 * Monaka's canonical pose space is [MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS]:
 * - right-handed
 * - metres
 * - +X right
 * - +Y up
 * - -Z forward (+Z backward)
 * - Hamilton quaternion stored as (w, x, y, z), representing an active rotation
 *
 * [UNITY_LH_Y_UP_POS_Z_FORWARD_METERS] exists for Unity-side producers that expose
 * the usual Unity world basis directly. The receiver reflects that basis across Z
 * before observations enter Monaka Core.
 */
enum class PicoOtCoordinateConvention {
	MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
	UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
}

/**
 * Converts transport samples into Monaka's canonical coordinate convention.
 */
object PicoOtCoordinateConverter {
	fun toMonaka(
		sample: PicoOtTrackerSample,
		convention: PicoOtCoordinateConvention,
	): PicoOtTrackerSample = when (convention) {
		PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS -> sample
		PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS -> sample.copy(
			position = sample.position?.let(::unityPositionToMonaka),
			rotation = sample.rotation?.let(::unityRotationToMonaka),
		)
	}

	/**
	 * Unity LH (+Z forward) -> Monaka RH (-Z forward).
	 */
	fun unityPositionToMonaka(position: Vector3): Vector3 =
		Vector3(position.x, position.y, -position.z)

	/**
	 * Basis reflection S=diag(1,1,-1): Rm = S * Ru * S.
	 *
	 * For Hamilton quaternion (w,x,y,z), this becomes (w,-x,-y,z).
	 */
	fun unityRotationToMonaka(rotation: Quaternion): Quaternion =
		Quaternion(rotation.w, -rotation.x, -rotation.y, rotation.z)
}
