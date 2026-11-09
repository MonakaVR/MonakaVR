package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

internal object TrackingOverrideConfigJson {
	fun hand(key: String): TrackingOverrideHand = when (key) {
		"left" -> TrackingOverrideHand.LEFT
		"right" -> TrackingOverrideHand.RIGHT
		else -> error("Unknown tracking override hand")
	}

	fun read(node: JsonNode): Map<TrackingOverrideHand, TrackingOverrideSelection> {
		require(node.isObject) { "trackingOverride must be an object" }
		return node.fields().asSequence().associate { (key, value) ->
			hand(key) to readSelection(value) { ref ->
				require(ref.isObject && ref["kind"]?.textValue() == "mtp") { "Override source must use the MVIP catalog identity" }
				fun component(name: String): String = requireNotNull(ref[name]?.textValue()?.takeUnless(String::isBlank)) {
					"Missing override source $name"
				}
				TrackerReference.mtp(LogicalTracker(component("source_id"), component("tracker_id"), component("publisher_id")))
			}
		}
	}

	fun readSelection(node: JsonNode, reference: (JsonNode) -> TrackerReference): TrackingOverrideSelection {
		require(
			node.isObject &&
				node.fieldNames().asSequence().toSet() == setOf("enabled", "source", "gripOffset", "sameUniverseConfirmed") &&
				node["enabled"].isBoolean && node["sameUniverseConfirmed"].isBoolean,
		) { "Invalid tracking override selection" }
		val source = node["source"].takeUnless(JsonNode::isNull)?.let(reference)
		val grip = node["gripOffset"].takeUnless(JsonNode::isNull)?.let(::readGrip)
		val enabled = node["enabled"].booleanValue()
		val confirmed = node["sameUniverseConfirmed"].booleanValue()
		require(!enabled || (source != null && grip != null && confirmed)) {
			"Enable requires a 6DoF source, an explicit grip offset, and SteamVR world confirmation"
		}
		return TrackingOverrideSelection(enabled, source, grip, confirmed)
	}

	private fun readGrip(node: JsonNode): TrackingOverrideGripOffset {
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

	fun writeSelection(selection: TrackingOverrideSelection, reference: (TrackerReference) -> Any): Map<String, Any?> = mapOf(
		"enabled" to selection.enabled,
		"source" to selection.source?.let(reference),
		"gripOffset" to selection.gripOffset?.let { grip ->
			mapOf(
				"positionM" to listOf(grip.position.x, grip.position.y, grip.position.z),
				"rotationWxyz" to listOf(grip.rotation.w, grip.rotation.x, grip.rotation.y, grip.rotation.z),
			)
		},
		"sameUniverseConfirmed" to selection.sameUniverseConfirmed,
	)

	fun write(selections: Map<TrackingOverrideHand, TrackingOverrideSelection>): Map<String, Any> = selections.entries.associate { (hand, selection) ->
		hand.name.lowercase() to writeSelection(selection) { reference ->
			val mtp = requireNotNull(reference.mtp) { "Override source must use the MVIP catalog identity" }
			mapOf("kind" to "mtp", "publisher_id" to mtp.publisherId, "source_id" to mtp.sourceId, "tracker_id" to mtp.trackerId)
		}
	}
}
