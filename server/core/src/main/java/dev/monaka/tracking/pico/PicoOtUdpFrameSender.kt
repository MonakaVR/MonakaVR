package dev.monaka.tracking.pico

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Producer-side UDP sender for PICO OT authoritative snapshot frames.
 *
 * One call to [send] emits exactly one UTF-8 NDJSON frame in one datagram. The
 * receiver can therefore discard lost or out-of-order packets without reconstructing
 * stream state.
 */
class PicoOtUdpFrameSender(
	private val remoteAddress: InetAddress,
	private val remotePort: Int,
	private val maxDatagramBytes: Int = 65_507,
) : PicoOtFrameSender, AutoCloseable {
	private val socket = DatagramSocket()
	private val sentFramesCounter = AtomicLong()

	init {
		require(remotePort in 1..65_535) { "remotePort must be between 1 and 65535" }
		require(maxDatagramBytes in 256..65_507) {
			"maxDatagramBytes must be between 256 and 65507"
		}
	}

	val sentFrames: Long
		get() = sentFramesCounter.get()

	val isClosed: Boolean
		get() = socket.isClosed

	override fun send(frame: PicoOtTransportFrame): Int {
		check(!socket.isClosed) { "PICO OT UDP sender is closed" }

		val payload = PicoOtJsonWireCodec.encodeLine(frame)
		val bytes = payload.toByteArray(StandardCharsets.UTF_8)
		require(bytes.size <= maxDatagramBytes) {
			"PICO OT UDP frame is ${bytes.size} bytes; configured maximum is $maxDatagramBytes"
		}

		socket.send(DatagramPacket(bytes, bytes.size, remoteAddress, remotePort))
		sentFramesCounter.incrementAndGet()
		return bytes.size
	}

	override fun close() {
		socket.close()
	}
}
