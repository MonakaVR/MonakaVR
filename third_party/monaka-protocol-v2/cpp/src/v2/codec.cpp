#include "monaka/protocol/v2/codec.hpp"
#include "nlohmann/json.hpp"
#include <cmath>
#include <regex>
#include <set>
#include <stdexcept>

namespace monaka::protocol::v2 {
using json = nlohmann::json;
#include "models_json.inc"
namespace {
#include "schema.inc"
struct Failure { ErrorCode code; std::string message; };
[[noreturn]] void fail(ErrorCode c, const std::string& m) { throw Failure{c,m}; }
void need(bool ok, ErrorCode c, const std::string& m) { if(!ok) fail(c,m); }
bool validUtf8(const std::uint8_t* p, std::size_t n) {
    for(std::size_t i=0;i<n;) {
        auto a=p[i++]; if(a<0x80) continue;
        unsigned cp=0, count=0, minimum=0;
        if(a>=0xc2 && a<=0xdf) { cp=a&31; count=1; minimum=0x80; }
        else if(a>=0xe0 && a<=0xef) { cp=a&15; count=2; minimum=0x800; }
        else if(a>=0xf0 && a<=0xf4) { cp=a&7; count=3; minimum=0x10000; }
        else return false;
        if(n-i<count) return false;
        for(unsigned k=0;k<count;k++) { auto b=p[i++]; if((b&0xc0)!=0x80) return false; cp=(cp<<6)|(b&63); }
        if(cp<minimum || cp>0x10ffff || (cp>=0xd800 && cp<=0xdfff)) return false;
    }
    return true;
}
void validate(const json& v, const json& s, const std::string& path) {
    if(s.contains("$ref")) {
        auto name=s.at("$ref").get<std::string>().substr(8);
        validate(v,schema.at("$defs").at(name),path);
        if(name=="U63") {
            auto x=v.get<std::string>();
            need(x.size()<19 || x<="9223372036854775807",ErrorCode::OutOfRange,path);
        }
        if(name=="Id") need(v.get_ref<const std::string&>().size()<=96,ErrorCode::OutOfRange,path);
        return;
    }
    if(s.contains("anyOf")) { if(v.is_null()) return; validate(v,s.at("anyOf")[0],path); return; }
    const auto type=s.value("type","");
    bool ok = type=="object" ? v.is_object() : type=="array" ? v.is_array() : type=="string" ? v.is_string() : type=="boolean" ? v.is_boolean() : type=="null" ? v.is_null() : v.is_number();
    need(ok,ErrorCode::InvalidType,path);
    if(type=="object") {
        for(const auto& key:s.at("required")) need(v.contains(key.get<std::string>()),ErrorCode::MissingField,path+"."+key.get<std::string>());
        for(auto it=s.at("properties").begin();it!=s.at("properties").end();++it)
            if(v.contains(it.key())) validate(v.at(it.key()),it.value(),path+"."+it.key());
    } else if(type=="array") {
        if(s.contains("minItems")) need(v.size()>=s.at("minItems").get<std::size_t>(),ErrorCode::OutOfRange,path);
        if(s.contains("maxItems")) need(v.size()<=s.at("maxItems").get<std::size_t>(),ErrorCode::OutOfRange,path);
        for(const auto& x:v) validate(x,s.at("items"),path+"[]");
        if(s.value("uniqueItems",false)) for(std::size_t i=0;i<v.size();i++) for(std::size_t k=0;k<i;k++) need(v[i]!=v[k],ErrorCode::InconsistentValidity,path);
    } else if(type=="string") {
        const auto& x=v.get_ref<const std::string&>();
        // JSON Schema length counts code points. The Id reference additionally checks bytes.
        std::size_t length=0; for(unsigned char c:x) if((c&0xc0)!=0x80) ++length;
        if(s.contains("minLength")) need(length>=s.at("minLength").get<std::size_t>(),ErrorCode::OutOfRange,path);
        if(s.contains("maxLength")) need(length<=s.at("maxLength").get<std::size_t>(),ErrorCode::OutOfRange,path);
        if(s.contains("pattern")) {
            const auto pattern=s.at("pattern").get<std::string>();
            if(pattern.find("001f")!=std::string::npos) {
                for(std::size_t i=0;i<x.size();i++) {
                    unsigned char c=x[i];
                    need(c>=32 && c!=127 && !(c==0xc2 && i+1<x.size() && (unsigned char)x[i+1]>=0x80 && (unsigned char)x[i+1]<=0x9f),ErrorCode::OutOfRange,path);
                }
            } else need(std::regex_match(x,std::regex(pattern)),ErrorCode::OutOfRange,path);
        }
    } else if(type=="number" || type=="integer") {
        double x=v.get<double>(); need(std::isfinite(x),ErrorCode::OutOfRange,path);
        if(type=="integer") need(std::floor(x)==x,ErrorCode::InvalidType,path);
        if(s.contains("minimum")) need(x>=s.at("minimum").get<double>(),ErrorCode::OutOfRange,path);
        if(s.contains("maximum")) need(x<=s.at("maximum").get<double>(),ErrorCode::OutOfRange,path);
    }
    if(s.contains("enum")) { bool found=false; for(const auto& x:s.at("enum")) found|=v==x; need(found,ErrorCode::UnsupportedValue,path); }
    if(s.contains("const")) need(v==s.at("const"),ErrorCode::UnsupportedValue,path);
}
std::string dispatch(const json& j) {
    need(j.is_object(),ErrorCode::InvalidType,"envelope");
    for(auto k:{"protocol","type","version"}) need(j.contains(k),ErrorCode::MissingField,k);
    need(j.at("protocol").is_string() && j.at("type").is_string() && j.at("version").is_object(),ErrorCode::InvalidType,"dispatch");
    need(j["version"].contains("major"),ErrorCode::MissingField,"version.major");
    need(j["version"]["major"].is_number(),ErrorCode::InvalidType,"version.major");
    const double major=j["version"]["major"].get<double>();
    need(std::isfinite(major) && std::floor(major)==major,ErrorCode::InvalidType,"version.major");
    need(major>=0 && major<=65535,ErrorCode::OutOfRange,"version.major");
    need(major==2,ErrorCode::UnsupportedVersion,"version.major");
    const auto p=j["protocol"].get<std::string>(), t=j["type"].get<std::string>();
    if(p=="monaka.observation" && t=="pose") return "TrackerObservation";
    if(p=="monaka.observation" && t=="device_state") return "ObservationDeviceState";
    if(p=="monaka.tracking" && t=="pose") return "MtpPose";
    if(p=="monaka.tracking" && t=="tracker_state") return "MtpTrackerState";
    fail(ErrorCode::UnsupportedMessage,"protocol/type");
}
void semantics(const json& j) {
    auto u=[](const json& x) { return std::stoll(x.get<std::string>()); };
    need(u(j.at("timestamp_ns"))<=u(j.at("sent_at_ns")),ErrorCode::OutOfRange,"timestamp_ns > sent_at_ns");
    auto has=[&](const std::string& name) { for(const auto& x:j.at("capabilities")) if(x==name) return true; return false; };
    if(j.contains("battery") && !j["battery"].is_null()) {
        need(u(j["battery"]["timestamp_ns"])<=u(j["sent_at_ns"]),ErrorCode::OutOfRange,"battery timestamp > sent_at_ns");
        for(auto pair:{std::pair{"fraction","battery_fraction"},std::pair{"charging","charging"}})
            if(!j["battery"][pair.first].is_null()) need(has(pair.second),ErrorCode::InconsistentValidity,pair.second);
    }
    if(j.contains("input")) need(j["input"]["source_id"]==j["source_id"],ErrorCode::InconsistentValidity,"input.source_id");
    if(j.at("type")!="pose") {
        if(j["presence"]=="absent" || (j["tracking_state"]!="tracked" && j["tracking_state"]!="degraded"))
            need(j["modality"]=="none",ErrorCode::InconsistentValidity,"inactive state modality");
        return;
    }
    bool p=j["validity"]["position"], o=j["validity"]["orientation"];
    const auto modality=j.at("modality");
    need(modality=="full" ? p&&o : modality=="rotation_only" ? !p&&o : !p&&!o,ErrorCode::InconsistentValidity,"modality");
    for(auto pair:{std::pair{"position",p},std::pair{"orientation",o}})
        if(pair.second) need(!j.at(pair.first).is_null() && has(pair.first),ErrorCode::InconsistentValidity,pair.first);
    for(auto name:{"linear_velocity","angular_velocity","linear_acceleration"})
        if(j.contains(name) && !j.at(name).is_null()) need(has(name),ErrorCode::InconsistentValidity,name);
    if(o) {
        double norm=0; for(const auto& x:j.at("orientation")) norm=std::hypot(norm,x.get<double>());
        bool mtp=j.at("protocol")=="monaka.tracking";
        need(mtp ? std::abs(norm-1)<=1e-5 : norm>=0.5 && norm<=1.5,ErrorCode::InvalidQuaternion,"orientation norm");
    }
    if(j.contains("orientation_evidence")) need(o ? j["orientation_evidence"]!="none" : j["orientation_evidence"]=="none",ErrorCode::InconsistentValidity,"orientation_evidence");
    const auto state=j.at("tracking_state");
    need(state=="tracked" ? p&&o : state=="degraded" ? p||o : !p&&!o,ErrorCode::InconsistentValidity,"tracking_state");
    if(j.contains("confidence")) {
        need(p ? j["confidence"]["position"].get<double>()>0 : j["confidence"]["position"]==0,ErrorCode::InconsistentValidity,"confidence.position");
        need(o ? j["confidence"]["orientation"].get<double>()>0 : j["confidence"]["orientation"]==0,ErrorCode::InconsistentValidity,"confidence.orientation");
    }
}
// nlohmann serializes NaN as null: reject it before serialization, including diagnostics.
void finiteTree(const json& j) {
    if(j.is_number_float()) need(std::isfinite(j.get<double>()),ErrorCode::OutOfRange,"nonfinite number");
    if(j.is_structured()) for(const auto& x:j) finiteTree(x);
}
}
const char* ErrorCodeName(ErrorCode c) noexcept {
    switch(c) {
#define E(x) case ErrorCode::x: return #x;
    E(MalformedJson) E(InvalidUtf8) E(DuplicateKey) E(TooLarge) E(UnsupportedVersion)
    E(UnsupportedMessage) E(UnsupportedValue) E(MissingField) E(InvalidType)
    E(OutOfRange) E(InvalidQuaternion) E(InconsistentValidity)
#undef E
    }
    return "MalformedJson";
}
bool DecodeEnvelope(const std::uint8_t* data, std::size_t size, Envelope& out, Error& error) {
    try {
        need(size<=4096,ErrorCode::TooLarge,"datagram exceeds 4096 bytes");
        need(data!=nullptr || size==0,ErrorCode::MalformedJson,"null data");
        need(validUtf8(data,size),ErrorCode::InvalidUtf8,"invalid UTF-8");
        need(!(size>=3 && data[0]==0xef && data[1]==0xbb && data[2]==0xbf),ErrorCode::MalformedJson,"BOM");
        need(size>0,ErrorCode::MalformedJson,"empty input");
        std::vector<std::set<std::string>> objects; int depth=0;
        auto cb=[&](int, json::parse_event_t event,json& parsed) {
            if(event==json::parse_event_t::object_start || event==json::parse_event_t::array_start) need(++depth<=16,ErrorCode::MalformedJson,"depth exceeds 16");
            if(event==json::parse_event_t::object_start) objects.emplace_back();
            if(event==json::parse_event_t::key) need(objects.back().insert(parsed.get<std::string>()).second,ErrorCode::DuplicateKey,"duplicate object key");
            if(event==json::parse_event_t::object_end) objects.pop_back();
            if(event==json::parse_event_t::object_end || event==json::parse_event_t::array_end) --depth;
            return true;
        };
        json j=json::parse(data,data+size,cb);
        auto name=dispatch(j); validate(j,schema.at("$defs").at(name),"envelope"); semantics(j);
        Envelope result;
        if(name=="TrackerObservation") result=j.get<TrackerObservation>();
        else if(name=="ObservationDeviceState") result=j.get<ObservationDeviceState>();
        else if(name=="MtpPose") result=j.get<MtpPose>();
        else result=j.get<MtpTrackerState>();
        out=std::move(result); error.message.clear(); return true;
    } catch(const Failure& f) { error={f.code,f.message}; }
      catch(const json::out_of_range& e) { error={ErrorCode::OutOfRange,e.what()}; }
      catch(const std::exception& e) { error={ErrorCode::MalformedJson,e.what()}; }
    return false;
}
bool EncodeEnvelope(const Envelope& value, std::string& utf8, Error& error) {
    try {
        json j=std::visit([](const auto& v) { return json(v); },value);
        finiteTree(j);
        auto name=dispatch(j); validate(j,schema.at("$defs").at(name),"envelope"); semantics(j);
        j["version"]["minor"]=0;
        std::string encoded=j.dump();
        Envelope checked;
        if(!DecodeEnvelope(reinterpret_cast<const std::uint8_t*>(encoded.data()),encoded.size(),checked,error)) return false;
        utf8=std::move(encoded); error.message.clear(); return true;
    } catch(const Failure& f) { error={f.code,f.message}; }
      catch(const json::type_error& e) { error={ErrorCode::InvalidUtf8,e.what()}; }
      catch(const std::exception& e) { error={ErrorCode::MalformedJson,e.what()}; }
    return false;
}
}
