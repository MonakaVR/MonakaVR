#pragma once
#include <cstddef>
#include "models.hpp"

namespace monaka::protocol::v1 {
enum class ErrorCode {
    MalformedJson, InvalidUtf8, DuplicateKey, TooLarge, UnsupportedVersion,
    UnsupportedMessage, UnsupportedValue, MissingField, InvalidType,
    OutOfRange, InvalidQuaternion, InconsistentValidity
};
struct Error { ErrorCode code{ErrorCode::MalformedJson}; std::string message; };
const char* ErrorCodeName(ErrorCode code) noexcept;
bool DecodeEnvelope(const std::uint8_t* data, std::size_t size, Envelope& out, Error& error);
bool EncodeEnvelope(const Envelope& value, std::string& utf8, Error& error);
}
