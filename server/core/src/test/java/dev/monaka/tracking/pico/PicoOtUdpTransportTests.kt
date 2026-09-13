package dev.monaka.tracking.pico

import dev.monaka.tracking.ConstraintPipeline
import dev.monaka.tracking.ObservationBackendRunner
import dev.monaka.tracking.ObservationSourceProfile
import dev.monaka.tracking.ObservationSourceProfileRegistry
import dev.slimevr.tracking.trackers.TrackerPosition
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PicoOtUdpTransportTests {
	private fun frame(
		sessionId: String = "session-a",
		sequence: Long,
		positionX: Float = sequence.toFloat(),
	): PicoOtTransportFrame = PicoOtTransportFrame(
		sessionId = sessionId,
		trackingSpaceId = "space-1",
		sequence = sequence,
		trackers = listOf(
			PicoOtTrackerSample(
				trackerId = "hip",
				position = Vector3(positionX, 1f, 0f),
				rotation = Quaternion.IDENTITY,
			),
		),
	)

	@Test
	fun inboxKeepsNewestFrameAndRejectsRetiredSessionDatagrams() {
		val inbox = PicoOtTransportFrameInbox()
		val first = frame(sequence = 10L)

		assertEquals(PicoOtTransportFrameInbox.SubmitResult.ACCEPTED, inbox.submit(first))
		assertEquals(PicoOtTransportFrameInbox.SubmitResult.DUPLICATE, inbox.submit(first))
		assertEquals(PicoOtTransportFrameInbox.SubmitResult.STALE, inbox.submit(frame(sequence = 9L)))
		assertEquals(10L, inbox.latestFrame().sequence)

		assertFailsWith<IllegalArgumentException> {
			inbox.submit(frame(sequence = 10L, positionX = 99f))
		}

		assertEquals(
			PicoOtTransportFrameInbox.SubmitResult.ACCEPTED,
			inbox.submit(frame(sessionId = "session-b", sequence = 0L)),
		)
		assertEquals(
			PicoOtTransportFrameInbox.SubmitResult.RETIRED_SESSION,
			inbox.submit(frame(sessionId = "session-a", sequence = 11L)),
		)
		assertEquals("session-b", inbox.latestFrame().sessionId)
	}

	@Test
	fun udpProviderReceivesNewestLoopbackFrameAndIgnoresOlderDatagram() {
		val loopback = InetAddress.getLoopbackAddress()
		val provider = PicoOtUdpFrameProvider(loopback, port = 0)
		try {
			sendDatagram(loopback, provider.localPort, PicoOtJsonWireCodec.encodeLine(frame(sequence = 2L)))
			waitUntil { provider.acceptedFrames == 1L }

			sendDatagram(loopback, provider.localPort, PicoOtJsonWireCodec.encodeLine(frame(sequence = 1L)))
			waitUntil { provider.droppedFrames == 1L }

			assertEquals(2L, provider.latestFrame().sequence)
			assertEquals(2L, provider.receivedDatagrams)
			assertEquals(loopback, provider.lastRemoteAddress)
			assertNull(provider.lastError)
		} finally {
			provider.close()
		}
		assertTrue(!provider.isRunning)
	}

	@Test
	fun udpProviderRejectsMalformedDatagramWithoutReplacingLatestFrame() {
		val loopback = InetAddress.getLoopbackAddress()
		val provider = PicoOtUdpFrameProvider(loopback, port = 0)
		try {
			sendDatagram(loopback, provider.localPort, PicoOtJsonWireCodec.encodeLine(frame(sequence = 3L)))
			waitUntil { provider.acceptedFrames == 1L }

			sendDatagram(loopback, provider.localPort, "{not-json}\n")
			waitUntil { provider.malformedDatagrams == 1L }

			assertEquals(3L, provider.latestFrame().sequence)
			assertNotNull(provider.lastError)
		} finally {
			provider.close()
		}
	}

	@Test
	fun repeatedTransportFrameDoesNotRefreshObservationAge() {
		var current = frame(sequence = 1L, positionX = 0.1f)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { current })
		val registry = ObservationSourceProfileRegistry(
			listOf(
				ObservationSourceProfile.sixDof(
					profileId = "pico-sixdof",
					priority = 100,
					positionTimeoutNanos = 50L,
					rotationTimeoutNanos = 50L,
				),
			),
		)
		val pipeline = ConstraintPipeline(profileRegistry = registry)
		val backend = PicoOtObservationBackend(
			backendId = "pico-ot",
			profileId = "pico-sixdof",
			dataSource = dataSource,
			targetResolver = { TrackerPosition.HIP },
		)
		val runner = ObservationBackendRunner(pipeline, listOf(backend))

		runner.poll("pico-ot", 100L)
		assertEquals(100L, pipeline.observations().single().observedAtNanos)

		// Polling the same sequence again must not make a frozen network pose fresh.
		runner.poll("pico-ot", 140L)
		assertEquals(100L, pipeline.observations().single().observedAtNanos)
		assertNull(pipeline.resolve(TrackerPosition.HIP, nowNanos = 151L).position)

		current = frame(sequence = 2L, positionX = 0.2f)
		runner.poll("pico-ot", 160L)
		assertEquals(160L, pipeline.observations().single().observedAtNanos)
		assertEquals(Vector3(0.2f, 1f, 0f), pipeline.resolve(TrackerPosition.HIP, 160L).position?.value)
	}

	@Test
	fun transportDataSourceDoesNotAllowRetiredSessionToReappear() {
		var current = frame(sessionId = "session-a", sequence = 100L)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { current })
		dataSource.snapshot(1L)

		current = frame(sessionId = "session-b", sequence = 0L)
		dataSource.snapshot(2L)
		assertEquals("session-b", dataSource.sessionId)

		current = frame(sessionId = "session-a", sequence = 101L)
		assertFailsWith<IllegalArgumentException> { dataSource.snapshot(3L) }
		assertEquals("session-b", dataSource.sessionId)
	}

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
				fail("Timed out waiting for UDP transport condition")
			}
			Thread.sleep(10L)
		}
	}
}
