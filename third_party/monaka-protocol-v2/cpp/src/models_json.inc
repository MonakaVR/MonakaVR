void to_json(json& j, const Version& v) { j=json::object();
    j["major"]=v.major;
    j["minor"]=v.minor;
}
void from_json(const json& j, Version& v) {
    v.major=j.at("major").get<std::uint16_t>();
    v.minor=j.at("minor").get<std::uint16_t>();
}
void to_json(json& j, const CoordinateSpace& v) { j=json::object();
    j["id"]=v.id;
    j["convention"]=v.convention;
    j["revision"]=v.revision;
}
void from_json(const json& j, CoordinateSpace& v) {
    v.id=j.at("id").get<std::string>();
    v.convention=j.at("convention").get<std::string>();
    v.revision=j.at("revision").get<std::uint32_t>();
}
void to_json(json& j, const Battery& v) { j=json::object();
    if(v.fraction) j["fraction"]=*v.fraction; else j["fraction"]=nullptr;
    if(v.charging) j["charging"]=*v.charging; else j["charging"]=nullptr;
    j["timestamp_ns"]=std::to_string(v.timestamp_ns);
}
void from_json(const json& j, Battery& v) {
    if(j.contains("fraction") && !j.at("fraction").is_null()) v.fraction=j.at("fraction").get<double>(); else v.fraction.reset();
    if(j.contains("charging") && !j.at("charging").is_null()) v.charging=j.at("charging").get<bool>(); else v.charging.reset();
    v.timestamp_ns=std::stoll(j.at("timestamp_ns").get<std::string>());
}
void to_json(json& j, const Derivative& v) { j=json::object();
    j["value"]=v.value;
    j["frame"]=v.frame;
    j["evidence"]=v.evidence;
}
void from_json(const json& j, Derivative& v) {
    v.value=j.at("value").get<Vec3>();
    v.frame=j.at("frame").get<std::string>();
    v.evidence=j.at("evidence").get<std::string>();
}
void to_json(json& j, const Validity& v) { j=json::object();
    j["position"]=v.position;
    j["orientation"]=v.orientation;
}
void from_json(const json& j, Validity& v) {
    v.position=j.at("position").get<bool>();
    v.orientation=j.at("orientation").get<bool>();
}
void to_json(json& j, const Confidence& v) { j=json::object();
    j["position"]=v.position;
    j["orientation"]=v.orientation;
}
void from_json(const json& j, Confidence& v) {
    v.position=j.at("position").get<double>();
    v.orientation=j.at("orientation").get<double>();
}
void to_json(json& j, const Input& v) { j=json::object();
    j["device_id"]=v.device_id;
    j["session_id"]=v.session_id;
    j["sequence"]=std::to_string(v.sequence);
}
void from_json(const json& j, Input& v) {
    v.device_id=j.at("device_id").get<std::string>();
    v.session_id=j.at("session_id").get<std::string>();
    v.sequence=std::stoll(j.at("sequence").get<std::string>());
}
void to_json(json& j, const TrackerObservation& v) { j=json::object();
    j["version"]=v.version;
    j["source_id"]=v.source_id;
    j["session_id"]=v.session_id;
    j["clock_id"]=v.clock_id;
    j["sequence"]=std::to_string(v.sequence);
    j["timestamp_ns"]=std::to_string(v.timestamp_ns);
    j["sent_at_ns"]=std::to_string(v.sent_at_ns);
    j["timestamp_kind"]=v.timestamp_kind;
    j["device_id"]=v.device_id;
    if(v.position) j["position"]=*v.position; else j["position"]=nullptr;
    if(v.orientation) j["orientation"]=*v.orientation; else j["orientation"]=nullptr;
    j["validity"]=v.validity;
    j["orientation_evidence"]=v.orientation_evidence;
    if(v.linear_velocity) j["linear_velocity"]=*v.linear_velocity; else j["linear_velocity"]=nullptr;
    if(v.angular_velocity) j["angular_velocity"]=*v.angular_velocity; else j["angular_velocity"]=nullptr;
    if(v.linear_acceleration) j["linear_acceleration"]=*v.linear_acceleration; else j["linear_acceleration"]=nullptr;
    j["tracking_state"]=v.tracking_state;
    j["coordinate_space"]=v.coordinate_space;
    j["capabilities"]=v.capabilities;
    if(v.battery) j["battery"]=*v.battery; else j["battery"]=nullptr;
    j["protocol"]="monaka.observation"; j["type"]="pose";
}
void from_json(const json& j, TrackerObservation& v) {
    v.version=j.at("version").get<Version>();
    v.source_id=j.at("source_id").get<std::string>();
    v.session_id=j.at("session_id").get<std::string>();
    v.clock_id=j.at("clock_id").get<std::string>();
    v.sequence=std::stoll(j.at("sequence").get<std::string>());
    v.timestamp_ns=std::stoll(j.at("timestamp_ns").get<std::string>());
    v.sent_at_ns=std::stoll(j.at("sent_at_ns").get<std::string>());
    v.timestamp_kind=j.at("timestamp_kind").get<std::string>();
    v.device_id=j.at("device_id").get<std::string>();
    if(j.contains("position") && !j.at("position").is_null()) v.position=j.at("position").get<Vec3>(); else v.position.reset();
    if(j.contains("orientation") && !j.at("orientation").is_null()) v.orientation=j.at("orientation").get<QuatXyzw>(); else v.orientation.reset();
    v.validity=j.at("validity").get<Validity>();
    v.orientation_evidence=j.at("orientation_evidence").get<std::string>();
    if(j.contains("linear_velocity") && !j.at("linear_velocity").is_null()) v.linear_velocity=j.at("linear_velocity").get<Derivative>(); else v.linear_velocity.reset();
    if(j.contains("angular_velocity") && !j.at("angular_velocity").is_null()) v.angular_velocity=j.at("angular_velocity").get<Derivative>(); else v.angular_velocity.reset();
    if(j.contains("linear_acceleration") && !j.at("linear_acceleration").is_null()) v.linear_acceleration=j.at("linear_acceleration").get<Derivative>(); else v.linear_acceleration.reset();
    v.tracking_state=j.at("tracking_state").get<std::string>();
    v.coordinate_space=j.at("coordinate_space").get<CoordinateSpace>();
    v.capabilities=j.at("capabilities").get<std::vector<std::string>>();
    if(j.contains("battery") && !j.at("battery").is_null()) v.battery=j.at("battery").get<Battery>(); else v.battery.reset();
}
void to_json(json& j, const ObservationDeviceState& v) { j=json::object();
    j["version"]=v.version;
    j["source_id"]=v.source_id;
    j["session_id"]=v.session_id;
    j["clock_id"]=v.clock_id;
    j["sequence"]=std::to_string(v.sequence);
    j["timestamp_ns"]=std::to_string(v.timestamp_ns);
    j["sent_at_ns"]=std::to_string(v.sent_at_ns);
    j["timestamp_kind"]=v.timestamp_kind;
    j["device_id"]=v.device_id;
    j["presence"]=v.presence;
    j["tracking_state"]=v.tracking_state;
    j["coordinate_space"]=v.coordinate_space;
    j["capabilities"]=v.capabilities;
    if(v.battery) j["battery"]=*v.battery; else j["battery"]=nullptr;
    j["protocol"]="monaka.observation"; j["type"]="device_state";
}
void from_json(const json& j, ObservationDeviceState& v) {
    v.version=j.at("version").get<Version>();
    v.source_id=j.at("source_id").get<std::string>();
    v.session_id=j.at("session_id").get<std::string>();
    v.clock_id=j.at("clock_id").get<std::string>();
    v.sequence=std::stoll(j.at("sequence").get<std::string>());
    v.timestamp_ns=std::stoll(j.at("timestamp_ns").get<std::string>());
    v.sent_at_ns=std::stoll(j.at("sent_at_ns").get<std::string>());
    v.timestamp_kind=j.at("timestamp_kind").get<std::string>();
    v.device_id=j.at("device_id").get<std::string>();
    v.presence=j.at("presence").get<std::string>();
    v.tracking_state=j.at("tracking_state").get<std::string>();
    v.coordinate_space=j.at("coordinate_space").get<CoordinateSpace>();
    v.capabilities=j.at("capabilities").get<std::vector<std::string>>();
    if(j.contains("battery") && !j.at("battery").is_null()) v.battery=j.at("battery").get<Battery>(); else v.battery.reset();
}
void to_json(json& j, const MtpPose& v) { j=json::object();
    j["version"]=v.version;
    j["source_id"]=v.source_id;
    j["session_id"]=v.session_id;
    j["clock_id"]=v.clock_id;
    j["sequence"]=std::to_string(v.sequence);
    j["timestamp_ns"]=std::to_string(v.timestamp_ns);
    j["sent_at_ns"]=std::to_string(v.sent_at_ns);
    j["timestamp_kind"]=v.timestamp_kind;
    j["tracker_id"]=v.tracker_id;
    if(v.position) j["position"]=*v.position; else j["position"]=nullptr;
    if(v.orientation) j["orientation"]=*v.orientation; else j["orientation"]=nullptr;
    j["validity"]=v.validity;
    if(v.linear_velocity) j["linear_velocity"]=*v.linear_velocity; else j["linear_velocity"]=nullptr;
    if(v.angular_velocity) j["angular_velocity"]=*v.angular_velocity; else j["angular_velocity"]=nullptr;
    if(v.linear_acceleration) j["linear_acceleration"]=*v.linear_acceleration; else j["linear_acceleration"]=nullptr;
    j["confidence"]=v.confidence;
    j["tracking_state"]=v.tracking_state;
    j["coordinate_space"]=v.coordinate_space;
    j["capabilities"]=v.capabilities;
    j["mapping_revision"]=v.mapping_revision;
    j["input"]=v.input;
    j["protocol"]="monaka.tracking"; j["type"]="pose";
}
void from_json(const json& j, MtpPose& v) {
    v.version=j.at("version").get<Version>();
    v.source_id=j.at("source_id").get<std::string>();
    v.session_id=j.at("session_id").get<std::string>();
    v.clock_id=j.at("clock_id").get<std::string>();
    v.sequence=std::stoll(j.at("sequence").get<std::string>());
    v.timestamp_ns=std::stoll(j.at("timestamp_ns").get<std::string>());
    v.sent_at_ns=std::stoll(j.at("sent_at_ns").get<std::string>());
    v.timestamp_kind=j.at("timestamp_kind").get<std::string>();
    v.tracker_id=j.at("tracker_id").get<std::string>();
    if(j.contains("position") && !j.at("position").is_null()) v.position=j.at("position").get<Vec3>(); else v.position.reset();
    if(j.contains("orientation") && !j.at("orientation").is_null()) v.orientation=j.at("orientation").get<QuatXyzw>(); else v.orientation.reset();
    v.validity=j.at("validity").get<Validity>();
    if(j.contains("linear_velocity") && !j.at("linear_velocity").is_null()) v.linear_velocity=j.at("linear_velocity").get<Vec3>(); else v.linear_velocity.reset();
    if(j.contains("angular_velocity") && !j.at("angular_velocity").is_null()) v.angular_velocity=j.at("angular_velocity").get<Vec3>(); else v.angular_velocity.reset();
    if(j.contains("linear_acceleration") && !j.at("linear_acceleration").is_null()) v.linear_acceleration=j.at("linear_acceleration").get<Vec3>(); else v.linear_acceleration.reset();
    v.confidence=j.at("confidence").get<Confidence>();
    v.tracking_state=j.at("tracking_state").get<std::string>();
    v.coordinate_space=j.at("coordinate_space").get<CoordinateSpace>();
    v.capabilities=j.at("capabilities").get<std::vector<std::string>>();
    v.mapping_revision=j.at("mapping_revision").get<std::uint32_t>();
    v.input=j.at("input").get<Input>();
}
void to_json(json& j, const MtpTrackerState& v) { j=json::object();
    j["version"]=v.version;
    j["source_id"]=v.source_id;
    j["session_id"]=v.session_id;
    j["clock_id"]=v.clock_id;
    j["sequence"]=std::to_string(v.sequence);
    j["timestamp_ns"]=std::to_string(v.timestamp_ns);
    j["sent_at_ns"]=std::to_string(v.sent_at_ns);
    j["timestamp_kind"]=v.timestamp_kind;
    j["tracker_id"]=v.tracker_id;
    j["presence"]=v.presence;
    j["tracking_state"]=v.tracking_state;
    j["coordinate_space"]=v.coordinate_space;
    j["capabilities"]=v.capabilities;
    if(v.battery) j["battery"]=*v.battery; else j["battery"]=nullptr;
    j["mapping_revision"]=v.mapping_revision;
    j["protocol"]="monaka.tracking"; j["type"]="tracker_state";
}
void from_json(const json& j, MtpTrackerState& v) {
    v.version=j.at("version").get<Version>();
    v.source_id=j.at("source_id").get<std::string>();
    v.session_id=j.at("session_id").get<std::string>();
    v.clock_id=j.at("clock_id").get<std::string>();
    v.sequence=std::stoll(j.at("sequence").get<std::string>());
    v.timestamp_ns=std::stoll(j.at("timestamp_ns").get<std::string>());
    v.sent_at_ns=std::stoll(j.at("sent_at_ns").get<std::string>());
    v.timestamp_kind=j.at("timestamp_kind").get<std::string>();
    v.tracker_id=j.at("tracker_id").get<std::string>();
    v.presence=j.at("presence").get<std::string>();
    v.tracking_state=j.at("tracking_state").get<std::string>();
    v.coordinate_space=j.at("coordinate_space").get<CoordinateSpace>();
    v.capabilities=j.at("capabilities").get<std::vector<std::string>>();
    if(j.contains("battery") && !j.at("battery").is_null()) v.battery=j.at("battery").get<Battery>(); else v.battery.reset();
    v.mapping_revision=j.at("mapping_revision").get<std::uint32_t>();
}
