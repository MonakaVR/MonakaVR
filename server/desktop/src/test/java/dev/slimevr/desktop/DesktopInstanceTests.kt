package dev.slimevr.desktop

import dev.slimevr.config.VRConfig
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstanceTests {
	@Test fun `EOF keeps standalone runtime alive but ends explicit owned instance`() {
		val standalone = DesktopInstance.fromEnvironment(emptyMap())
		val owned = standalone.copy(networkOnly = true)
		assertFalse(standalone.shouldStopForInput(null))
		assertTrue(owned.shouldStopForInput(null))
		assertTrue(standalone.shouldStopForInput("exit"))
		assertTrue(owned.shouldStopForInput("exit"))
		assertFalse(owned.shouldStopForInput("status"))
	}

	@Test fun `network-only refuses enabled OSC before any runtime construction`() {
		val standalone = DesktopInstance.fromEnvironment(emptyMap())
		val owned = standalone.copy(networkOnly = true)
		val config = VRConfig()
		owned.validateOutputs(config)
		config.vrcOSC.enabled = true
		assertFailsWith<IllegalArgumentException> { owned.validateOutputs(config) }
		standalone.validateOutputs(config)
		config.vrcOSC.enabled = false
		config.vmc.enabled = true
		assertFailsWith<IllegalArgumentException> { owned.validateOutputs(config) }
		config.vmc.enabled = false
		config.oscRouter.enabled = true
		assertFailsWith<IllegalArgumentException> { owned.validateOutputs(config) }
	}

	@Test fun `ordinary launch retains native integration and standard endpoints`() {
		val instance = DesktopInstance.fromEnvironment(emptyMap())
		assertFalse(instance.networkOnly)
		assertNull(instance.profile)
		assertEquals(21110, instance.endpoints.webSocketPort)
		assertNull(instance.endpoints.trackerPort)
		assertFalse(instance.endpoints.loopbackOnly)
	}

	@Test fun `isolated launch uses explicit profile loopback and ephemeral tracker socket`() {
		val directory = Files.createTempDirectory("desktop-profile-")
		val instance = DesktopInstance.fromEnvironment(mapOf("MONAKA_PROFILE_DIRECTORY" to directory.toString(), "MONAKA_NETWORK_ONLY" to "true", "MONAKA_WEBSOCKET_PORT" to "0"))
		assertEquals(directory, instance.profile)
		assertTrue(instance.networkOnly)
		assertTrue(instance.endpoints.loopbackOnly)
		assertEquals(0, instance.endpoints.trackerPort)
		assertEquals(0, instance.endpoints.webSocketPort)
	}

	@Test fun `invalid or implicit isolation fails before startup`() {
		for (environment in listOf(
			mapOf("MONAKA_PROFILE_DIRECTORY" to "relative"),
			mapOf("MONAKA_NETWORK_ONLY" to "true"),
			mapOf("MONAKA_NETWORK_ONLY" to "yes"),
			mapOf("MONAKA_WEBSOCKET_PORT" to "-1"),
			mapOf("MONAKA_WEBSOCKET_PORT" to "65536"),
			mapOf("MONAKA_WEBSOCKET_PORT" to "21110suffix"),
		)) {
			assertFailsWith<IllegalArgumentException> { DesktopInstance.fromEnvironment(environment) }
		}
	}
}
