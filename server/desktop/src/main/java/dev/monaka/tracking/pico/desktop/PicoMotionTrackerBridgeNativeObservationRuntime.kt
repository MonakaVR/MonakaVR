package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.pico.PicoMotionTrackerBridgeCoordinateMapper
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeDataSource
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeReceiverStateProvider
import dev.monaka.tracking.pico.PicoOtObservationBackend
import dev.slimevr.tracking.trackers.TrackerPosition

/**
 * Production composition settings for the desktop PicoMotionTrackerBridge input path.
 *
 * The native receiver remains responsible for UDP decoding, sender-session handling,
 * packet sequencing, and clock synchronization. Monaka owns only the normalized-state
 * adaptation, OutputA coordinate conversion, body assignment, and observation policy.
 */
data class PicoMotionTrackerBridgeNativeObservationRuntimeConfig(
	val receiverConfig: PicoMotionTrackerBridgeNativeReceiverConfig = PicoMotionTrackerBridgeNativeReceiverConfig(),
	val backendId: String = "pico-ot",
	val profileId: String = "pico-sixdof",
	val sourcePrefix: String = "pico-ot",
) {
	init {
		require(backendId.isNotBlank()) { "backendId must not be blank" }
		require(profileId.isNotBlank()) { "profileId must not be blank" }
		require(sourcePrefix.isNotBlank()) { "sourcePrefix must not be blank" }
	}
}

/**
 * Owns one native PicoMotionTrackerBridge Receiver and exposes its Monaka observation backend.
 *
 * This is the production wiring boundary. In particular, the PICO OutputA -> Monaka mapper is
 * deliberately fixed here rather than supplied by callers, preventing the native receiver from
 * accidentally entering the constraint pipeline with identity/Unity coordinate semantics.
 */
class PicoMotionTrackerBridgeNativeObservationRuntime(
	config: PicoMotionTrackerBridgeNativeObservationRuntimeConfig = PicoMotionTrackerBridgeNativeObservationRuntimeConfig(),
	targetResolver: (trackerId: String) -> TrackerPosition?,
) : AutoCloseable {
	private val receiverClient = PicoMotionTrackerBridgeNativeReceiverClient(config.receiverConfig)

	val backend: PicoOtObservationBackend = PicoOtObservationBackend(
		backendId = config.backendId,
		profileId = config.profileId,
		dataSource = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeReceiverStateProvider(receiverClient),
			poseMapper = PicoMotionTrackerBridgeCoordinateMapper,
		),
		targetResolver = targetResolver,
		sourcePrefix = config.sourcePrefix,
	)

	val listenPort: Int
		get() = receiverClient.listenPort

	override fun close() {
		receiverClient.close()
	}
}
