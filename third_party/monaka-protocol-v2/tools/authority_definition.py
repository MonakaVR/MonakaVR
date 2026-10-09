"""Wire 2.1 definitions consumed by generate.py; no runtime state."""
import hashlib
import json

MESSAGES = {
    'TrustedHmdSourceAuthority': ('monaka.hmd_authority', 'source_authority'),
    'TrustedHmdSourcePose': ('monaka.hmd_authority', 'source_pose'),
    'TrustedHmdSourceUnavailable': ('monaka.hmd_authority', 'source_unavailable'),
    'TrustedHmdSourceRevocation': ('monaka.hmd_authority', 'source_revocation'),
    'CommonWorldAuthorityPublication': ('monaka.common_world', 'world_authority'),
    'CommonWorldMappingPublication': ('monaka.common_world', 'mapping_publication'),
    'TrustedHmdCommonPose': ('monaka.common_world', 'common_pose'),
    'TrustedHmdCommonUnavailable': ('monaka.common_world', 'common_unavailable'),
    'CommonWorldMappingRevocation': ('monaka.common_world', 'mapping_revocation'),
    'CommonWorldRevocation': ('monaka.common_world', 'world_revocation'),
}
UNAVAILABLE = ['tracking_lost', 'position_invalid', 'orientation_invalid', 'pose_unavailable']
SOURCE_REVOKE = ['event_stream_lost', 'source_session_loss', 'source_space_ambiguous', 'authority_withdrawn', 'capacity_exhausted']
MAPPING_REVOKE = ['mapping_withdrawn', 'mapping_conflict', 'source_space_changed', 'calibration_invalidated']
WORLD_REVOKE = ['anchor_authority_lost', 'source_space_changed', 'source_session_changed', 'event_stream_lost', 'explicit_world_replacement', 'capacity_exhausted']

def define(defs, model, fields, ref, enum, messages, put, root):
    defs['I64'] = {'type': 'string', 'pattern': r'^(0|[1-9][0-9]*|-[1-9][0-9]*)$', 'maxLength': 20}
    for n, values in [('UnavailableReason', UNAVAILABLE), ('SourceRevocationReason', SOURCE_REVOKE), ('MappingRevocationReason', MAPPING_REVOKE), ('WorldRevocationReason', WORLD_REVOKE)]:
        defs[n] = enum(*values)
    defs['SourceSpaceKind'] = enum('openxr_view_in_stage')
    header = fields(version='Version', publisher_id='Id', session_id='Uuid', clock_id='Uuid', sequence='U63', timestamp_ns='U63', sent_at_ns='U63', timestamp_kind='TimestampKind')
    model('AuthorityHeader', header)
    model('SourceSpaceAuthority', fields(source_id='Id', source_authority_session_epoch='Id', source_space_id='Id', source_space_generation='U63'))
    model('SourceObservationIdentity', fields(source_space='SourceSpaceAuthority', observation_id='U63', source_locate_time_ns='I64', source_time_domain_id='Id'))
    model('HmdValidityEvidence', fields(**{n: 'Bool' for n in ['position_valid', 'orientation_valid', 'position_tracked', 'orientation_tracked', 'view_position_valid', 'view_orientation_valid']}))
    model('RigidTransform', fields(rotation_xyzw='QuatXyzw', translation_xyz='Vec3'))
    model('CommonWorldReference', fields(owner_id='Id', world_epoch='Id', coordinate_space='CoordinateSpace'))
    model('CommonMappingReference', fields(world='CommonWorldReference', source_space='SourceSpaceAuthority', calibration_epoch='Id', mapping_revision='UInt32'))
    payloads = {
        'TrustedHmdSourceAuthority': fields(source_space='SourceSpaceAuthority', source_space_kind='SourceSpaceKind', source_time_domain_id='Id'),
        'TrustedHmdSourcePose': fields(source='SourceObservationIdentity', position='Vec3', orientation='QuatXyzw', validity='HmdValidityEvidence'),
        'TrustedHmdSourceUnavailable': fields(source_space='SourceSpaceAuthority', reason='UnavailableReason', validity='HmdValidityEvidence?'),
        'TrustedHmdSourceRevocation': fields(source_space='SourceSpaceAuthority', reason='SourceRevocationReason'),
        'CommonWorldAuthorityPublication': fields(world='CommonWorldReference', anchor_source='SourceSpaceAuthority'),
        'CommonWorldMappingPublication': fields(mapping='CommonMappingReference', transform='RigidTransform'),
        'TrustedHmdCommonPose': fields(mapping='CommonMappingReference', source='SourceObservationIdentity', source_position='Vec3', source_orientation='QuatXyzw', validity='HmdValidityEvidence', common_position='Vec3', common_orientation='QuatXyzw'),
        'TrustedHmdCommonUnavailable': fields(mapping='CommonMappingReference', reason='UnavailableReason', validity='HmdValidityEvidence?'),
        'CommonWorldMappingRevocation': fields(mapping='CommonMappingReference', reason='MappingRevocationReason'),
        'CommonWorldRevocation': fields(world='CommonWorldReference', reason='WorldRevocationReason'),
    }
    for n, (p, t) in MESSAGES.items():
        model(n, header + payloads[n])
        for key, value in [('protocol', p), ('type', t)]:
            defs[n]['properties'][key] = {'type': 'string', 'const': value}
            defs[n]['required'].append(key)
        defs[n]['properties']['version'] = {'type': 'object', 'properties': {'major': {'type': 'integer', 'const': 2}, 'minor': {'type': 'integer', 'minimum': 1, 'maximum': 65535}}, 'required': ['major', 'minor'], 'additionalProperties': True}
    # The frozen schemas have already been emitted by generate.py.
    schema = {'$schema': 'https://json-schema.org/draft/2020-12/schema', '$id': 'https://monaka.dev/schema/v2.1/trusted-hmd-authority.schema.json', '$comment': 'C2.1 SHA256 ' + hashlib.sha256((root/'docs/C2.1.md').read_bytes()).hexdigest(), 'oneOf': [ref(n) for n in MESSAGES], '$defs': defs}
    put('schema/trusted-hmd-authority.schema.json', json.dumps(schema, indent=2) + '\n')
    messages.update(MESSAGES)

