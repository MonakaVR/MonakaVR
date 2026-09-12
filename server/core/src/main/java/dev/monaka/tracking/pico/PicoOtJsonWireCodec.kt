package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * UTF-8 JSON wire representation for one [PicoOtTransportFrame].
 *
 * Framing is NDJSON: one compact JSON object followed by a single LF. A concrete
 * socket/pipe implementation only needs to preserve complete lines; transport
 * semantics remain in [PicoOtTransportDataSource].
 */
object PicoOtJsonWireCodec {
	private val json = Json {
		encodeDefaults = true
		explicitNulls = false
		ignoreUnknownKeys = false
	}

	fun encode(frame: PicoOtTransportFrame): String = json.encodeToString(frame.toWire())

	fun encodeLine(frame: PicoOtTransportFrame): String = encode(frame) + "\n"

	fun decode(payload: String): PicoOtTransportFrame =
		json.decodeFromString<WireFrame>(payload).toDomain()

	fun decodeLine(line: String): PicoOtTransportFrame {
		val payload = when {
			line.endsWith("\r\n") -> line.dropLast(2)
			line.endsWith("\n") -> line.dropLast(1)
			else -> line
		}
		require('\n' !in payload && '\r' !in payload) {
			"PICO OT NDJSON frame must contain exactly one JSON record"
		}
		return decode(payload)
	}

	@Serializable
	private data class WireFrame(
		val protocolVersion: Int,
		val coordinateConvention: String,
		val sessionId: String,
		val trackingSpaceId: String,
		val sequence: Long,
		val trackers: List<WireTracker>,
	)

	@Serializable
	private data class WireTracker(
		val trackerId: String,
		val position: WireVector3? = null,
		val rotation: WireQuaternion? = null,
		val positionState: String,
		val rotationState: String,
		val batteryPercent: Int? = null,
	)

	@Serializable
	private data class WireVector3(
		val x: Float,
		val y: Float,
		val z: Float,
	)

	/** Quaternion component order on the wire is explicitly w,x,y,z. */
	@Serializable
	private data class WireQuaternion(
		val w: Float,
		val x: Float,
		val y: Float,
		val z: Float,
	)

	private fun PicoOtTransportFrame.toWire(): WireFrame = WireFrame(
		protocolVersion = protocolVersion,
		coordinateConvention = coordinateConvention.name,
		sessionId = sessionId,
		trackingSpaceId = trackingSpaceId,
		sequence = sequence,
		trackers = trackers.map { tracker ->
			WireTracker(
				trackerId = tracker.trackerId,
				position = tracker.position?.let { WireVector3(it.x, it.y, it.z) },
				rotation = tracker.rotation?.let { WireQuaternion(it.w, it.x, it.y, it.z) },
				positionState = tracker.positionState.name,
				rotationState = tracker.rotationState.name,
				batteryPercent = tracker.batteryPercent,
			)
		},
	)

	private fun WireFrame.toDomain(): PicoOtTransportFrame = PicoOtTransportFrame(
		protocolVersion = protocolVersion,
		coordinateConvention = enumValueOfChecked(
			coordinateConvention,
			"coordinateConvention",
		),
		sessionId = sessionId,
		trackingSpaceId = trackingSpaceId,
		sequence = sequence,
		trackers = trackers.map { it.toDomain() },
	)

	private fun WireTracker.toDomain(): PicoOtTrackerSample = PicoOtTrackerSample(
		trackerId = trackerId,
		position = position?.toDomain(),
		rotation = rotation?.toDomain(),
		positionState = enumValueOfChecked(positionState, "positionState"),
		rotationState = enumValueOfChecked(rotationState, "rotationState"),
		batteryPercent = batteryPercent,
	)

	private fun WireVector3.toDomain(): Vector3 {
		require(x.isFinite() && y.isFinite() && z.isFinite()) {
			"PICO OT position components must be finite"
		}
		return Vector3(x, y, z)
	}

	private fun WireQuaternion.toDomain(): Quaternion {
		require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()) {
			"PICO OT quaternion components must be finite"
		}
		return Quaternion(w, x, y, z)
	}

	private inline fun <reified T : Enum<T>> enumValueOfChecked(
		value: String,
		fieldName: String,
	): T = try {
		enumValueOf<T>(value)
	} catch (exception: IllegalArgumentException) {
		throw IllegalArgumentException("Unknown PICO OT $fieldName '$value'", exception)
	}
}
