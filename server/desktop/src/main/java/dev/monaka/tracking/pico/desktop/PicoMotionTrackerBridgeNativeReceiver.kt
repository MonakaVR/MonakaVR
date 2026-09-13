package dev.monaka.tracking.pico.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeReceiverClient
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeReceiverSnapshot
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeReceiverTracker
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeReferenceFrame
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

private const val PICO_OT_BRIDGE_C_API_VERSION = 1
private const val PICO_OT_BRIDGE_OK = 0
private const val PICO_OT_BRIDGE_SERIAL_CAPACITY = 32

/**
 * Desktop-only configuration for the native PicoMotionTrackerBridge Receiver.
 *
 * [libraryName] may be either a JNA library name (the default) or an absolute DLL path.
 * Port 0 is accepted for tests; production normally uses the bridge default UDP port 29765.
 */
data class PicoMotionTrackerBridgeNativeReceiverConfig(
	val libraryName: String = System.getProperty("monaka.pico.bridge.library")
		?: System.getenv("PICO_OT_BRIDGE_LIBRARY")
		?: "pico_ot_bridge_capi",
	val listenPort: Int = 29765,
	val timeSyncIntervalNanos: Long = 1_000_000_000L,
	val staleTimeoutNanos: Long = 500_000_000L,
	val clientSessionId: Long = 0L,
) {
	init {
		require(libraryName.isNotBlank()) { "PICO bridge native library name must not be blank" }
		require(listenPort in 0..0xffff) { "listenPort must fit unsigned 16-bit range" }
		require(timeSyncIntervalNanos > 0L) { "timeSyncIntervalNanos must be positive" }
		require(staleTimeoutNanos > 0L) { "staleTimeoutNanos must be positive" }
	}
}

@Structure.FieldOrder(
	"listenPort",
	"timeSyncIntervalNanos",
	"staleTimeoutNanos",
	"clientSessionId",
)
private open class NativeReceiverConfig : Structure() {
	@JvmField var listenPort: Short = 0
	@JvmField var timeSyncIntervalNanos: Long = 0L
	@JvmField var staleTimeoutNanos: Long = 0L
	@JvmField var clientSessionId: Long = 0L
}

@Structure.FieldOrder(
	"serial",
	"senderSessionId",
	"lastPoseSequence",
	"lastDeviceSequence",
	"hasPoseSequence",
	"hasDeviceSequence",
	"runtimeDeviceId",
	"connectedSnapshot",
	"positionValid",
	"orientationSamplePresent",
	"rawTrackingState65",
	"referenceFrame",
	"angularVelocityFrame",
	"lastPoseReceivePcNanos",
	"lastDeviceReceivePcNanos",
	"posePcMonotonicNanos",
	"posePcMonotonicValid",
	"sourceTimestamp",
	"observedMonotonicNanos",
	"mappedMonotonicNanos",
	"mappedMonotonicValid",
	"positionMeters",
	"orientationXyzw",
	"linearVelocityMetersPerSec",
	"angularVelocityRadPerSec",
	"deviceStatePresent",
	"charging",
	"batteryBucketPresent",
	"batteryBucketRaw",
)
private open class NativeTrackerState : Structure() {
	@JvmField var serial: ByteArray = ByteArray(PICO_OT_BRIDGE_SERIAL_CAPACITY)
	@JvmField var senderSessionId: Long = 0L
	@JvmField var lastPoseSequence: Int = 0
	@JvmField var lastDeviceSequence: Int = 0
	@JvmField var hasPoseSequence: Byte = 0
	@JvmField var hasDeviceSequence: Byte = 0

	@JvmField var runtimeDeviceId: Int = -1
	@JvmField var connectedSnapshot: Byte = 0
	@JvmField var positionValid: Byte = 0
	@JvmField var orientationSamplePresent: Byte = 0
	@JvmField var rawTrackingState65: Byte = 0
	@JvmField var referenceFrame: Byte = 0
	@JvmField var angularVelocityFrame: Byte = 0

	@JvmField var lastPoseReceivePcNanos: Long = 0L
	@JvmField var lastDeviceReceivePcNanos: Long = 0L
	@JvmField var posePcMonotonicNanos: Long = 0L
	@JvmField var posePcMonotonicValid: Byte = 0

	@JvmField var sourceTimestamp: Long = 0L
	@JvmField var observedMonotonicNanos: Long = 0L
	@JvmField var mappedMonotonicNanos: Long = 0L
	@JvmField var mappedMonotonicValid: Byte = 0

	@JvmField var positionMeters: DoubleArray = DoubleArray(3)
	@JvmField var orientationXyzw: DoubleArray = DoubleArray(4)
	@JvmField var linearVelocityMetersPerSec: DoubleArray = DoubleArray(3)
	@JvmField var angularVelocityRadPerSec: DoubleArray = DoubleArray(3)

