#include "HilAction.hpp"
#include <iostream>
#include <streambuf>

using namespace monaka::hil;
void Check(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
template<class F> void Reject(F f) {
    bool rejected = false;
    try { f(); } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "expected rejection");
}
struct FakeRuntime final : Runtime {
    bool available = true;
    int opens = 0, seated = 0, standing = 0, inspections = 0;
    std::ostringstream* output = nullptr;
    bool Open(std::string& error) override { ++opens; error = "not available"; return available; }
    std::string Inspect() override { ++inspections; return "\"snapshot_atomic\":false"; }
    void BeforeReset() {
        Check(output && output->str().find("request_recorded_before_api_call") != std::string::npos, "request must be written before reset");
        Check(output->str().find("void_return_no_success_status_or_effect_ack") == std::string::npos, "result must follow reset");
    }
    void ResetSeated() override { BeforeReset(); ++seated; }
    void ResetStanding() override { BeforeReset(); ++standing; }
};
int main() {
    try {
        Reject([] { Parse({}); });
        Reject([] { Parse({"--action", "invalid"}); });
        Reject([] { Parse({"--action", "mark"}); });
        Reject([] { Parse({"--action"}); });
        Reject([] { Parse({"--action", "mark", "--label", "x", "--label", "y"}); });
        Reject([] { Parse({"--action", "inspect", "--unknown"}); });
        for (auto action : {"seated-reset", "standing-reset"})
            Reject([&] { Parse({"--action", action}); });
        Ids ids("owner"); auto first = ids.Next(), second = ids.Next();
        Check(first.id == "owner:1" && second.id == "owner:2" && second.ordinal > first.ordinal, "unique monotonic IDs");
        Ids another("other"); Check(another.Next().id != first.id, "owner separation");
        Reject([] { Ids empty(""); });
        auto clock = [] { return Stamp{123,456,789,1000}; };
        std::ostringstream out; FakeRuntime runtime; runtime.output = &out;
        Execute(Parse({"--help", "--action", "seated-reset"}), ids, runtime, out, clock);
        Check(runtime.opens == 0 && out.str().empty(), "help must not touch runtime");
        Execute(Parse({"--action", "mark", "--label", "x\n\"\\"}), ids, runtime, out, clock);
        Check(runtime.opens == 0 && out.str().find("x\\n\\\"\\\\") != std::string::npos, "escaped marker without runtime");
        Check(Json(std::string("\1\xff", 2)) == "\"\\u0001\\u00ff\"", "control/byte JSON escaping");
        out.str(""); runtime.available = false;
        Check(Execute(Parse({"--action", "seated-reset", "--allow-persistent-chaperone-change"}), ids, runtime, out, clock) == 3, "unavailable error exit");
        Check(runtime.seated == 0 && runtime.standing == 0 && runtime.inspections == 0, "fail closed on unavailable runtime");
        Check(out.str().find("runtime_unavailable_no_action") != std::string::npos, "unavailable diagnostic");
        runtime.available = true;
        for (auto action : {"seated-reset", "standing-reset"}) {
            out.str(""); Execute(Parse({"--action", action, "--allow-persistent-chaperone-change"}), ids, runtime, out, clock);
            Check(out.str().find("void_return_no_success_status_or_effect_ack") != std::string::npos, "honest void result");
        }
        Check(runtime.seated == 1 && runtime.standing == 1, "exact reset dispatch");
        out.str(""); Execute(Parse({"--action", "inspect"}), ids, runtime, out, clock);
        Check(runtime.seated == 1 && runtime.standing == 1, "inspection must not reset");
        Options bypass; bypass.action = Action::StandingReset;
        auto opensBefore = runtime.opens;
        Reject([&] { Execute(bypass, ids, runtime, out, clock); });
        Check(runtime.opens == opensBefore, "confirmation checked before runtime");
        std::ostringstream failed; failed.setstate(std::ios::badbit);
        bool writeFailed = false;
        try { Execute(Parse({"--action", "seated-reset", "--allow-persistent-chaperone-change"}), ids, runtime, failed, clock); }
        catch (const std::runtime_error&) { writeFailed = true; }
        Check(writeFailed && runtime.seated == 1, "no reset after logging failure");
        std::cout << "PASS: CLI safety, IDs, JSONL, runtime failure, exact dispatch and request/result ordering\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
