package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.pico.PicoMotionTrackerBridgeCoordinateMapper
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeReceiverTracker
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.util.Locale

private const val DEFAULT_PROBE_SECONDS = 20L
private const val MIN_UNIQUE_POSE_PACKETS = 5
private const val PRINT_INTERVAL_NANOS = 250_000_000L
private const val LOOP_SLEEP_MILLIS = 5L

private data class HardwareProbeStats(
	var uniquePosePackets: Int = 0,
	var lastSequence: Long? = null,
	var sawPositionValid: Boolean = false,
	var sawPositionInvalid: Boolean = false,
	var sawOrientation: Boolean = false,
	var sawMappedTimestamp: Boolean = false,
	var newestReceiveNanos: Long = 0L,
) {
	fun observe(tracker: PicoMotionTrackerBridgeReceiverTracker) {
		if (lastSequence != tracker.lastPoseSequence) {
			uniquePosePackets++
			lastSequence = tracker.lastPoseSequence
		}
		sawPositionValid = sawPositionValid || tracker.positionValid
		sawPositionInvalid = sawPositionInvalid || !tracker.positionValid
		sawOrientation = sawOrientation || tracker.orientationSamplePresent
		sawMappedTimestamp = sawMappedTimestamp || tracker.mappedPosePcMonotonicNanos != null
		newestReceiveNanos = maxOf(newestReceiveNanos, tracker.lastPoseReceivePcMonotonicNanos)
	}
}

/**
 * Live hardware probe for the real PICO HMD bridge sender.
 *
 * This deliberately bypasses body assignment and IK. It validates the first real-device
 * boundary only: HMD UDP sender -> native receiver -> JNA -> OutputA coordinate mapper.
 * Run it with one visible tracker powered before the HMD backend starts.
 */
fun main() {
	val durationSeconds = System.getProperty("monaka.pico.hardwareProbeSeconds")
		?.trim()
		?.takeIf { it.isNotEmpty() }
		?.toLongOrNull()
		?: DEFAULT_PROBE_SECONDS
	require(durationSeconds in 5L..300L) {
		"monaka.pico.hardwareProbeSeconds must be between 5 and 300"
	}

	val config = PicoMotionTrackerBridgeNativeReceiverConfig(
		listenPort = 29765,
		timeSyncIntervalNanos = 1_000_000_000L,
		staleTimeoutNanos = 500_000_000L,
	)

	PicoMotionTrackerBridgeNativeReceiverClient(config).use { receiver ->
		println(
			"PICO_HARDWARE_PROBE_READY port=${receiver.listenPort} duration=${durationSeconds}s " +
				"library=${config.libraryName}",
		)
		println("Keep one tracker visible and move/rotate it slowly while this probe runs.")

		val deadline = System.nanoTime() + durationSeconds * 1_000_000_000L
		val statsBySerial = linkedMapOf<String, HardwareProbeStats>()
		var activeSession: Long? = null
		var sessionChanges = 0
		var nextPrintAt = 0L

		while (System.nanoTime() < deadline) {
			receiver.pump()
			val snapshot = receiver.readSnapshot()
			val now = System.nanoTime()

			if (snapshot.activeSenderSessionId != null && snapshot.activeSenderSessionId != activeSession) {
				if (activeSession != null) sessionChanges++
				activeSession = snapshot.activeSenderSessionId
				println("PICO_HARDWARE_SESSION id=${java.lang.Long.toUnsignedString(activeSession!!)}")
			}

			for (tracker in snapshot.trackers) {
				statsBySerial.getOrPut(tracker.serial) { HardwareProbeStats() }.observe(tracker)
			}

			if (now >= nextPrintAt && snapshot.trackers.isNotEmpty()) {
				for (tracker in snapshot.trackers) {
					printTracker(tracker, now)
				}
				nextPrintAt = now + PRINT_INTERVAL_NANOS
			}

			Thread.sleep(LOOP_SLEEP_MILLIS)
		}

		check(activeSession != null) {
			"No PICO sender session reached UDP port ${receiver.listenPort}. " +
				"Check HMD bridge PC IPv4, same-LAN reachability, and Windows firewall."
		}
		check(statsBySerial.isNotEmpty()) {
			"A PICO sender session was received, but no tracker pose was received. " +
				"Power the tracker before restarting the HMD backend so startup enumeration includes it."
		}

		for ((serial, stats) in statsBySerial) {
			println(
				"PICO_HARDWARE_SUMMARY serial=$serial uniquePosePackets=${stats.uniquePosePackets} " +
					"positionValid=${stats.sawPositionValid} positionInvalidSeen=${stats.sawPositionInvalid} " +
					"orientation=${stats.sawOrientation} clockMapped=${stats.sawMappedTimestamp}",
			)
		}

		val streamHealthy = statsBySerial.values.any {
			it.uniquePosePackets >= MIN_UNIQUE_POSE_PACKETS &&
				it.sawPositionValid &&
				it.sawOrientation
		}
		check(streamHealthy) {
			"No tracker produced at least $MIN_UNIQUE_POSE_PACKETS unique poses with valid optical position " +
				"and orientation during the probe. Keep a tracker visible and confirm the HMD backend is polling it."
		}
		check(statsBySerial.values.any { it.sawMappedTimestamp }) {
			"Pose transport worked, but bridge clock synchronization never produced a mapped PC timestamp."
		}

		println(
			"PICO_HARDWARE_PROBE_PASSED trackers=${statsBySerial.size} " +
				"sessionChanges=$sessionChanges session=${java.lang.Long.toUnsignedString(activeSession!!)}",
		)
	}
}

private fun printTracker(
	tracker: PicoMotionTrackerBridgeReceiverTracker,
	nowNanos: Long,
) {
	val rawPosition = tracker.positionMeters
	val mappedPosition = rawPosition?.let(PicoMotionTrackerBridgeCoordinateMapper::mapPositionFromPicoOutputA)
	val rawRotation = tracker.orientation
	val mappedRotation = rawRotation?.let(PicoMotionTrackerBridgeCoordinateMapper::mapRotationFromPicoOutputA)
	val ageMillis = ((nowNanos - tracker.lastPoseReceivePcMonotonicNanos).coerceAtLeast(0L)) / 1_000_000.0

	println(
		String.format(
			Locale.US,
			"PICO_HARDWARE_TRACKER serial=%s seq=%d posValid=%s ori=%s ageMs=%.2f mappedTime=%s " +
				"rawP=%s monakaP=%s rawQ=%s monakaQ=%s",
			tracker.serial,
			tracker.lastPoseSequence,
			tracker.positionValid,
			tracker.orientationSamplePresent,
			ageMillis,
			tracker.mappedPosePcMonotonicNanos?.toString() ?: "none",
			formatVector(rawPosition),
			formatVector(mappedPosition),
			formatQuaternion(rawRotation),
			formatQuaternion(mappedRotation),
		),
	)
}

private fun formatVector(value: Vector3?): String = value?.let {
	String.format(Locale.US, "(%.4f,%.4f,%.4f)", it.x, it.y, it.z)
} ?: "none"

private fun formatQuaternion(value: Quaternion?): String = value?.let {
	String.format(Locale.US, "(w=%.5f,x=%.5f,y=%.5f,z=%.5f)", it.w, it.x, it.y, it.z)
} ?: "none"
