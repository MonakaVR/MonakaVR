package dev.slimevr.desktop.platform

/** One logical accept lifetime, independent of reusable native connections and capability tokens. */
@ConsistentCopyVisibility
data class TransportSessionHandle internal constructor(val epoch: String) {
	init { require(epoch.isNotBlank()) }
}

/** Session is captured by ingress, never assigned from current active state while draining the queue. */
data class InboundProtobufEnvelope(
	val message: ProtobufMessages.ProtobufMessage,
	val transportSession: TransportSessionHandle?,
)
