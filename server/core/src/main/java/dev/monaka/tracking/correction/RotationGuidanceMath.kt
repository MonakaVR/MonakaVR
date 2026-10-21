package dev.monaka.tracking.correction

import io.github.axisangles.ktmath.Quaternion
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.expm1
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Hamilton quaternions, constructor order wxyz. No wire/config parsing here. */
object RotationGuidanceMath {
	// Double intermediates avoid overflow/underflow of Float lenSq on finite inputs.
	fun normalize(q: Quaternion): Quaternion? {
		val v = listOf(q.w.toDouble(), q.x.toDouble(), q.y.toDouble(), q.z.toDouble())
		if (v.any { !it.isFinite() }) return null
		val norm = sqrt(v.sumOf { it * it })
		if (norm < 1e-10) return null
		return Quaternion(
			(v[0] / norm).toFloat(),
			(v[1] / norm).toFloat(),
			(v[2] / norm).toFloat(),
			(v[3] / norm).toFloat(),
		)
	}

	fun angle(a: Quaternion, b: Quaternion): Double {
		val x = requireNotNull(normalize(a))
		val y = requireNotNull(normalize(b))
		val dot = x.w.toDouble() * y.w + x.x.toDouble() * y.x + x.y.toDouble() * y.y + x.z.toDouble() * y.z
		val nx = sqrt(x.w.toDouble() * x.w + x.x.toDouble() * x.x + x.y.toDouble() * x.y + x.z.toDouble() * x.z)
		val ny = sqrt(y.w.toDouble() * y.w + y.x.toDouble() * y.x + y.y.toDouble() * y.y + y.z.toDouble() * y.z)
		return 2 * acos((abs(dot) / (nx * ny)).coerceIn(0.0, 1.0))
	}

	fun body(worldToSensor: Quaternion, sensorToBody: Quaternion): Quaternion = requireNotNull(normalize(requireNotNull(normalize(worldToSensor)) * requireNotNull(normalize(sensorToBody))))

	fun target(mainBody: Quaternion, imuBody: Quaternion): Quaternion = body(mainBody, requireNotNull(normalize(imuBody)).inv())

	/** Exponential response on the shortest SO(3) arc, capped by radians/second. */
	fun learn(
		correction: Quaternion,
		target: Quaternion,
		dtSeconds: Double,
		tauSeconds: Double,
		maxRadiansPerSecond: Double,
	): Quaternion {
		require(listOf(dtSeconds, tauSeconds, maxRadiansPerSecond).all { it.isFinite() && it > 0 })
		val a = requireNotNull(normalize(correction))
		var b = requireNotNull(normalize(target))
		if (a.dot(b) < 0f) b = -b
		val radians = angle(a, b)
		if (radians < 1e-12) return a
		val fraction = min(-expm1(-dtSeconds / tauSeconds), maxRadiansPerSecond * dtSeconds / radians).coerceIn(0.0, 1.0)
		val half = radians / 2
		val (wa, wb) = if (half < 1e-6) {
			(1 - fraction) to fraction
		} else {
			(sin((1 - fraction) * half) / sin(half)) to (sin(fraction * half) / sin(half))
		}
		return requireNotNull(
			normalize(
				Quaternion(
					(wa * a.w + wb * b.w).toFloat(),
					(wa * a.x + wb * b.x).toFloat(),
					(wa * a.y + wb * b.y).toFloat(),
					(wa * a.z + wb * b.z).toFloat(),
				),
			),
		)
	}
}
