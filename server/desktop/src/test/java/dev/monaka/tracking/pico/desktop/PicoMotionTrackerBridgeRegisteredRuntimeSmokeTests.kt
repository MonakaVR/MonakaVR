package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.ObservationQuality
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeBodyAssignments
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

class PicoMotionTrackerBridgeRegisteredRuntimeSmokeTests {
	@Test
	fun syntheticTrackerIsAssignedProfiledAndResolvedByProductionRuntime() {
		val libraryPath = requiredFileProperty("monaka.pico.bridge.library")
		val publisherPath = requiredFileProperty("monaka.pico.bridge.smokePublisher")
		val assignments = PicoMotionTrackerBridgeBodyAssignments(
			mapOf("MONAKA-SMOKE-001" to TrackerPosition.LEFT_FOOT),
		)
		val receiverConfig = PicoMotionTrackerBridgeNativeReceiverConfig(
			libraryName = libraryPath.toString(),
			listenPort = 0,
			timeSyncIntervalNanos = 1_000_000L,
			staleTimeoutNanos = 500_000_000L,
		)

		PicoMotionTrackerBridgeRegisteredRuntime(
			assignments = assignments,
			config = PicoMotionTrackerBridgeRegisteredRuntimeConfig(
				observationConfig = PicoMotionTrackerBridgeNativeObservationRuntimeConfig(
					receiverConfig = receiverConfig,
				),
			),
		).use { runtime ->
			assertEquals("pico-sixdof", runtime.profile.profileId)
			assertEquals(100, runtime.profile.priority)
			assertEquals(1, runtime.runner.size)

			val process = ProcessBuilder(
				publisherPath.toString(),
				runtime.listenPort.toString(),
			)
				.redirectErrorStream(true)
				.start()

			val deadline = System.nanoTime() + 5_000_000_000L
			while (System.nanoTime() < deadline && runtime.pipeline.observationCount == 0) {
				runtime.poll()
				Thread.sleep(2L)
			}

			assertEquals(1, runtime.pipeline.observationCount, "synthetic tracker was not registered into the constraint pipeline")
			val constraint = runtime.pipeline.resolve(TrackerPosition.LEFT_FOOT, System.nanoTime())
			val position = assertNotNull(constraint.position)
			val rotation = assertNotNull(constraint.rotation)

			assertEquals("pico-ot:MONAKA-SMOKE-001", position.sourceId)
			assertEquals("pico-ot:MONAKA-SMOKE-001", rotation.sourceId)
			assertEquals(ObservationQuality.TRACKED, position.quality)
			assertEquals(ObservationQuality.TRACKED, rotation.quality)
			assertEquals(Vector3(1.25f, 2.5f, -3.75f), position.value)

			val s = 0.70710677f
			assertEquals(Quaternion(s, 0f, 0f, -s), rotation.value)

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
