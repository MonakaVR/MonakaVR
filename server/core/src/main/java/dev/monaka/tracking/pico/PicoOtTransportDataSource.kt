package dev.monaka.tracking.pico

/**
 * Stateful bridge from logical transport frames to the raw snapshot contract used
 * by [PicoOtObservationBackend].
 *
 * The bridge enforces protocol/session sequencing, converts the advertised transport
 * coordinate convention into Monaka's canonical pose space, and carries sparse
 * battery updates forward while a tracker remains present. Pose timestamps are
 * assigned from the Monaka monotonic poll time only when a new transport frame is
 * accepted, so repeated polls of the same frame still age out normally.
 */
class PicoOtTransportDataSource(
	private val frameProvider: PicoOtTransportFrameProvider,
	private val supportedProtocolVersion: Int = PICO_OT_TRANSPORT_PROTOCOL_VERSION,
) : PicoOtDataSource {
	private var lastFrame: PicoOtTransportFrame? = null
	private var lastSnapshot: PicoOtSnapshot? = null
	private var lastAcceptedObservedAtNanos: Long? = null
	private val batteryByTrackerId = mutableMapOf<String, Int>()
	private val retiredSessionIds = mutableSetOf<String>()

	init {
		require(supportedProtocolVersion > 0) { "supportedProtocolVersion must be positive" }
	}

	val sessionId: String?
		get() = lastFrame?.sessionId

	val trackingSpaceId: String?
		get() = lastFrame?.trackingSpaceId

	val coordinateConvention: PicoOtCoordinateConvention?
		get() = lastFrame?.coordinateConvention

	val sequence: Long?
		get() = lastFrame?.sequence

	val trackerCount: Int
		get() = lastSnapshot?.trackers?.size ?: 0

	override fun observationTimestampNanos(requestedAtNanos: Long): Long =
		lastAcceptedObservedAtNanos ?: requestedAtNanos

	override fun snapshot(observedAtNanos: Long): PicoOtSnapshot {
		require(observedAtNanos >= 0L) { "observedAtNanos must be non-negative" }

		val frame = frameProvider.latestFrame()
		require(frame.protocolVersion == supportedProtocolVersion) {
			"Unsupported PICO OT transport protocol ${frame.protocolVersion}; expected $supportedProtocolVersion"
		}

		val previous = lastFrame
		if (previous != null && frame.sessionId == previous.sessionId) {
			require(frame.sequence >= previous.sequence) {
				"Out-of-order PICO OT transport frame ${frame.sequence}; last sequence was ${previous.sequence}"
			}
			if (frame.sequence == previous.sequence) {
				require(frame == previous) {
					"PICO OT transport payload changed without advancing sequence ${frame.sequence}"
				}
				return requireNotNull(lastSnapshot)
			}
		} else if (previous != null) {
			require(frame.sessionId !in retiredSessionIds) {
				"Retired PICO OT transport session '${frame.sessionId}' cannot become active again"
			}
			retiredSessionIds += previous.sessionId
			batteryByTrackerId.clear()
		}

		val canonicalTrackers = frame.trackers.map { tracker ->
			PicoOtCoordinateConverter.toMonaka(tracker, frame.coordinateConvention)
		}
		val presentIds = canonicalTrackers.mapTo(mutableSetOf()) { it.trackerId }
		batteryByTrackerId.keys.retainAll(presentIds)

		val enrichedTrackers = canonicalTrackers.map { tracker ->
			tracker.batteryPercent?.let { batteryByTrackerId[tracker.trackerId] = it }
			tracker.copy(batteryPercent = tracker.batteryPercent ?: batteryByTrackerId[tracker.trackerId])
		}
		val snapshot = PicoOtSnapshot(enrichedTrackers)

		lastFrame = frame
		lastSnapshot = snapshot
		lastAcceptedObservedAtNanos = observedAtNanos
		return snapshot
	}
}
