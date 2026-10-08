#pragma once
#include "MonakaRawHmdProviderEvidence.hpp"
#include <array>
#include <mutex>
#include <stdexcept>
#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <bcrypt.h>
#elif defined(__APPLE__)
#include <cstdlib>
#else
#include <cerrno>
#include <sys/random.h>
#endif

namespace monaka {
inline std::string NewProviderSessionToken() {
    std::array<unsigned char, 32> bytes{};
#ifdef _WIN32
    if (BCryptGenRandom(nullptr, bytes.data(), static_cast<ULONG>(bytes.size()),
        BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) throw std::runtime_error("provider OS entropy unavailable");
#elif defined(__APPLE__)
    arc4random_buf(bytes.data(), bytes.size());
#else
    size_t offset = 0;
    while (offset < bytes.size()) {
        const auto n = getrandom(bytes.data() + offset, bytes.size() - offset, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) throw std::runtime_error("provider OS entropy unavailable");
        offset += static_cast<size_t>(n);
    }
#endif
    constexpr char hex[] = "0123456789abcdef";
    std::string token;
    token.reserve(64);
    for (auto byte : bytes) { token += hex[byte >> 4]; token += hex[byte & 15]; }
    return token;
}

// No logger, environment, formatting, clock, OpenVR query or wire reference.
// Created before workers; the bridge callbacks and pose thread share this mutex.
class RawHmdProviderDriver {
public:
    explicit RawHmdProviderDriver(RawHmdProviderEvidenceState::TokenFactory factory = NewProviderSessionToken)
        : state_(std::move(factory)) { Reestablish(); }
    void Reestablish() {
        std::lock_guard lock(mutex_);
        StartLocked();
    }
    void Retire() {
        std::lock_guard lock(mutex_);
        state_.RetireSession();
    }
    std::optional<ProviderSessionEpoch> BeginSample() {
        std::lock_guard lock(mutex_);
        if (state_.Lifecycle() == ProviderEvidenceLifecycle::EXHAUSTED) StartLocked();
        return state_.CurrentSession(); // No observation ID allocation by polling.
    }
    std::optional<RawHmdProviderEvidenceSnapshot> Capture(const ProviderSessionEpoch& expected,
        const ProviderHmdPoseSample& sample) {
        std::lock_guard lock(mutex_);
        try { return state_.Capture(expected, sample); }
        catch (...) { state_.RetireSession(); return std::nullopt; }
    }
private:
    void StartLocked() noexcept {
        try { state_.StartSession(); }
        catch (...) { state_.RetireSession(); } // Evidence fails closed; existing sends continue.
    }
    std::mutex mutex_;
    RawHmdProviderEvidenceState state_;
};
} // namespace monaka
