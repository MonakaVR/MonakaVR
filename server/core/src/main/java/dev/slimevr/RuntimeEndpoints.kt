package dev.slimevr

/** Explicit per-instance listen endpoints. Defaults retain existing desktop behavior. */
data class RuntimeEndpoints(
	val webSocketPort: Int = 21110,
	val trackerPort: Int? = null,
	val loopbackOnly: Boolean = false,
) {
	init {
		require(webSocketPort in 0..65535) { "WebSocket port must be in 0..65535" }
		require(trackerPort == null || trackerPort in 0..65535) { "Tracker port must be in 0..65535" }
	}
}
