package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.ObservationQuality
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PicoMotionTrackerBridgeNativeObservationRuntimeSmokeTests {
	private val halfSqrt2 = 0.70710677f

	@Test
	fun productionCompositionMapsNativeOutputAIntoMonakaObservation() {
		val libraryPath = requiredFileProperty("monaka.pico.bridge.library")
		val publisherPath = requiredFileProperty("monaka.pico.bridge.smokePublisher")

		PicoMotionTrackerBridgeNativeObservationRuntime(
			config = PicoMotionTrackerBridgeNativeObservationRuntimeConfig(
				receiverConfig = PicoMotionTrackerBridgeNativeReceiverConfig(
					libraryName = libraryPath.toString(),
					listenPort = 0,
					timeSyncIntervalNanos = 1_000_000L,
					staleTimeoutNanos = 500_000_000L,
				),
			),
			targetResolver = { serial ->
				if (serial == "MONAKA-SMOKE-001") TrackerPosition.HIP else null
			},
		).use { runtime ->
			assertTrue(runtime.listenPort in 1..0xffff)

			val process = ProcessBuilder(
				publisherPath.toString(),
				runtime.listenPort.toString(),
			)
				.redirectErrorStream(true)
				.start()

			var observation = runtime.backend.poll(System.nanoTime()).firstOrNull()
			val deadline = System.nanoTime() + 5_000_000_000L
			while (System.nanoTime() < deadline && observation == null) {
				Thread.sleep(2L)
				observation = runtime.backend.poll(System.nanoTime()).firstOrNull()
			}

			assertNotNull(observation, "synthetic bridge publisher did not reach the production observation runtime")
			assertEquals("pico-ot:MONAKA-SMOKE-001", observation.sourceId)
			assertEquals(TrackerPosition.HIP, observation.target)
			assertEquals(Vector3(1.25f, 2.5f, -3.75f), observation.position)
			assertEquals(
				Quaternion(halfSqrt2, 0f, 0f, -halfSqrt2),
				observation.rotation,
			)
			assertEquals(ObservationQuality.TRACKED, observation.positionQuality)
			assertEquals(ObservationQuality.TRACKED, observation.rotationQuality)
			assertTrue(observation.observedAtNanos > 0L)

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
