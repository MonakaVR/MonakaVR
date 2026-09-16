package dev.monaka.protocol.v1

/** Shared error model; codec failures expose these same code and message fields. */
data class Error(val code: ErrorCode, val message: String)
