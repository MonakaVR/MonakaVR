package dev.slimevr.desktop.platform

/** One logical accept lifetime, independent of reusable native connections and capability tokens. */
@ConsistentCopyVisibility
data class TransportSessionHandle internal constructor(val epoch: String) {
	init { require(epoch.isNotBlank()) }
}

/** Session and host monotonic receive time are fixed before queueing, never refreshed on dequeue. */
data class InboundProtobufEnvelope(
	val message: ProtobufMessages.ProtobufMessage,
	val transportSession: TransportSessionHandle?,
	// System.nanoTime domain (or the bridge's injected clock), not device acquisition time.
	val receivedAtSystemNanos: Long,
)
