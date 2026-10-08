#pragma once
#include "MonakaRawHmdProviderEvidence.hpp"
#include "ProtobufMessages.pb.h"
#include <atomic>
#include <bit>

namespace monaka {
// Bridge writes and pose reads. Lifecycle resets synchronously before connection changes.
class HmdProviderTransport {
public:
    static constexpr const char* Query = "monaka-hmd-provider-evidence-v1?";
    static constexpr const char* Reply = "monaka-hmd-provider-evidence-v1";
    void Reset() { enabled_.store(false); }
    bool Confirm(const messages::ProtobufMessage& query, messages::ProtobufMessage& reply) {
        if (!query.has_user_action() || query.user_action().name() != Query) return false;
        const auto& args = query.user_action().action_arguments();
        const auto token = args.find("connection");
        if (token == args.end() || token->second.empty()) return false;
        *reply.mutable_user_action() = query.user_action();
        reply.mutable_user_action()->set_name(Reply);
        enabled_.store(true);
        return true;
    }
    bool Attach(messages::Position& p, const RawHmdProviderEvidenceSnapshot& e) const {
        if (!enabled_.load() || p.tracker_id() != 0 || !p.has_x() || !p.has_y() || !p.has_z() ||
            !p.has_data_source() || p.data_source() != messages::Position_DataSource_FULL ||
            e.sample.dataSource != static_cast<int32_t>(p.data_source())) return false;
        const auto& w = e.sample.wirePose;
        const auto same = [](float a, float b) { return std::bit_cast<uint32_t>(a) == std::bit_cast<uint32_t>(b); };
        if (!same(w.px,p.x()) || !same(w.py,p.y()) || !same(w.pz,p.z()) || !same(w.qx,p.qx()) ||
            !same(w.qy,p.qy()) || !same(w.qz,p.qz()) || !same(w.qw,p.qw())) return false;
        auto* v = p.mutable_hmd_provider_evidence_v1();
        v->set_provider_session_epoch(e.providerSession.value);
        v->set_observation_id(e.observationId);
        const auto& r = e.sample.rawPose;
        v->set_raw_x(r.px); v->set_raw_y(r.py); v->set_raw_z(r.pz);
        v->set_raw_qx(r.qx); v->set_raw_qy(r.qy); v->set_raw_qz(r.qz); v->set_raw_qw(r.qw);
        v->set_wire_x(w.px); v->set_wire_y(w.py); v->set_wire_z(w.pz);
        v->set_wire_qx(w.qx); v->set_wire_qy(w.qy); v->set_wire_qz(w.qz); v->set_wire_qw(w.qw);
        v->set_data_source(e.sample.dataSource); v->set_pose_valid(e.sample.poseValid);
        v->set_device_connected(e.sample.deviceConnected); v->set_tracking_result(e.sample.trackingResult);
        return true;
    }
private:
    std::atomic<bool> enabled_{false};
};
} // namespace monaka
