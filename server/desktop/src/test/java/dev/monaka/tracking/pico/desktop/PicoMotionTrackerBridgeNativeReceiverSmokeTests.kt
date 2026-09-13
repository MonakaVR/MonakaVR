package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.pico.PicoMotionTrackerBridgeReceiverStateProvider
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PicoMotionTrackerBridgeNativeReceiverSmokeTests {
	private val halfSqrt2 = 0.70710677f

	@Test
	fun syntheticPublisherCrossesNativeReceiverCAbiAndJnaBoundary() {
		val libraryPath = requiredFileProperty("monaka.pico.bridge.library")
		val publisherPath = requiredFileProperty("monaka.pico.bridge.smokePublisher")

		PicoMotionTrackerBridgeNativeReceiverClient(
			PicoMotionTrackerBridgeNativeReceiverConfig(
				libraryName = libraryPath.toString(),
				listenPort = 0,
				timeSyncIntervalNanos = 1_000_000L,
				staleTimeoutNanos = 500_000_000L,
			),
		).use { client ->
			assertTrue(client.listenPort in 1..0xffff)
			val provider = PicoMotionTrackerBridgeReceiverStateProvider(client)

			val process = ProcessBuilder(
				publisherPath.toString(),
				client.listenPort.toString(),
			)
				.redirectErrorStream(true)
				.start()

			var observed = provider.snapshot().firstOrNull()
			val deadline = System.nanoTime() + 5_000_000_000L
			while (
				System.nanoTime() < deadline &&
				(observed == null || observed.mappedPosePcMonotonicNanos == null)
			) {
				Thread.sleep(2L)
				observed = provider.snapshot().firstOrNull()
			}

			assertNotNull(observed, "synthetic bridge publisher did not reach the JNA receiver")
			assertEquals("MONAKA-SMOKE-001", observed.serial)
			assertEquals(true, observed.connectedSnapshot)
			assertEquals(true, observed.positionValid)
			assertEquals(true, observed.orientationSamplePresent)
			assertEquals(Vector3(1.25f, 2.5f, -3.75f), observed.positionMeters)
			assertEquals(Quaternion(halfSqrt2, halfSqrt2, 0f, 0f), observed.orientation)
			assertTrue(observed.lastPoseReceivePcMonotonicNanos > 0L)
			assertNotNull(
				observed.mappedPosePcMonotonicNanos,
				"native receiver clock sync did not become available",
			)

			assertTrue(process.waitFor(5L, TimeUnit.SECONDS), "synthetic publisher did not exit")
			val output = process.inputStream.bufferedReader().use { it.readText() }
			assertEquals(0, process.exitValue(), output)
			assertTrue(output.contains("MONAKA_SMOKE_SENT"), output)
		}
	}

	private fun requiredFileProperty(name: String): Path {
		val value = System.getProperty(name)
			?: error("Required system property is missing: $name")
		val path = Path.of(value).toAbsolutePath().normalize()
		require(Files.isRegularFile(path)) { "$name does not point to a file: $path" }
		return path
	}
}
