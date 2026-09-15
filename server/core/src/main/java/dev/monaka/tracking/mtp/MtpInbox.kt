package dev.monaka.tracking.mtp

import dev.monaka.protocol.v1.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Worker validates complete datagrams; only the server thread updates the pose cache. */
class MtpInbox(capacity: Int = 1024) {
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
	internal fun drain(limit: Int = 256): List<Received> = buildList {
		repeat(limit) { add(queue.poll() ?: return@buildList) }
	}
	fun clear() = queue.clear()
}
