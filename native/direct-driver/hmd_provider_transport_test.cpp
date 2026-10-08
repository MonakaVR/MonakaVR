#include "MonakaHmdProviderTransport.hpp"
#include "MonakaRawHmdProviderDriver.hpp"
#include <array>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace monaka;
static void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
namespace monaka {
struct RawHmdProviderEvidenceTestAccess {
    static void Last(RawHmdProviderEvidenceState& s) { s.next_ = INT64_MAX; }
};
struct RawHmdProviderDriverTestAccess {
    static void Last(RawHmdProviderDriver& d) { RawHmdProviderEvidenceTestAccess::Last(d.state_); }
};
}
static auto factory() { return [n=0]() mutable { return "session-" + std::to_string(n++); }; }
static messages::Position position(int id=0) {
    messages::Position p;
    p.set_tracker_id(id); p.set_x(1); p.set_y(2); p.set_z(3); p.set_qw(1);
    p.set_data_source(messages::Position_DataSource_FULL);
    return p;
}
static ProviderHmdPoseSample sample(const messages::Position& p) {
    return {{-0.0f, std::bit_cast<float>(0x7fc12345u), 3, 0, 0, 0, 1},
        {p.x(),p.y(),p.z(),p.qx(),p.qy(),p.qz(),p.qw()},3,false,true,300};
}
static messages::ProtobufMessage query(const std::string& name=HmdProviderTransport::Query) {
    messages::ProtobufMessage q;
    q.mutable_user_action()->set_name(name);
    (*q.mutable_user_action()->mutable_action_arguments())["connection"] = "challenge";
    return q;
}
static std::string hex(const std::string& s) {
    std::string out; constexpr char h[]="0123456789abcdef";
    for (unsigned char c : s) { out+=h[c>>4]; out+=h[c&15]; } return out;
}
int main() {
    try {
        RawHmdProviderDriver d(factory()); HmdProviderTransport t;
        auto p=position(); const auto old=p.SerializeAsString();
        require(hex(old)=="150000803f1d000000402500004040450000803f4803", "pre-5U FULL golden");
        auto ticket=*d.BeginSample(); auto e=d.Capture(ticket,sample(p));
        require(!t.Attach(p,*e) && p.SerializeAsString()==old, "old server/new driver old bytes");
        messages::ProtobufMessage reply;
        require(!t.Confirm(query("monaka-direct-output-v1?"),reply), "Direct independent");
        auto missing=query(); missing.mutable_user_action()->mutable_action_arguments()->clear();
        require(!t.Confirm(missing,reply), "challenge required");
        require(t.Confirm(query(),reply) && reply.user_action().name()==HmdProviderTransport::Reply &&
            reply.user_action().action_arguments().at("connection")=="challenge", "exact echo");
        e.reset(); e.emplace(*d.Capture(ticket,sample(p)));
        require(e->observationId==1 && t.Attach(p,*e), "gaps before negotiation legal");
        messages::Position decoded; require(decoded.ParseFromString(p.SerializeAsString()), "decode");
        const auto& v=decoded.hmd_provider_evidence_v1();
        require(v.provider_session_epoch()==ticket.value && v.observation_id()==1 && v.data_source()==3 &&
            !v.pose_valid() && v.device_connected() && v.tracking_result()==300, "exact facts");
        require(std::bit_cast<uint32_t>(v.raw_x())==0x80000000u &&
            std::bit_cast<uint32_t>(v.raw_y())==0x7fc12345u, "raw signed zero/NaN bits");
        require(v.wire_x()==decoded.x() && v.wire_y()==decoded.y() && v.wire_z()==decoded.z() &&
            v.wire_qx()==decoded.qx() && v.wire_qy()==decoded.qy() && v.wire_qz()==decoded.qz() && v.wire_qw()==decoded.qw(), "wire P/Q");
        std::vector<std::string> order{"status"}; unsigned sends=0;
        const auto send=[&] { ++sends; order.emplace_back("position"); }; send(); order.emplace_back("battery");
        require(sends==1 && order==std::vector<std::string>{"status","position","battery"}, "send order");
        auto other=position(1); require(!t.Attach(other,*e) && !other.has_hmd_provider_evidence_v1(), "non-HMD");
        auto mismatch=position(); mismatch.set_qx(-0.0f); require(!t.Attach(mismatch,*e), "bit mismatch");
        std::thread retire([&] { t.Reset(); d.Retire(); }); retire.join();
        p=position(); require(!t.Attach(p,*e) && p.SerializeAsString()==old && !d.Capture(ticket,sample(p)), "reconnect disables/stale ticket");
        d.Reestablish(); const auto freshTicket=*d.BeginSample(); require(freshTicket.value!=e->providerSession.value, "fresh provider");
        require(t.Confirm(query(),reply), "fresh confirmation");
        RawHmdProviderDriverTestAccess::Last(d); auto last=d.Capture(freshTicket,sample(p));
        require(last->observationId==INT64_MAX && t.Attach(p,*last), "INT64_MAX transport");
        require(!d.Capture(freshTicket,sample(p)), "exhaustion");
        const auto next=*d.BeginSample(); require(next!=freshTicket && d.Capture(next,sample(p))->observationId==0, "fresh required");
        bool fail=false; int n=0;
        RawHmdProviderDriver broken([&]() -> std::string { if(fail) throw std::runtime_error("entropy"); return std::to_string(n++); });
        RawHmdProviderDriverTestAccess::Last(broken);
        broken.Capture(*broken.BeginSample(),sample(p)); fail=true;
        require(!broken.BeginSample(), "fresh entropy failure");
        p=position(); require(!p.has_hmd_provider_evidence_v1() && p.SerializeAsString()==old, "generic after failure");
        std::cout << "PASS negotiated same-Position provider transport / compatibility / exact bits / INT64_MAX\n";
    } catch(const std::exception& e) { std::cerr<<"FAIL "<<e.what()<<'\n'; return 1; }
}