	@JvmField var deviceStatePresent: Byte = 0
	@JvmField var charging: Byte = 0
	@JvmField var batteryBucketPresent: Byte = 0
	@JvmField var batteryBucketRaw: Byte = 0
}

@Structure.FieldOrder("hmdMinusPcNanos", "networkRoundTripNanos")
private open class NativeClockEstimate : Structure() {
	@JvmField var hmdMinusPcNanos: Long = 0L
	@JvmField var networkRoundTripNanos: Long = 0L
}

private interface PicoMotionTrackerBridgeNativeLibrary : Library {
	fun pico_ot_bridge_c_api_version(): Int
	fun pico_ot_bridge_receiver_config_size(): Int
	fun pico_ot_bridge_tracker_state_size(): Int
	fun pico_ot_bridge_clock_estimate_size(): Int

	fun pico_ot_bridge_receiver_default_config(config: NativeReceiverConfig)
	fun pico_ot_bridge_receiver_create(): Pointer?
	fun pico_ot_bridge_receiver_destroy(receiver: Pointer?)
	fun pico_ot_bridge_receiver_start(receiver: Pointer?, config: NativeReceiverConfig): Int
	fun pico_ot_bridge_receiver_stop(receiver: Pointer?)
	fun pico_ot_bridge_receiver_pump(receiver: Pointer?): Int
	fun pico_ot_bridge_receiver_is_running(receiver: Pointer?): Byte
	fun pico_ot_bridge_receiver_listen_port(receiver: Pointer?): Short
	fun pico_ot_bridge_receiver_active_sender_session_id(receiver: Pointer?): Long
	fun pico_ot_bridge_receiver_tracker_count(receiver: Pointer?): Int
	fun pico_ot_bridge_receiver_get_tracker(receiver: Pointer?, index: Int, out: NativeTrackerState): Int
	fun pico_ot_bridge_receiver_get_clock_estimate(receiver: Pointer?, out: NativeClockEstimate): Byte
	fun pico_ot_bridge_receiver_last_error(receiver: Pointer?): String?
	fun pico_ot_bridge_status_string(status: Int): String?
}

/**
 * JNA client for the existing native PicoMotionTrackerBridge PC Receiver.
 *
 * UDP decoding, sender-session handling, packet sequence filtering, and clock sync remain
 * owned by the C++ bridge. This class only marshals the receiver's retained normalized
 * state into Monaka's platform-neutral [PicoMotionTrackerBridgeReceiverClient] contract.
 *
 * Calls are intentionally single-threaded. The receiver is pumped immediately before
 * [readSnapshot] by PicoMotionTrackerBridgeReceiverStateProvider in server:core.
 */
