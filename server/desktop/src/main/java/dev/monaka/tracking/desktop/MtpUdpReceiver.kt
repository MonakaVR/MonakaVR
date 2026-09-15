package dev.monaka.tracking.desktop

import dev.monaka.tracking.mtp.MtpInbox
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/** Only loopback; tick never performs socket I/O or JSON parsing. */
class MtpUdpReceiver(private val inbox: MtpInbox, private val clock: () -> Long, port: Int = 29811) : AutoCloseable {
	private val socket = DatagramSocket(null)
	@Volatile private var closed = false
	@Volatile var failure: Exception? = null
		private set
	private val worker: Thread
	val port: Int get() = socket.localPort
	val isAlive: Boolean get() = worker.isAlive
	init {
		try {
			socket.reuseAddress = false
			socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
		} catch (e: Exception) { socket.close(); throw e }
		worker = thread(name = "Monaka MTP UDP", isDaemon = true) {
			val buffer = ByteArray(4097) // Oversize/truncated datagrams must fail the fixed codec's size guard.
			while (!closed) {
				try {
					val packet = DatagramPacket(buffer, buffer.size)
					socket.receive(packet)
					val receivedAt = clock()
					try { inbox.receive(buffer.copyOf(packet.length), receivedAt) } catch (_: Exception) {
						inbox.count("WorkerValidationFailure")
					}
				} catch (e: Exception) {
					if (!closed) { failure = e; inbox.count("TransportFailure") }
					break
				}
			}
		}
	}
	override fun close() {
		closed = true; socket.close()
		if (Thread.currentThread() !== worker) worker.join(2000)
		check(!worker.isAlive) { "MTP receiver worker did not stop" }
	}
}
