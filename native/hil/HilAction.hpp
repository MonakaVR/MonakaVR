#pragma once
#include <chrono>
#include <cstdint>
#include <iomanip>
#include <limits>
#include <ostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace monaka::hil {
enum class Action { Mark, Inspect, SeatedReset, StandingReset };
struct Options {
    bool help = false;
    bool allowPersistent = false;
    Action action = Action::Mark;
    std::string actionName;
    std::string label;
};
inline Options Parse(const std::vector<std::string>& args) {
    Options o;
    bool haveAction = false, haveLabel = false;
    for (size_t i = 0; i < args.size(); ++i) {
        const auto& arg = args[i];
        if (arg == "--help") { o.help = true; }
        else if (arg == "--allow-persistent-chaperone-change") {
            if (o.allowPersistent) throw std::invalid_argument("duplicate confirmation flag");
            o.allowPersistent = true;
        } else if (arg == "--action" || arg == "--label") {
            if (++i == args.size()) throw std::invalid_argument("missing option value");
            if (arg == "--action") {
                if (haveAction) throw std::invalid_argument("duplicate action");
                haveAction = true; o.actionName = args[i];
            } else {
                if (haveLabel) throw std::invalid_argument("duplicate label");
                haveLabel = true; o.label = args[i];
            }
        } else throw std::invalid_argument("unknown option: " + arg);
    }
    if (o.help) return o; // Never opens a runtime or invokes an action.
    if (!haveAction) throw std::invalid_argument("explicit --action required");
    if (o.actionName == "mark") o.action = Action::Mark;
    else if (o.actionName == "inspect") o.action = Action::Inspect;
    else if (o.actionName == "seated-reset") o.action = Action::SeatedReset;
    else if (o.actionName == "standing-reset") o.action = Action::StandingReset;
    else throw std::invalid_argument("invalid action: " + o.actionName);
    if (o.action == Action::Mark && o.label.empty()) throw std::invalid_argument("mark requires nonempty --label");
    if (o.label.size() > 256) throw std::invalid_argument("label exceeds 256 bytes");
    if ((o.action == Action::SeatedReset || o.action == Action::StandingReset) && !o.allowPersistent)
        throw std::invalid_argument("ResetZeroPose overwrites the saved zero pose; --allow-persistent-chaperone-change required");
    return o;
}
inline std::string Json(std::string_view value) {
    std::ostringstream out; out << '"';
    for (unsigned char c : value) {
        switch (c) {
        case '"': out << "\\\""; break;
        case '\\': out << "\\\\"; break;
        case '\n': out << "\\n"; break;
        case '\r': out << "\\r"; break;
        case '\t': out << "\\t"; break;
        default:
            // ASCII-only JSON ensures even arbitrary command-line bytes cannot break JSONL.
            if (c < 32 || c >= 127) out << "\\u00" << std::hex << std::setw(2) << std::setfill('0') << unsigned(c) << std::dec;
            else out << char(c);
        }
    }
    out << '"'; return out.str();
}
struct Stamp {
    int64_t monotonicNs;
    int64_t wallUnixNs;
    int64_t qpcTicks;
    int64_t qpcFrequency;
};
struct Identity { std::string owner, id; uint64_t ordinal; };
class Ids {
    std::string owner_;
    uint64_t ordinal_ = 0;
public:
    explicit Ids(std::string owner) : owner_(std::move(owner)) {
        if (owner_.empty()) throw std::invalid_argument("empty helper owner");
    }
    Identity Next() {
        if (ordinal_ == std::numeric_limits<uint64_t>::max()) throw std::overflow_error("action ordinal exhausted");
        ++ordinal_; return {owner_, owner_ + ":" + std::to_string(ordinal_), ordinal_};
    }
};
inline void Record(std::ostream& out, const Identity& id, const Options& o,
                   std::string_view kind, const Stamp& t, std::string_view status,
                   std::string_view extra = {}) {
    out << "MONAKA_HIL_ACTION_V1 {\"kind\":" << Json(kind)
        << ",\"helper_owner\":" << Json(id.owner) << ",\"action_id\":" << Json(id.id)
        << ",\"action_ordinal\":" << id.ordinal << ",\"action_kind\":" << Json(o.actionName)
        << ",\"label\":" << Json(o.label) << ",\"local_monotonic_ns\":" << t.monotonicNs
        << ",\"local_wall_time\":" << Json(std::to_string(t.wallUnixNs))
        << ",\"wall_time_unit\":\"unix_ns\",\"qpc_ticks\":" << Json(std::to_string(t.qpcTicks))
        << ",\"qpc_frequency\":" << t.qpcFrequency << ",\"api_status\":" << Json(status)
        << ",\"origin_kind\":" << Json(o.action == Action::SeatedReset ? "seated" : o.action == Action::StandingReset ? "standing" : "none")
        << ",\"frame_identity\":false,\"physical_application_time\":false";
    if (!extra.empty()) out << ',' << extra;
    out << "}\n"; out.flush();
    if (!out) throw std::runtime_error("action record write failed; action sequence stopped");
}
struct Runtime {
    virtual ~Runtime() = default;
    virtual bool Open(std::string& error) = 0;
    virtual std::string Inspect() = 0; // JSON member fragment; independent non-atomic client queries.
    virtual void ResetSeated() = 0;
    virtual void ResetStanding() = 0;
};
template<class Clock>
int Execute(const Options& o, Ids& ids, Runtime& runtime, std::ostream& out, Clock clock) {
    if (o.help) return 0;
    // Defense in depth for callers that do not use Parse.
    if ((o.action == Action::SeatedReset || o.action == Action::StandingReset) && !o.allowPersistent)
        throw std::invalid_argument("persistent change not confirmed");
    auto id = ids.Next();
    if (o.action == Action::Mark) {
        Record(out, id, o, "request", clock(), "operator_marker_requested");
        Record(out, id, o, "result", clock(), "operator_marker_recorded_no_runtime_action");
        return 0;
    }
    std::string error;
    if (!runtime.Open(error)) {
        Record(out, id, o, "result", clock(), "runtime_unavailable_no_action", "\"runtime_error\":" + Json(error));
        return 3;
    }
    Record(out, id, o, "pre_state", clock(), "independent_client_snapshot", runtime.Inspect());
    Record(out, id, o, "request", clock(), "request_recorded_before_api_call");
    switch (o.action) {
    case Action::SeatedReset: runtime.ResetSeated(); break;
    case Action::StandingReset: runtime.ResetStanding(); break;
    case Action::Inspect: break;
    case Action::Mark: throw std::logic_error("unreachable action");
    }
    Record(out, id, o, "result", clock(), o.action == Action::Inspect ? "read_only_inspection" : "void_return_no_success_status_or_effect_ack");
    Record(out, id, o, "post_state", clock(), "independent_client_snapshot", runtime.Inspect());
    return 0;
}
} // namespace monaka::hil
