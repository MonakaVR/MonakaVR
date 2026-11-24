package dev.monaka.tracking.mtp

import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.MtpTrackerState
import dev.monaka.protocol.v3.DecodeResult as C3DecodeResult
import dev.monaka.protocol.v3.ErrorCode as C3ErrorCode
import dev.monaka.protocol.v3.MonakaCodec as C3Codec
import dev.monaka.protocol.v3.MtpPose as C3MtpPose
import dev.monaka.protocol.v3.MtpTrackerState as C3MtpTrackerState
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Worker validates complete datagrams; only the server thread updates the pose cache. */
class MtpInbox(private val capacity: Int = DEFAULT_CAPACITY) {
	internal data class Received(
		val message: NormalMtpMessage,
		val receivedAtNanos: Long,
		val peer: String,
		val orderedAcrossTypes: Boolean,
		// Original validated poses retained for diagnostics and legacy consumers.
		val legacyPose: MtpPose? = null,
		val c3Pose: C3MtpPose? = null,
	)
	private val queue = ArrayBlockingQueue<Received>(capacity)
	private val counters = ConcurrentHashMap<String, AtomicLong>()
	fun count(reason: String) {
		counters.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
	}
	fun diagnostics(): Map<String, Long> = counters.mapValues { it.value.get() }

	/** Invoke on the UDP worker (or synchronously in an in-memory test). */
	fun receive(bytes: ByteArray, receivedAtNanos: Long, peer: String = "in-memory"): Boolean {
		require(receivedAtNanos >= 0)
		return when (val decoded = C3Codec.decodeEnvelope(bytes)) {
			is C3DecodeResult.Failure -> {
				if (decoded.code == C3ErrorCode.UnsupportedVersion) receiveC2(bytes, receivedAtNanos, peer)
				else rejected(decoded.code.name)
			}
			is C3DecodeResult.Success -> when (val message = decoded.value) {
				is C3MtpPose -> enqueue(Received(message.toNormalMtpPose(), receivedAtNanos, peer, true, c3Pose = message))
				is C3MtpTrackerState -> enqueue(Received(message.toNormalMtpTrackerState(), receivedAtNanos, peer, true))
				else -> rejected("WrongProtocol")
			}
		}
	}

	private fun receiveC2(bytes: ByteArray, receivedAtNanos: Long, peer: String): Boolean =
		when (val decoded = MonakaCodec.decodeEnvelope(bytes)) {
			is DecodeResult.Failure -> rejected(decoded.code.name)
			is DecodeResult.Success -> when (val message = decoded.value) {
				is MtpPose -> enqueue(Received(message.toNormalMtpPose(), receivedAtNanos, peer, false, message))
				is MtpTrackerState -> enqueue(Received(message.toNormalMtpTrackerState(), receivedAtNanos, peer, false))
				else -> rejected("WrongProtocol")
			}
		}

	private fun enqueue(received: Received): Boolean {
		if (!queue.offer(received)) return rejected("QueueFull")
		count("Validated")
		return true
	}

	private fun rejected(reason: String): Boolean {
		count(reason)
		return false
	}
	init {
		require(capacity > 0) { "capacity must be positive" }
	}

	internal fun drain(limit: Int = NORMAL_DRAIN_LIMIT): List<Received> = buildList {
		require(limit >= 0) { "limit must be non-negative" }
		repeat(limit) { add(queue.poll() ?: return@buildList) }
	}

	/** Resume-only bounded drain. The server thread admits every returned envelope. */
	internal fun drainAllBounded(): List<Received> = ArrayList<Received>(capacity).also {
		// ArrayBlockingQueue drains the queue contents held at this transition under
		// its queue lock. Producers may enqueue afterward for the resumed runtime.
		queue.drainTo(it, capacity)
	}

	internal val resumeDrainBound: Int
		get() = capacity

	fun clear() = queue.clear()

	companion object {
		const val DEFAULT_CAPACITY = 1024
		const val NORMAL_DRAIN_LIMIT = 256
	}
}
