package dev.monaka.tracking.pico

import dev.slimevr.tracking.trackers.TrackerPosition

/**
 * Runtime body assignment table for persistent PicoMotionTrackerBridge serials.
 *
 * Tracker identity stays independent from body placement. The bridge serial is the
 * persistent physical identity; this registry is the explicit policy that maps that
 * identity onto a SlimeVR/Monaka body target. Replacing the map is atomic so a future
 * UI/config writer can update assignments without racing the VRServer polling thread.
 */
class PicoMotionTrackerBridgeBodyAssignments(
	initialAssignments: Map<String, TrackerPosition> = emptyMap(),
) {
	@Volatile
	private var assignments: Map<String, TrackerPosition> = normalize(initialAssignments)

	fun resolve(serial: String): TrackerPosition? = assignments[serial]

	fun snapshot(): Map<String, TrackerPosition> = assignments

	fun assign(
		serial: String,
		target: TrackerPosition,
	) {
		val normalizedSerial = requireSerial(serial)
		assignments = LinkedHashMap(assignments).apply {
			this[normalizedSerial] = target
		}
	}

	fun unassign(serial: String): TrackerPosition? {
		val normalizedSerial = requireSerial(serial)
		val previous = assignments[normalizedSerial] ?: return null
		assignments = LinkedHashMap(assignments).apply {
			remove(normalizedSerial)
		}
		return previous
	}

	fun replaceAll(newAssignments: Map<String, TrackerPosition>) {
		assignments = normalize(newAssignments)
	}

	companion object {
		/**
		 * Parses `SERIAL=body:left_foot;SERIAL2=RIGHT_FOOT` style startup configuration.
		 * Both TrackerPosition designations and enum names are accepted.
		 */
		fun parse(spec: String?): Map<String, TrackerPosition> {
			if (spec.isNullOrBlank()) return emptyMap()

			val parsed = linkedMapOf<String, TrackerPosition>()
			for (rawEntry in spec.split(';')) {
				val entry = rawEntry.trim()
				if (entry.isEmpty()) continue

				val separator = entry.indexOf('=')
			require(separator > 0 && separator < entry.lastIndex) {
					"Invalid PICO body assignment '$entry'; expected SERIAL=body:position"
				}

				val serial = requireSerial(entry.substring(0, separator))
				val targetText = entry.substring(separator + 1).trim()
				val target = TrackerPosition.getByDesignation(targetText)
					?: TrackerPosition.entries.firstOrNull { it.name.equals(targetText, ignoreCase = true) }
					?: throw IllegalArgumentException("Unknown PICO body target '$targetText' for serial '$serial'")

			require(serial !in parsed) {
					"Duplicate PICO body assignment for serial '$serial'"
				}
				parsed[serial] = target
			}
			return parsed
		}

		private fun normalize(source: Map<String, TrackerPosition>): Map<String, TrackerPosition> =
			linkedMapOf<String, TrackerPosition>().apply {
				for ((serial, target) in source) {
					this[requireSerial(serial)] = target
				}
			}

		private fun requireSerial(serial: String): String {
			val normalized = serial.trim()
			require(normalized.isNotEmpty()) { "PICO tracker serial must not be blank" }
			return normalized
		}
	}
}