def cpp_codec(text):
    text = text.replace('if(name=="U63") {', 'if(name=="I64") { auto x=v.get<std::string>(); try { (void)std::stoll(x); } catch(...) { fail(ErrorCode::OutOfRange,path); } }\n        if(name=="U63") {')
    dispatch = '\n'.join(f'    if(p=="{p}" && t=="{t}") {{ need(j["version"].contains("minor"),ErrorCode::MissingField,"version.minor"); need(j["version"]["minor"].is_number(),ErrorCode::InvalidType,"version.minor"); auto m=j["version"]["minor"].get<double>(); need(std::isfinite(m)&&std::floor(m)==m,ErrorCode::InvalidType,"version.minor"); need(m>=0&&m<=65535,ErrorCode::OutOfRange,"version.minor"); need(m>=1,ErrorCode::UnsupportedVersion,"authority minor"); return "{n}"; }}' for n,(p,t) in MESSAGES.items())
    text = text.replace('    fail(ErrorCode::UnsupportedMessage,"protocol/type");', dispatch+'\n    fail(ErrorCode::UnsupportedMessage,"protocol/type");')
    text = text.replace('void semantics(const json& j) {', '''void authoritySemantics(const json& j) {
    auto unit=[](const json& q) { double n=0; for(const auto& x:q) n=std::hypot(n,x.get<double>()); need(std::abs(n-1)<=1e-5,ErrorCode::InvalidQuaternion,"authority quaternion"); };
    auto nonblank=[](const json& x) { const auto s=x.get<std::string>(); bool content=false; for(std::size_t i=0;i<s.size();) { unsigned cp=(unsigned char)s[i++]; if(cp>=128) { unsigned count=cp<224?1:cp<240?2:3; cp&=count==1?31:count==2?15:7; while(count--) cp=(cp<<6)|((unsigned char)s[i++]&63); } bool ws=cp==32||cp==160||cp==5760||(cp>=8192&&cp<=8202)||cp==8232||cp==8233||cp==8239||cp==8287||cp==12288; content|=!ws; } need(content,ErrorCode::OutOfRange,"blank authority Id"); };
    auto source=[&](const json& s) { for(auto k:{"source_id","source_authority_session_epoch","source_space_id"}) nonblank(s.at(k)); };
    auto world=[&](const json& w) { nonblank(w.at("owner_id")); nonblank(w.at("world_epoch")); nonblank(w["coordinate_space"]["id"]); need(w["coordinate_space"]["convention"]=="rh_y_up_neg_z_forward",ErrorCode::UnsupportedValue,"world convention"); };
    nonblank(j.at("publisher_id"));
    if(j.contains("source_space")) source(j["source_space"]);
    if(j.contains("anchor_source")) source(j["anchor_source"]);
    if(j.contains("source")) { source(j["source"]["source_space"]); nonblank(j["source"]["source_time_domain_id"]); }
    if(j.contains("source_time_domain_id")) nonblank(j["source_time_domain_id"]);
    if(j.contains("world")) world(j["world"]);
    if(j.contains("mapping")) { const auto& m=j["mapping"]; world(m["world"]); source(m["source_space"]); nonblank(m["calibration_epoch"]); }
    for(auto k:{"orientation","source_orientation","common_orientation"}) if(j.contains(k)) unit(j[k]);
    if(j.contains("transform")) unit(j["transform"]["rotation_xyzw"]);
    if(j.contains("source") && j.contains("mapping")) for(auto k:{"source_id","source_authority_session_epoch","source_space_id","source_space_generation"}) need(j["source"]["source_space"][k]==j["mapping"]["source_space"][k],ErrorCode::InconsistentValidity,"source/mapping authority mismatch");
    // Flags are exact source facts, including false/tracked combinations. No inferred validity.
}
void semantics(const json& j) {''')
    text = text.replace('    auto has=[&]', '    if(j["protocol"]=="monaka.hmd_authority" || j["protocol"]=="monaka.common_world") { authoritySemantics(j); return; }\n    auto has=[&]')
    text = text.replace('        else result=j.get<MtpTrackerState>();', '        else if(name=="MtpTrackerState") result=j.get<MtpTrackerState>();\n'+'\n'.join(f'        else if(name=="{n}") result=j.get<{n}>();' for n in MESSAGES))
    text = text.replace('j["version"]["minor"]=0;', 'j["version"]["minor"]=(j["protocol"]=="monaka.hmd_authority" || j["protocol"]=="monaka.common_world")?1:0;')
    return text

