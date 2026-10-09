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
internal fun AuthorityHeader.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
}
internal fun readAuthorityHeader(j: JsonObject): AuthorityHeader = AuthorityHeader(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
)
internal fun SourceSpaceAuthority.toJson(): JsonObject = JsonObject().also { j ->
    j.add("source_id", gson.toJsonTree(source_id))
    j.add("source_authority_session_epoch", gson.toJsonTree(source_authority_session_epoch))
    j.add("source_space_id", gson.toJsonTree(source_space_id))
    j.add("source_space_generation", gson.toJsonTree(source_space_generation.toString()))
}
internal fun readSourceSpaceAuthority(j: JsonObject): SourceSpaceAuthority = SourceSpaceAuthority(
    source_id = j.get("source_id").asString,
    source_authority_session_epoch = j.get("source_authority_session_epoch").asString,
    source_space_id = j.get("source_space_id").asString,
    source_space_generation = j.get("source_space_generation").asLong,
)
internal fun SourceObservationIdentity.toJson(): JsonObject = JsonObject().also { j ->
    j.add("source_space", source_space.toJson())
    j.add("observation_id", gson.toJsonTree(observation_id.toString()))
    j.add("source_locate_time_ns", gson.toJsonTree(source_locate_time_ns.toString()))
    j.add("source_time_domain_id", gson.toJsonTree(source_time_domain_id))
}
internal fun readSourceObservationIdentity(j: JsonObject): SourceObservationIdentity = SourceObservationIdentity(
    source_space = readSourceSpaceAuthority(j.get("source_space").asJsonObject),
    observation_id = j.get("observation_id").asLong,
    source_locate_time_ns = j.get("source_locate_time_ns").asLong,
    source_time_domain_id = j.get("source_time_domain_id").asString,
)
internal fun HmdValidityEvidence.toJson(): JsonObject = JsonObject().also { j ->
    j.add("position_valid", gson.toJsonTree(position_valid))
    j.add("orientation_valid", gson.toJsonTree(orientation_valid))
    j.add("position_tracked", gson.toJsonTree(position_tracked))
    j.add("orientation_tracked", gson.toJsonTree(orientation_tracked))
    j.add("view_position_valid", gson.toJsonTree(view_position_valid))
    j.add("view_orientation_valid", gson.toJsonTree(view_orientation_valid))
}
internal fun readHmdValidityEvidence(j: JsonObject): HmdValidityEvidence = HmdValidityEvidence(
    position_valid = j.get("position_valid").asBoolean,
    orientation_valid = j.get("orientation_valid").asBoolean,
    position_tracked = j.get("position_tracked").asBoolean,
    orientation_tracked = j.get("orientation_tracked").asBoolean,
    view_position_valid = j.get("view_position_valid").asBoolean,
    view_orientation_valid = j.get("view_orientation_valid").asBoolean,
)
internal fun RigidTransform.toJson(): JsonObject = JsonObject().also { j ->
    j.add("rotation_xyzw", gson.toJsonTree(rotation_xyzw))
    j.add("translation_xyz", gson.toJsonTree(translation_xyz))
}
internal fun readRigidTransform(j: JsonObject): RigidTransform = RigidTransform(
    rotation_xyzw = j.get("rotation_xyzw").asJsonArray.map { it.asDouble },
    translation_xyz = j.get("translation_xyz").asJsonArray.map { it.asDouble },
)
internal fun CommonWorldReference.toJson(): JsonObject = JsonObject().also { j ->
    j.add("owner_id", gson.toJsonTree(owner_id))
    j.add("world_epoch", gson.toJsonTree(world_epoch))
    j.add("coordinate_space", coordinate_space.toJson())
}
internal fun readCommonWorldReference(j: JsonObject): CommonWorldReference = CommonWorldReference(
    owner_id = j.get("owner_id").asString,
    world_epoch = j.get("world_epoch").asString,
    coordinate_space = readCoordinateSpace(j.get("coordinate_space").asJsonObject),
)
internal fun CommonMappingReference.toJson(): JsonObject = JsonObject().also { j ->
    j.add("world", world.toJson())
    j.add("source_space", source_space.toJson())
    j.add("calibration_epoch", gson.toJsonTree(calibration_epoch))
    j.add("mapping_revision", gson.toJsonTree(mapping_revision))
}
internal fun readCommonMappingReference(j: JsonObject): CommonMappingReference = CommonMappingReference(
    world = readCommonWorldReference(j.get("world").asJsonObject),
    source_space = readSourceSpaceAuthority(j.get("source_space").asJsonObject),
    calibration_epoch = j.get("calibration_epoch").asString,
    mapping_revision = j.get("mapping_revision").asLong,
)
internal fun TrustedHmdSourceAuthority.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("source_space", source_space.toJson())
    j.add("source_space_kind", gson.toJsonTree(source_space_kind))
    j.add("source_time_domain_id", gson.toJsonTree(source_time_domain_id))
    j.addProperty("protocol", "monaka.hmd_authority"); j.addProperty("type", "source_authority")
}
internal fun readTrustedHmdSourceAuthority(j: JsonObject): TrustedHmdSourceAuthority = TrustedHmdSourceAuthority(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    source_space = readSourceSpaceAuthority(j.get("source_space").asJsonObject),
    source_space_kind = j.get("source_space_kind").asString,
    source_time_domain_id = j.get("source_time_domain_id").asString,
)
internal fun TrustedHmdSourcePose.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("source", source.toJson())
    j.add("position", gson.toJsonTree(position))
    j.add("orientation", gson.toJsonTree(orientation))
    j.add("validity", validity.toJson())
    j.addProperty("protocol", "monaka.hmd_authority"); j.addProperty("type", "source_pose")
}
internal fun readTrustedHmdSourcePose(j: JsonObject): TrustedHmdSourcePose = TrustedHmdSourcePose(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    source = readSourceObservationIdentity(j.get("source").asJsonObject),
    position = j.get("position").asJsonArray.map { it.asDouble },
    orientation = j.get("orientation").asJsonArray.map { it.asDouble },
    validity = readHmdValidityEvidence(j.get("validity").asJsonObject),
)
internal fun TrustedHmdSourceUnavailable.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("source_space", source_space.toJson())
    j.add("reason", gson.toJsonTree(reason))
    j.add("validity", validity?.toJson())
    j.addProperty("protocol", "monaka.hmd_authority"); j.addProperty("type", "source_unavailable")
}
internal fun readTrustedHmdSourceUnavailable(j: JsonObject): TrustedHmdSourceUnavailable = TrustedHmdSourceUnavailable(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    source_space = readSourceSpaceAuthority(j.get("source_space").asJsonObject),
    reason = j.get("reason").asString,
    validity = if (!j.has("validity") || j.get("validity").isJsonNull) null else readHmdValidityEvidence(j.get("validity").asJsonObject),
)
internal fun TrustedHmdSourceRevocation.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("source_space", source_space.toJson())
    j.add("reason", gson.toJsonTree(reason))
    j.addProperty("protocol", "monaka.hmd_authority"); j.addProperty("type", "source_revocation")
}
internal fun readTrustedHmdSourceRevocation(j: JsonObject): TrustedHmdSourceRevocation = TrustedHmdSourceRevocation(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    source_space = readSourceSpaceAuthority(j.get("source_space").asJsonObject),
    reason = j.get("reason").asString,
)
internal fun CommonWorldAuthorityPublication.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("world", world.toJson())
    j.add("anchor_source", anchor_source.toJson())
    j.addProperty("protocol", "monaka.common_world"); j.addProperty("type", "world_authority")
}
internal fun readCommonWorldAuthorityPublication(j: JsonObject): CommonWorldAuthorityPublication = CommonWorldAuthorityPublication(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    world = readCommonWorldReference(j.get("world").asJsonObject),
    anchor_source = readSourceSpaceAuthority(j.get("anchor_source").asJsonObject),
)
internal fun CommonWorldMappingPublication.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("mapping", mapping.toJson())
    j.add("transform", transform.toJson())
    j.addProperty("protocol", "monaka.common_world"); j.addProperty("type", "mapping_publication")
}
internal fun readCommonWorldMappingPublication(j: JsonObject): CommonWorldMappingPublication = CommonWorldMappingPublication(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    mapping = readCommonMappingReference(j.get("mapping").asJsonObject),
    transform = readRigidTransform(j.get("transform").asJsonObject),
)
internal fun TrustedHmdCommonPose.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("mapping", mapping.toJson())
    j.add("source", source.toJson())
    j.add("source_position", gson.toJsonTree(source_position))
    j.add("source_orientation", gson.toJsonTree(source_orientation))
    j.add("validity", validity.toJson())
    j.add("common_position", gson.toJsonTree(common_position))
    j.add("common_orientation", gson.toJsonTree(common_orientation))
    j.addProperty("protocol", "monaka.common_world"); j.addProperty("type", "common_pose")
}
internal fun readTrustedHmdCommonPose(j: JsonObject): TrustedHmdCommonPose = TrustedHmdCommonPose(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    mapping = readCommonMappingReference(j.get("mapping").asJsonObject),
    source = readSourceObservationIdentity(j.get("source").asJsonObject),
    source_position = j.get("source_position").asJsonArray.map { it.asDouble },
    source_orientation = j.get("source_orientation").asJsonArray.map { it.asDouble },
    validity = readHmdValidityEvidence(j.get("validity").asJsonObject),
    common_position = j.get("common_position").asJsonArray.map { it.asDouble },
    common_orientation = j.get("common_orientation").asJsonArray.map { it.asDouble },
)
internal fun TrustedHmdCommonUnavailable.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("mapping", mapping.toJson())
    j.add("reason", gson.toJsonTree(reason))
    j.add("validity", validity?.toJson())
    j.addProperty("protocol", "monaka.common_world"); j.addProperty("type", "common_unavailable")
}
internal fun readTrustedHmdCommonUnavailable(j: JsonObject): TrustedHmdCommonUnavailable = TrustedHmdCommonUnavailable(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    mapping = readCommonMappingReference(j.get("mapping").asJsonObject),
    reason = j.get("reason").asString,
    validity = if (!j.has("validity") || j.get("validity").isJsonNull) null else readHmdValidityEvidence(j.get("validity").asJsonObject),
)
internal fun CommonWorldMappingRevocation.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("mapping", mapping.toJson())
    j.add("reason", gson.toJsonTree(reason))
    j.addProperty("protocol", "monaka.common_world"); j.addProperty("type", "mapping_revocation")
}
internal fun readCommonWorldMappingRevocation(j: JsonObject): CommonWorldMappingRevocation = CommonWorldMappingRevocation(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    mapping = readCommonMappingReference(j.get("mapping").asJsonObject),
    reason = j.get("reason").asString,
)
internal fun CommonWorldRevocation.toJson(): JsonObject = JsonObject().also { j ->
    j.add("version", version.toJson())
    j.add("publisher_id", gson.toJsonTree(publisher_id))
    j.add("session_id", gson.toJsonTree(session_id))
    j.add("clock_id", gson.toJsonTree(clock_id))
    j.add("sequence", gson.toJsonTree(sequence.toString()))
    j.add("timestamp_ns", gson.toJsonTree(timestamp_ns.toString()))
    j.add("sent_at_ns", gson.toJsonTree(sent_at_ns.toString()))
    j.add("timestamp_kind", gson.toJsonTree(timestamp_kind))
    j.add("world", world.toJson())
    j.add("reason", gson.toJsonTree(reason))
    j.addProperty("protocol", "monaka.common_world"); j.addProperty("type", "world_revocation")
}
internal fun readCommonWorldRevocation(j: JsonObject): CommonWorldRevocation = CommonWorldRevocation(
    version = readVersion(j.get("version").asJsonObject),
    publisher_id = j.get("publisher_id").asString,
    session_id = j.get("session_id").asString,
    clock_id = j.get("clock_id").asString,
    sequence = j.get("sequence").asLong,
    timestamp_ns = j.get("timestamp_ns").asLong,
    sent_at_ns = j.get("sent_at_ns").asLong,
    timestamp_kind = j.get("timestamp_kind").asString,
    world = readCommonWorldReference(j.get("world").asJsonObject),
    reason = j.get("reason").asString,
)
