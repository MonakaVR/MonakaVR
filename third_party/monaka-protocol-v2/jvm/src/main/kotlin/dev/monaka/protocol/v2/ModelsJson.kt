package dev.monaka.protocol.v2
import com.google.gson.*

internal fun Version.toJson(): JsonObject = JsonObject().also { j ->
    j.add("major", gson.toJsonTree(major))
    j.add("minor", gson.toJsonTree(minor))
}
internal fun readVersion(j: JsonObject): Version = Version(
    major = j.get("major").asInt,
    minor = j.get("minor").asInt,
)
internal fun CoordinateSpace.toJson(): JsonObject = JsonObject().also { j ->
    j.add("id", gson.toJsonTree(id))
    j.add("convention", gson.toJsonTree(convention))
    j.add("revision", gson.toJsonTree(revision))
}
internal fun readCoordinateSpace(j: JsonObject): CoordinateSpace = CoordinateSpace(
    id = j.get("id").asString,
    convention = j.get("convention").asString,
    revision = j.get("revision").asLong,
)
internal fun Battery.toJson(): JsonObject = JsonObject().also { j ->
    j.add("fraction", gson.toJsonTree(fraction))
    j.add("charging", gson.toJsonTree(charging))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
}
internal fun readBattery(j: JsonObject): Battery = Battery(
    fraction = if (!j.has("fraction") || j.get("fraction").isJsonNull) null else j.get("fraction").asDouble,
    charging = if (!j.has("charging") || j.get("charging").isJsonNull) null else j.get("charging").asBoolean,
    timestamp_ns = j.get("timestamp_ns").asLong,
)
internal fun Derivative.toJson(): JsonObject = JsonObject().also { j ->
    j.add("value", gson.toJsonTree(value))
    j.add("frame", gson.toJsonTree(frame))
    j.add("evidence", gson.toJsonTree(evidence))
}
internal fun readDerivative(j: JsonObject): Derivative = Derivative(
    value = j.get("value").asJsonArray.map { it.asDouble },
    frame = j.get("frame").asString,
    evidence = j.get("evidence").asString,
)
internal fun Validity.toJson(): JsonObject = JsonObject().also { j ->
    j.add("position", gson.toJsonTree(position))
    j.add("orientation", gson.toJsonTree(orientation))
}
internal fun readValidity(j: JsonObject): Validity = Validity(
    position = j.get("position").asBoolean,
    orientation = j.get("orientation").asBoolean,
)
internal fun Confidence.toJson(): JsonObject = JsonObject().also { j ->
    j.add("position", gson.toJsonTree(position))
    j.add("orientation", gson.toJsonTree(orientation))
}
internal fun readConfidence(j: JsonObject): Confidence = Confidence(
    position = j.get("position").asDouble,
    orientation = j.get("orientation").asDouble,
)
internal fun Input.toJson(): JsonObject = JsonObject().also { j ->
    j.add("source_id", gson.toJsonTree(source_id))
    j.add("device_id", gson.toJsonTree(device_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("orientation_evidence", gson.toJsonTree(orientation_evidence))
}
internal fun readInput(j: JsonObject): Input = Input(
    source_id = j.get("source_id").asString,
    device_id = j.get("device_id").asString,
    session_id = j.get("session_id").asString,
    sequence = j.get("sequence").asLong,
    orientation_evidence = j.get("orientation_evidence").asString,
)
internal fun TrackerObservation.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("source_id", gson.toJsonTree(source_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("device_id", gson.toJsonTree(device_id))
    j.add("position", gson.toJsonTree(position))
    j.add("orientation", gson.toJsonTree(orientation))
    j.add("validity", validity.toJson())
    j.add("orientation_evidence", gson.toJsonTree(orientation_evidence))
    j.add("linear_velocity", linear_velocity?.toJson())
    j.add("angular_velocity", angular_velocity?.toJson())
    j.add("linear_acceleration", linear_acceleration?.toJson())
    j.add("tracking_state", gson.toJsonTree(tracking_state))
    j.add("coordinate_space", coordinate_space.toJson())
    j.add("capabilities", gson.toJsonTree(capabilities))
    j.add("modality", gson.toJsonTree(modality))
    j.add("battery", battery?.toJson())
    j.addProperty("protocol", "monaka.observation"); j.addProperty("type", "pose")
}
internal fun readTrackerObservation(j: JsonObject): TrackerObservation = TrackerObservation(
    version = readVersion(j.get("version").asJsonObject),
    source_id = j.get("source_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    device_id = j.get("device_id").asString,
    position = if (!j.has("position") || j.get("position").isJsonNull) null else j.get("position").asJsonArray.map { it.asDouble },
    orientation = if (!j.has("orientation") || j.get("orientation").isJsonNull) null else j.get("orientation").asJsonArray.map { it.asDouble },
    validity = readValidity(j.get("validity").asJsonObject),
    orientation_evidence = j.get("orientation_evidence").asString,
    linear_velocity = if (!j.has("linear_velocity") || j.get("linear_velocity").isJsonNull) null else readDerivative(j.get("linear_velocity").asJsonObject),
    angular_velocity = if (!j.has("angular_velocity") || j.get("angular_velocity").isJsonNull) null else readDerivative(j.get("angular_velocity").asJsonObject),
    linear_acceleration = if (!j.has("linear_acceleration") || j.get("linear_acceleration").isJsonNull) null else readDerivative(j.get("linear_acceleration").asJsonObject),
    tracking_state = j.get("tracking_state").asString,
    coordinate_space = readCoordinateSpace(j.get("coordinate_space").asJsonObject),
    capabilities = j.get("capabilities").asJsonArray.map { it.asString },
    modality = j.get("modality").asString,
    battery = if (!j.has("battery") || j.get("battery").isJsonNull) null else readBattery(j.get("battery").asJsonObject),
)
internal fun ObservationDeviceState.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("source_id", gson.toJsonTree(source_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("device_id", gson.toJsonTree(device_id))
    j.add("presence", gson.toJsonTree(presence))
    j.add("tracking_state", gson.toJsonTree(tracking_state))
    j.add("coordinate_space", coordinate_space.toJson())
    j.add("capabilities", gson.toJsonTree(capabilities))
    j.add("modality", gson.toJsonTree(modality))
    j.add("battery", battery?.toJson())
    j.addProperty("protocol", "monaka.observation"); j.addProperty("type", "device_state")
}
internal fun readObservationDeviceState(j: JsonObject): ObservationDeviceState = ObservationDeviceState(
    version = readVersion(j.get("version").asJsonObject),
    source_id = j.get("source_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    device_id = j.get("device_id").asString,
    presence = j.get("presence").asString,
    tracking_state = j.get("tracking_state").asString,
    coordinate_space = readCoordinateSpace(j.get("coordinate_space").asJsonObject),
    capabilities = j.get("capabilities").asJsonArray.map { it.asString },
    modality = j.get("modality").asString,
    battery = if (!j.has("battery") || j.get("battery").isJsonNull) null else readBattery(j.get("battery").asJsonObject),
)
internal fun MtpPose.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("source_id", gson.toJsonTree(source_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("tracker_id", gson.toJsonTree(tracker_id))
    j.add("position", gson.toJsonTree(position))
    j.add("orientation", gson.toJsonTree(orientation))
    j.add("validity", validity.toJson())
    j.add("linear_velocity", gson.toJsonTree(linear_velocity))
    j.add("angular_velocity", gson.toJsonTree(angular_velocity))
    j.add("linear_acceleration", gson.toJsonTree(linear_acceleration))
    j.add("confidence", confidence.toJson())
    j.add("tracking_state", gson.toJsonTree(tracking_state))
    j.add("coordinate_space", coordinate_space.toJson())
    j.add("capabilities", gson.toJsonTree(capabilities))
    j.add("modality", gson.toJsonTree(modality))
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("mapping_revision", gson.toJsonTree(mapping_revision))
    j.add("input", input.toJson())
    j.addProperty("protocol", "monaka.tracking"); j.addProperty("type", "pose")
}
internal fun readMtpPose(j: JsonObject): MtpPose = MtpPose(
    version = readVersion(j.get("version").asJsonObject),
    source_id = j.get("source_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    tracker_id = j.get("tracker_id").asString,
    position = if (!j.has("position") || j.get("position").isJsonNull) null else j.get("position").asJsonArray.map { it.asDouble },
    orientation = if (!j.has("orientation") || j.get("orientation").isJsonNull) null else j.get("orientation").asJsonArray.map { it.asDouble },
    validity = readValidity(j.get("validity").asJsonObject),
    linear_velocity = if (!j.has("linear_velocity") || j.get("linear_velocity").isJsonNull) null else j.get("linear_velocity").asJsonArray.map { it.asDouble },
    angular_velocity = if (!j.has("angular_velocity") || j.get("angular_velocity").isJsonNull) null else j.get("angular_velocity").asJsonArray.map { it.asDouble },
    linear_acceleration = if (!j.has("linear_acceleration") || j.get("linear_acceleration").isJsonNull) null else j.get("linear_acceleration").asJsonArray.map { it.asDouble },
    confidence = readConfidence(j.get("confidence").asJsonObject),
    tracking_state = j.get("tracking_state").asString,
    coordinate_space = readCoordinateSpace(j.get("coordinate_space").asJsonObject),
    capabilities = j.get("capabilities").asJsonArray.map { it.asString },
    modality = j.get("modality").asString,
    publisher_id = j.get("publisher_id").asString,
    mapping_revision = j.get("mapping_revision").asLong,
    input = readInput(j.get("input").asJsonObject),
)
internal fun MtpTrackerState.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("source_id", gson.toJsonTree(source_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("tracker_id", gson.toJsonTree(tracker_id))
    j.add("presence", gson.toJsonTree(presence))
    j.add("tracking_state", gson.toJsonTree(tracking_state))
    j.add("coordinate_space", coordinate_space.toJson())
    j.add("capabilities", gson.toJsonTree(capabilities))
    j.add("modality", gson.toJsonTree(modality))
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("battery", battery?.toJson())
    j.add("mapping_revision", gson.toJsonTree(mapping_revision))
    j.addProperty("protocol", "monaka.tracking"); j.addProperty("type", "tracker_state")
}
internal fun readMtpTrackerState(j: JsonObject): MtpTrackerState = MtpTrackerState(
    version = readVersion(j.get("version").asJsonObject),
    source_id = j.get("source_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    tracker_id = j.get("tracker_id").asString,
    presence = j.get("presence").asString,
    tracking_state = j.get("tracking_state").asString,
    coordinate_space = readCoordinateSpace(j.get("coordinate_space").asJsonObject),
    capabilities = j.get("capabilities").asJsonArray.map { it.asString },
    modality = j.get("modality").asString,
    publisher_id = j.get("publisher_id").asString,
    battery = if (!j.has("battery") || j.get("battery").isJsonNull) null else readBattery(j.get("battery").asJsonObject),
    mapping_revision = j.get("mapping_revision").asLong,
)
