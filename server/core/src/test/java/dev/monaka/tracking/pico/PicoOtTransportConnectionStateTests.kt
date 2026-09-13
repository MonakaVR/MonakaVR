package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.fail

class PicoOtTransportConnectionStateTests {
	@Test
	fun monitorTransitionsAcrossConfiguredAgeBoundariesAndRecovers() {
		val monitor = PicoOtTransportConnectionMonitor(
			PicoOtTransportConnectionPolicy(
				staleAfterNanos = 100L,
				disconnectedAfterNanos = 300L,
			),
		)

		assertEquals(PicoOtTransportConnectionState.WAITING, monitor.state(1_000L))
		assertEquals(null, monitor.ageNanos(1_000L))

		monitor.markAcceptedFrame(1_000L)
		assertEquals(PicoOtTransportConnectionState.CONNECTED, monitor.state(1_100L))
		assertEquals(PicoOtTransportConnectionState.STALE, monitor.state(1_101L))
		assertEquals(PicoOtTransportConnectionState.STALE, monitor.state(1_300L))
		assertEquals(PicoOtTransportConnectionState.DISCONNECTED, monitor.state(1_301L))

		monitor.markAcceptedFrame(1_400L)
		assertEquals(PicoOtTransportConnectionState.CONNECTED, monitor.state(1_400L))
		assertEquals(0L, monitor.ageNanos(1_400L))
	}

	@Test
	fun policyRejectsInvalidThresholdOrdering() {
		assertFailsWith<IllegalArgumentException> {
			PicoOtTransportConnectionPolicy(0L, 100L)
		}
		assertFailsWith<IllegalArgumentException> {
			PicoOtTransportConnectionPolicy(100L, 100L)
		}
		assertFailsWith<IllegalArgumentException> {
			PicoOtTransportConnectionPolicy(101L, 100L)
		}
	}

	@Test
	fun udpHealthUsesOnlyNewAcceptedFramesAndCloseForcesDisconnected() {
		val loopback = InetAddress.getLoopbackAddress()
		val policy = PicoOtTransportConnectionPolicy(
			staleAfterNanos = 50_000_000L,
			disconnectedAfterNanos = 100_000_000L,
		)
		val provider = PicoOtUdpFrameProvider(
			bindAddress = loopback,
			port = 0,
			connectionPolicy = policy,
		)

		assertEquals(PicoOtTransportConnectionState.WAITING, provider.connectionState())

		try {
			val first = frame(sequence = 1L)
			sendDatagram(loopback, provider.localPort, PicoOtJsonWireCodec.encodeLine(first))
			waitUntil { provider.acceptedFrames == 1L }

			val firstAcceptedAt = assertNotNull(provider.lastAcceptedFrameAtNanos)
			assertEquals(
				PicoOtTransportConnectionState.CONNECTED,
				provider.connectionState(firstAcceptedAt + policy.staleAfterNanos),
			)
			assertEquals(
				PicoOtTransportConnectionState.STALE,
				provider.connectionState(firstAcceptedAt + policy.staleAfterNanos + 1L),
			)
			assertEquals(
				PicoOtTransportConnectionState.DISCONNECTED,
				provider.connectionState(firstAcceptedAt + policy.disconnectedAfterNanos + 1L),
			)

			// A duplicate datagram proves traffic exists but must not make a frozen pose fresh.
			sendDatagram(loopback, provider.localPort, PicoOtJsonWireCodec.encodeLine(first))
			waitUntil { provider.droppedFrames == 1L }
			assertEquals(firstAcceptedAt, provider.lastAcceptedFrameAtNanos)

			sendDatagram(loopback, provider.localPort, PicoOtJsonWireCodec.encodeLine(frame(sequence = 2L)))
			waitUntil { provider.acceptedFrames == 2L }
			val recoveredAt = assertNotNull(provider.lastAcceptedFrameAtNanos)
			assertEquals(PicoOtTransportConnectionState.CONNECTED, provider.connectionState(recoveredAt))
		} finally {
			provider.close()
		}

		assertEquals(PicoOtTransportConnectionState.DISCONNECTED, provider.connectionState())
	}

	private fun frame(sequence: Long): PicoOtTransportFrame = PicoOtTransportFrame(
		sessionId = "session-health",
		trackingSpaceId = "space-health",
		sequence = sequence,
		trackers = listOf(
			PicoOtTrackerSample(
				trackerId = "hip",
				position = Vector3(sequence.toFloat(), 1f, 0f),
				rotation = Quaternion.IDENTITY,
			),
		),
	)

	private fun sendDatagram(
		address: InetAddress,
		port: Int,
		payload: String,
	) {
		val bytes = payload.toByteArray(StandardCharsets.UTF_8)
		DatagramSocket().use { sender ->
			sender.send(DatagramPacket(bytes, bytes.size, address, port))
		}
	}

	private fun waitUntil(
		timeoutMillis: Long = 2_000L,
		condition: () -> Boolean,
	) {
		val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
		while (!condition()) {
			if (System.nanoTime() >= deadline) {
				fail("Timed out waiting for PICO OT transport health condition")
			}
			Thread.sleep(10L)
		}
	}
}
