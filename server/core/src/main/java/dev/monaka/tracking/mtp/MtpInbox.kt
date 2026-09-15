package dev.monaka.tracking.mtp

import dev.monaka.protocol.v1.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Worker validates complete datagrams; only the server thread updates the pose cache. */
class MtpInbox(private val capacity: Int = DEFAULT_CAPACITY) {
	data class Received(val envelope: Envelope, val receivedAtNanos: Long)
	private val queue = ArrayBlockingQueue<Received>(capacity)
	private val counters = ConcurrentHashMap<String, AtomicLong>()
	fun count(reason: String) { counters.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet() }
	fun diagnostics(): Map<String, Long> = counters.mapValues { it.value.get() }

	/** Invoke on the UDP worker (or synchronously in an in-memory test). */
	fun receive(bytes: ByteArray, receivedAtNanos: Long): Boolean {
		require(receivedAtNanos >= 0)
		return when (val decoded = MonakaCodec.decodeEnvelope(bytes)) {
			is DecodeResult.Failure -> { count(decoded.code.name); false }
			is DecodeResult.Success -> {
				if (decoded.value !is MtpPose && decoded.value !is MtpTrackerState) {
					count("WrongProtocol"); false
				} else if (!queue.offer(Received(decoded.value, receivedAtNanos))) {
					count("QueueFull"); false
				} else { count("Validated"); true }
			}
		}
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
