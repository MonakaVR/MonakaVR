package dev.monaka.tracking.pico

/**
 * Thread-safe latest-frame inbox for unordered transports such as UDP.
 *
 * The inbox prevents an older datagram from replacing a newer frame before
 * [PicoOtTransportDataSource] polls it. Session ids are one-shot within the inbox
 * lifetime: after switching away from a session, delayed datagrams from that retired
 * session are ignored.
 */
class PicoOtTransportFrameInbox : PicoOtTransportFrameProvider {
	enum class SubmitResult {
		ACCEPTED,
		DUPLICATE,
		STALE,
		RETIRED_SESSION,
	}

	private var currentFrame: PicoOtTransportFrame? = null
	private val retiredSessionIds = mutableSetOf<String>()

	@Synchronized
	fun submit(frame: PicoOtTransportFrame): SubmitResult {
		val current = currentFrame
		if (current == null) {
			currentFrame = frame
			return SubmitResult.ACCEPTED
		}

		if (frame.sessionId == current.sessionId) {
			return when {
				frame.sequence > current.sequence -> {
					currentFrame = frame
					SubmitResult.ACCEPTED
				}
				frame.sequence < current.sequence -> SubmitResult.STALE
				frame == current -> SubmitResult.DUPLICATE
				else -> throw IllegalArgumentException(
					"PICO OT transport payload changed without advancing sequence ${frame.sequence}",
				)
			}
		}

		if (frame.sessionId in retiredSessionIds) {
			return SubmitResult.RETIRED_SESSION
		}

		retiredSessionIds += current.sessionId
		currentFrame = frame
		return SubmitResult.ACCEPTED
	}

	@Synchronized
	override fun latestFrame(): PicoOtTransportFrame =
		currentFrame ?: throw IllegalStateException("No PICO OT transport frame has been received")

	@Synchronized
	fun clear() {
		currentFrame = null
		retiredSessionIds.clear()
	}
}
