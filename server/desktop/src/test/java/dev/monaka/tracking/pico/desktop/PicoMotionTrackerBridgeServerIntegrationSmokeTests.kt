package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.ObservationQuality
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeBodyAssignments
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PicoMotionTrackerBridgeServerIntegrationSmokeTests {
	@Test
	fun featureGateIsOffByDefaultAndAcceptsExplicitOptIn() {
		assertFalse(
			PicoMotionTrackerBridgeFeatureGate.isEnabled(
				commandLineEnabled = false,
				propertyValue = null,
				environmentValue = null,
			),
		)
		assertTrue(
			PicoMotionTrackerBridgeFeatureGate.isEnabled(
				commandLineEnabled = true,
				propertyValue = null,
				environmentValue = null,
			),
		)
		assertTrue(
			PicoMotionTrackerBridgeFeatureGate.isEnabled(
				commandLineEnabled = false,
				propertyValue = "on",
				environmentValue = null,
			),
		)
		assertFalse(
			PicoMotionTrackerBridgeFeatureGate.isEnabled(
				commandLineEnabled = false,
				propertyValue = "false",
				environmentValue = "true",
			),
		)
		assertFailsWith<IllegalArgumentException> {
			PicoMotionTrackerBridgeFeatureGate.isEnabled(
				commandLineEnabled = false,
				propertyValue = "maybe",
				environmentValue = null,
			)
		}
	}

	@Test
	fun disabledIntegrationDoesNotCreateNativeReceiverOrRegisterTick() {
		var tickRegistered = false
		val integration = PicoMotionTrackerBridgeServerIntegration.startIfEnabled(
			enabled = false,
			addOnTick = { tickRegistered = true },
		)

		assertNull(integration)
		assertFalse(tickRegistered)
	}

	@Test
	fun syntheticTrackerFlowsThroughRegisteredServerTick() {
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
		val ticks = mutableListOf<Runnable>()
		val failures = mutableListOf<Throwable>()

		val integration = assertNotNull(
			PicoMotionTrackerBridgeServerIntegration.startIfEnabled(
				enabled = true,
				addOnTick = { ticks += it },
				assignments = assignments,
				config = PicoMotionTrackerBridgeRegisteredRuntimeConfig(
					observationConfig = PicoMotionTrackerBridgeNativeObservationRuntimeConfig(
						receiverConfig = receiverConfig,
					),
				),
				onFailure = { failures += it },
			),
		)

		integration.use {
			assertEquals(1, ticks.size)
			assertTrue(integration.isActive)

			val process = ProcessBuilder(
				publisherPath.toString(),
				integration.runtime.listenPort.toString(),
			)
				.redirectErrorStream(true)
				.start()

			val deadline = System.nanoTime() + 5_000_000_000L
			while (System.nanoTime() < deadline && integration.runtime.pipeline.observationCount == 0) {
				ticks.single().run()
				Thread.sleep(2L)
			}

			assertTrue(failures.isEmpty(), failures.joinToString { it.toString() })
			assertEquals(1, integration.runtime.pipeline.observationCount)

			val constraint = integration.runtime.pipeline.resolve(TrackerPosition.LEFT_FOOT, System.nanoTime())
			val position = assertNotNull(constraint.position)
			val rotation = assertNotNull(constraint.rotation)
			assertEquals("pico-ot:MONAKA-SMOKE-001", position.sourceId)
			assertEquals("pico-ot:MONAKA-SMOKE-001", rotation.sourceId)
			assertEquals(ObservationQuality.TRACKED, position.quality)
			assertEquals(ObservationQuality.TRACKED, rotation.quality)

			assertTrue(process.waitFor(5L, TimeUnit.SECONDS), "synthetic publisher did not exit")
			val output = process.inputStream.bufferedReader().use { it.readText() }
			assertEquals(0, process.exitValue(), output)
			assertTrue(output.contains("MONAKA_SMOKE_SENT"), output)
		}

		assertFalse(integration.isActive)
		ticks.single().run()
		assertTrue(failures.isEmpty())
	}

	private fun requiredFileProperty(name: String): Path {
		val value = System.getProperty(name)
			?: error("Required system property is missing: $name")
		val path = Path.of(value).toAbsolutePath().normalize()
		require(Files.isRegularFile(path)) { "$name does not point to a file: $path" }
		return path
	}
}
