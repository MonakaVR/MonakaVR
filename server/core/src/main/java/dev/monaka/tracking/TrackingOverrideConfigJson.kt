package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

internal object TrackingOverrideConfigJson {
	fun hand(key: String): TrackingOverrideHand = when (key) {
		"left" -> TrackingOverrideHand.LEFT
		"right" -> TrackingOverrideHand.RIGHT
		else -> error("Unknown tracking override hand")
	}

	fun read(
		node: JsonNode,
		assignments: Map<TrackerPosition, MainTrackerAssignment>,
		space: CoordinateSpace,
	): Map<TrackingOverrideHand, TrackingOverrideSelection> {
		require(node.isObject) { "trackingOverride must be an object" }
		return node.fields().asSequence().associate { (key, value) ->
			val hand = hand(key)
			require(value.isObject && value["enabled"]?.isBoolean == true)
			val grip = requireNotNull(value["gripOffset"]).takeUnless(JsonNode::isNull)?.let(::readGrip)
			val enabled = value["enabled"].booleanValue()
			val fields = value.fieldNames().asSequence().toSet()
			val selection = if (fields == setOf("enabled", "gripOffset", "confirmedAssignment")) {
				val binding = value["confirmedAssignment"].takeUnless(JsonNode::isNull)?.let {
					requireNotNull(it.textValue()) { "Invalid tracking override confirmation" }
				}
				TrackingOverrideSelection(enabled, grip, binding)
			} else {
				require(fields == setOf("enabled", "source", "gripOffset", "sameUniverseConfirmed") && value["sameUniverseConfirmed"].isBoolean) {
					"Invalid tracking override selection"
				}
				val oldSource = value["source"].takeUnless(JsonNode::isNull)?.let(::readLegacySource)
				val confirmed = value["sameUniverseConfirmed"].booleanValue()
				require(!enabled || (oldSource != null && grip != null && confirmed)) { "Invalid legacy tracking override selection" }
				if (confirmed && grip != null && oldSource != null && oldSource == assignments[hand.body]?.configuredSixDofSource()) {
					TrackingOverrideSelection.confirmed(enabled, hand, oldSource, space, grip)
				} else {
					TrackingOverrideSelection(enabled, grip)
				}
			}
			hand to selection
		}
	}

	private fun readLegacySource(ref: JsonNode): TrackerReference {
		require(ref.isObject && ref["kind"]?.textValue() == "mtp") { "Legacy override source must use the MVIP catalog identity" }
		fun component(name: String): String = requireNotNull(ref[name]?.textValue()?.takeUnless(String::isBlank)) {
			"Missing legacy override source $name"
		}
		return TrackerReference.mtp(LogicalTracker(component("source_id"), component("tracker_id"), component("publisher_id")))
	}

	data class Edit(val enabled: Boolean, val gripOffset: TrackingOverrideGripOffset?, val sameUniverseConfirmed: Boolean)

	fun readEdit(node: JsonNode): Edit {
		require(
			node.isObject &&
				node.fieldNames().asSequence().toSet() == setOf("enabled", "gripOffset", "sameUniverseConfirmed") &&
				node["enabled"].isBoolean && node["sameUniverseConfirmed"].isBoolean,
		) { "Invalid tracking override settings. Choose the source in the hand assignment." }
		val grip = node["gripOffset"].takeUnless(JsonNode::isNull)?.let(::readGrip)
		return Edit(node["enabled"].booleanValue(), grip, node["sameUniverseConfirmed"].booleanValue())
	}

	fun readGrip(node: JsonNode): TrackingOverrideGripOffset {
		require(node.isObject && node.fieldNames().asSequence().toSet() == setOf("positionM", "rotationWxyz")) { "Invalid grip offset" }
		fun components(name: String, size: Int): List<Float> {
			val values = node[name]
			require(values.isArray && values.size() == size && values.all { it.isNumber && it.floatValue().isFinite() }) {
				"$name must contain $size finite numbers"
			}
			return values.map(JsonNode::floatValue)
		}
		val (x, y, z) = components("positionM", 3)
		val (w, qx, qy, qz) = components("rotationWxyz", 4)
		return TrackingOverrideGripOffset(Vector3(x, y, z), Quaternion(w, qx, qy, qz))
	}

	fun readback(selection: TrackingOverrideSelection, calibrationCurrent: Boolean): Map<String, Any?> = mapOf(
		"enabled" to selection.enabled,
		"gripOffset" to selection.gripOffset?.takeIf { calibrationCurrent }?.let(::writeGrip),
		"sameUniverseConfirmed" to calibrationCurrent,
	)

	fun write(selections: Map<TrackingOverrideHand, TrackingOverrideSelection>): Map<String, Any> = selections.entries.associate { (hand, selection) ->
		hand.name.lowercase() to mapOf(
			"enabled" to selection.enabled,
			"gripOffset" to selection.gripOffset?.let(::writeGrip),
			"confirmedAssignment" to selection.confirmedAssignment,
		)
	}

	private fun writeGrip(grip: TrackingOverrideGripOffset) = mapOf(
		"positionM" to listOf(grip.position.x, grip.position.y, grip.position.z),
		"rotationWxyz" to listOf(grip.rotation.w, grip.rotation.x, grip.rotation.y, grip.rotation.z),
	)
}