def kt_codec(text):
    text = text.replace('if(name == "U63")', 'if(name == "I64") need(v.asString.toLongOrNull()!=null,ErrorCode.OutOfRange,path)\n        if(name == "U63")')
    text = text.replace('        else -> fail(ErrorCode.UnsupportedMessage,"protocol/type")', '\n'.join(f'        "{p}" to "{t}" -> {{ need(ver.has("minor"),ErrorCode.MissingField,"version.minor"); need(ver["minor"].number(),ErrorCode.InvalidType,"version.minor"); val m=ver["minor"].asDouble; need(m.isFinite()&&floor(m)==m,ErrorCode.InvalidType,"version.minor"); need(m>=0&&m<=65535,ErrorCode.OutOfRange,"version.minor"); need(m>=1,ErrorCode.UnsupportedVersion,"authority minor"); "{n}" }}' for n,(p,t) in MESSAGES.items())+'\n        else -> fail(ErrorCode.UnsupportedMessage,"protocol/type")')
    text = text.replace('private fun semantics(j: JsonObject) {', '''private fun authoritySemantics(j: JsonObject) {
    fun nonblank(x: JsonElement) { need(x.asString.isNotBlank(),ErrorCode.OutOfRange,"blank authority Id") }
    fun source(s: JsonObject) { for(k in listOf("source_id","source_authority_session_epoch","source_space_id")) nonblank(s[k]) }
    fun world(w: JsonObject) { nonblank(w["owner_id"]); nonblank(w["world_epoch"]); val c=w["coordinate_space"].asJsonObject; nonblank(c["id"]); need(c["convention"].asString=="rh_y_up_neg_z_forward",ErrorCode.UnsupportedValue,"world convention") }
    fun unit(q: JsonElement) { val n=q.asJsonArray.fold(0.0) { a,x -> hypot(a,x.asDouble) }; need(abs(n-1)<=1e-5,ErrorCode.InvalidQuaternion,"authority quaternion") }
    nonblank(j["publisher_id"])
    if(j.has("source_space")) source(j["source_space"].asJsonObject)
    if(j.has("anchor_source")) source(j["anchor_source"].asJsonObject)
    if(j.has("source")) { val s=j["source"].asJsonObject; source(s["source_space"].asJsonObject); nonblank(s["source_time_domain_id"]) }
    if(j.has("source_time_domain_id")) nonblank(j["source_time_domain_id"])
    if(j.has("world")) world(j["world"].asJsonObject)
    if(j.has("mapping")) { val m=j["mapping"].asJsonObject; world(m["world"].asJsonObject); source(m["source_space"].asJsonObject); nonblank(m["calibration_epoch"]) }
    for(k in listOf("orientation","source_orientation","common_orientation")) if(j.has(k)) unit(j[k])
    if(j.has("transform")) unit(j["transform"].asJsonObject["rotation_xyzw"])
    if(j.has("source")&&j.has("mapping")) for(k in listOf("source_id","source_authority_session_epoch","source_space_id","source_space_generation")) need(j["source"].asJsonObject["source_space"].asJsonObject[k]==j["mapping"].asJsonObject["source_space"].asJsonObject[k],ErrorCode.InconsistentValidity,"source/mapping authority mismatch")
}
private fun semantics(j: JsonObject) {''')
    text = text.replace('    val caps =', '    if(j["protocol"].asString in listOf("monaka.hmd_authority","monaka.common_world")) { authoritySemantics(j); return }\n    val caps =')
    text = text.replace('            else -> readMtpTrackerState(j)', '            "MtpTrackerState" -> readMtpTrackerState(j)\n'+'\n'.join(f'            "{n}" -> read{n}(j)' for n in MESSAGES)+'\n            else -> fail(ErrorCode.UnsupportedMessage,"protocol/type")')
    text = text.replace('            is MtpPose -> value.toJson(); is MtpTrackerState -> value.toJson()', '            is MtpPose -> value.toJson(); is MtpTrackerState -> value.toJson()\n'+'\n'.join(f'            is {n} -> value.toJson()' for n in MESSAGES))
    text = text.replace('addProperty("minor",0)', 'addProperty("minor",if(j["protocol"].asString in listOf("monaka.hmd_authority","monaka.common_world")) 1 else 0)')
    return text