class PicoMotionTrackerBridgeNativeReceiverClient(
	private val config: PicoMotionTrackerBridgeNativeReceiverConfig = PicoMotionTrackerBridgeNativeReceiverConfig(),
) : PicoMotionTrackerBridgeReceiverClient, AutoCloseable {
	private val library: PicoMotionTrackerBridgeNativeLibrary = Native.load(
		config.libraryName,
		PicoMotionTrackerBridgeNativeLibrary::class.java,
	)
	private var receiver: Pointer? = null
	private var closed = false

	init {
		verifyAbi()

		val handle = library.pico_ot_bridge_receiver_create()
			?: throw IllegalStateException("PicoMotionTrackerBridge receiver allocation failed")
		receiver = handle

		val nativeConfig = NativeReceiverConfig().apply {
			library.pico_ot_bridge_receiver_default_config(this)
			read()
			listenPort = config.listenPort.toShort()
			timeSyncIntervalNanos = config.timeSyncIntervalNanos
			staleTimeoutNanos = config.staleTimeoutNanos
			clientSessionId = config.clientSessionId
			write()
		}

		try {
			checkStatus(
				library.pico_ot_bridge_receiver_start(handle, nativeConfig),
				"receiver start",
			)
		} catch (exception: Throwable) {
			library.pico_ot_bridge_receiver_destroy(handle)
			receiver = null
			closed = true
			throw exception
		}
	}

	val listenPort: Int
		get() {
			val handle = requireOpen()
			return library.pico_ot_bridge_receiver_listen_port(handle).toInt() and 0xffff
		}

	override fun pump() {
		val handle = requireOpen()
		checkStatus(library.pico_ot_bridge_receiver_pump(handle), "receiver pump")
	}

	override fun readSnapshot(): PicoMotionTrackerBridgeReceiverSnapshot {
		val handle = requireOpen()
		val activeSession = library.pico_ot_bridge_receiver_active_sender_session_id(handle)
		if (activeSession == 0L) {
			return PicoMotionTrackerBridgeReceiverSnapshot()
		}

		val count = library.pico_ot_bridge_receiver_tracker_count(handle).toLong() and 0xffff_ffffL
		require(count <= Int.MAX_VALUE.toLong()) {
			"PicoMotionTrackerBridge receiver exposed an unreasonable tracker count: $count"
		}

		val trackers = ArrayList<PicoMotionTrackerBridgeReceiverTracker>(count.toInt())
		for (index in 0 until count.toInt()) {
			val native = NativeTrackerState()
			checkStatus(
				library.pico_ot_bridge_receiver_get_tracker(handle, index, native),
				"read tracker $index",
			)
			native.read()
			if (native.hasPoseSequence.toInt() == 0) continue

			val senderSessionId = native.senderSessionId
			if (senderSessionId != activeSession) {
				throw IllegalStateException(
					"PicoMotionTrackerBridge receiver returned tracker from sender session " +
						"$senderSessionId while active session is $activeSession",
				)
			}

			trackers += native.toMonakaTracker()
		}

		return PicoMotionTrackerBridgeReceiverSnapshot(
			activeSenderSessionId = activeSession,
			trackers = trackers,
		)
	}

	override fun close() {
		if (closed) return
		closed = true
		val handle = receiver
		receiver = null
		if (handle != null) {
			library.pico_ot_bridge_receiver_stop(handle)
			library.pico_ot_bridge_receiver_destroy(handle)
		}
	}

	private fun NativeTrackerState.toMonakaTracker(): PicoMotionTrackerBridgeReceiverTracker {
		val serialValue = Native.toString(serial)
		if (serialValue.isBlank()) {
			throw IllegalStateException("PicoMotionTrackerBridge receiver returned a blank serial")
		}

		val frame = when (referenceFrame.toInt() and 0xff) {
			0 -> PicoMotionTrackerBridgeReferenceFrame.PICO_OUTPUT_A
			else -> throw IllegalStateException(
				"Unsupported PicoMotionTrackerBridge reference frame: ${referenceFrame.toInt() and 0xff}",
			)
		}

		val position = Vector3(
			positionMeters[0].toFloat(),
			positionMeters[1].toFloat(),
			positionMeters[2].toFloat(),
		)
		val hasOrientation = orientationSamplePresent.toInt() != 0
		val rotation = if (hasOrientation) {
			Quaternion(
				orientationXyzw[3].toFloat(),
				orientationXyzw[0].toFloat(),
				orientationXyzw[1].toFloat(),
				orientationXyzw[2].toFloat(),
			)
		} else {
			null
		}

		return PicoMotionTrackerBridgeReceiverTracker(
			serial = serialValue,
			senderSessionId = senderSessionId,
			lastPoseSequence = Integer.toUnsignedLong(lastPoseSequence),
			connectedSnapshot = connectedSnapshot.toInt() != 0,
			positionMeters = position,
			orientation = rotation,
			positionValid = positionValid.toInt() != 0,
			orientationSamplePresent = hasOrientation,
			lastPoseReceivePcMonotonicNanos = lastPoseReceivePcNanos,
			mappedPosePcMonotonicNanos = if (posePcMonotonicValid.toInt() != 0) {
				posePcMonotonicNanos
			} else {
				null
			},
			referenceFrame = frame,
		)
	}

	private fun verifyAbi() {
		val version = library.pico_ot_bridge_c_api_version()
		require(version == PICO_OT_BRIDGE_C_API_VERSION) {
			"Unsupported PicoMotionTrackerBridge receiver C API version $version " +
				"(expected $PICO_OT_BRIDGE_C_API_VERSION)"
		}

		verifyStructureSize(
			"receiver config",
			library.pico_ot_bridge_receiver_config_size(),
			NativeReceiverConfig().size(),
		)
		verifyStructureSize(
			"tracker state",
			library.pico_ot_bridge_tracker_state_size(),
			NativeTrackerState().size(),
		)
		verifyStructureSize(
			"clock estimate",
			library.pico_ot_bridge_clock_estimate_size(),
			NativeClockEstimate().size(),
		)
	}

	private fun verifyStructureSize(name: String, nativeSize: Int, jnaSize: Int) {
		val nativeUnsigned = nativeSize.toLong() and 0xffff_ffffL
		require(nativeUnsigned == jnaSize.toLong()) {
			"PicoMotionTrackerBridge $name ABI size mismatch: native=$nativeUnsigned JNA=$jnaSize"
		}
	}

	private fun checkStatus(status: Int, operation: String) {
		if (status == PICO_OT_BRIDGE_OK) return
		val handle = receiver
		val statusText = library.pico_ot_bridge_status_string(status) ?: "status=$status"
		val error = handle?.let { library.pico_ot_bridge_receiver_last_error(it) }
		throw IllegalStateException(
			buildString {
				append("PicoMotionTrackerBridge $operation failed: ")
				append(statusText)
				if (!error.isNullOrBlank()) {
					append(" (")
					append(error)
					append(')')
				}
			},
		)
	}

	private fun requireOpen(): Pointer {
		check(!closed) { "PicoMotionTrackerBridge receiver is closed" }
		return checkNotNull(receiver) { "PicoMotionTrackerBridge receiver is unavailable" }
	}
}
