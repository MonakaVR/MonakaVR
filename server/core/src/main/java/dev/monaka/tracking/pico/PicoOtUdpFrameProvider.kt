package dev.monaka.tracking.pico

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP receiver for high-rate PICO OT authoritative snapshots.
 *
 * One UDP datagram carries exactly one UTF-8 JSON/NDJSON frame. UDP is appropriate
 * for the pose stream because each frame is a complete snapshot and newer frames
 * supersede older ones; transport loss therefore does not block subsequent poses.
 * Sequence/session ordering is enforced by [PicoOtTransportFrameInbox].
 *
 * This class intentionally contains no discovery or authentication. Callers choose
 * the bind address explicitly so production code can decide whether to expose the
 * socket to the LAN or only to loopback.
 */
class PicoOtUdpFrameProvider(
	bindAddress: InetAddress,
	port: Int = 0,
	connectionPolicy: PicoOtTransportConnectionPolicy,
	private val maxDatagramBytes: Int = 16 * 1024,
	receiveTimeoutMillis: Int = 250,
) : PicoOtTransportFrameProvider, AutoCloseable {
	private val inbox = PicoOtTransportFrameInbox()
	private val connectionMonitor = PicoOtTransportConnectionMonitor(connectionPolicy)
	private val socket = DatagramSocket(null)
	private val receivedDatagramsCounter = AtomicLong()
	private val acceptedFramesCounter = AtomicLong()
	private val droppedFramesCounter = AtomicLong()
	private val malformedDatagramsCounter = AtomicLong()

	@Volatile
	private var running = true

	@Volatile
	var lastRemoteAddress: InetAddress? = null
		private set

	@Volatile
	var lastError: String? = null
		private set

	private val receiveThread = Thread(::receiveLoop, "monaka-pico-ot-udp").apply {
		isDaemon = true
	}

	init {
		require(port in 0..65535) { "port must be between 0 and 65535" }
		require(maxDatagramBytes in 256..65_507) {
			"maxDatagramBytes must be between 256 and 65507"
		}
		require(receiveTimeoutMillis > 0) { "receiveTimeoutMillis must be positive" }

		socket.reuseAddress = true
		socket.soTimeout = receiveTimeoutMillis
		socket.bind(InetSocketAddress(bindAddress, port))
		receiveThread.start()
	}

	val localPort: Int
		get() = socket.localPort

	val receivedDatagrams: Long
		get() = receivedDatagramsCounter.get()

	val acceptedFrames: Long
		get() = acceptedFramesCounter.get()

	val droppedFrames: Long
		get() = droppedFramesCounter.get()

	val malformedDatagrams: Long
		get() = malformedDatagramsCounter.get()

	val isRunning: Boolean
		get() = running && !socket.isClosed

	val lastAcceptedFrameAtNanos: Long?
		get() = connectionMonitor.lastAcceptedFrameAtNanos

	fun lastAcceptedFrameAgeNanos(nowNanos: Long = System.nanoTime()): Long? =
		connectionMonitor.ageNanos(nowNanos)

	fun connectionState(nowNanos: Long = System.nanoTime()): PicoOtTransportConnectionState =
		if (isRunning) {
			connectionMonitor.state(nowNanos)
		} else {
			PicoOtTransportConnectionState.DISCONNECTED
		}

	override fun latestFrame(): PicoOtTransportFrame = inbox.latestFrame()

	override fun close() {
		if (!running) return
		running = false
		socket.close()
		receiveThread.join(1_000L)
	}

	private fun receiveLoop() {
		val buffer = ByteArray(maxDatagramBytes)
		while (running) {
			val packet = DatagramPacket(buffer, buffer.size)
			try {
				socket.receive(packet)
			} catch (_: SocketTimeoutException) {
				continue
			} catch (exception: SocketException) {
				if (running) lastError = exception.message ?: exception.javaClass.simpleName
				break
			}

			receivedDatagramsCounter.incrementAndGet()
			try {
				require(packet.length < maxDatagramBytes) {
					"PICO OT UDP datagram reached the configured receive limit and may be truncated"
				}
				val payload = String(
					packet.data,
					packet.offset,
					packet.length,
					StandardCharsets.UTF_8,
				)
				val frame = PicoOtJsonWireCodec.decodeLine(payload)
				lastRemoteAddress = packet.address
				lastError = null

				when (inbox.submit(frame)) {
					PicoOtTransportFrameInbox.SubmitResult.ACCEPTED -> {
						acceptedFramesCounter.incrementAndGet()
						connectionMonitor.markAcceptedFrame(System.nanoTime())
					}
					PicoOtTransportFrameInbox.SubmitResult.DUPLICATE,
					PicoOtTransportFrameInbox.SubmitResult.STALE,
					PicoOtTransportFrameInbox.SubmitResult.RETIRED_SESSION -> droppedFramesCounter.incrementAndGet()
				}
			} catch (exception: Exception) {
				malformedDatagramsCounter.incrementAndGet()
				lastError = exception.message ?: exception.javaClass.simpleName
			}
		}
	}
}
