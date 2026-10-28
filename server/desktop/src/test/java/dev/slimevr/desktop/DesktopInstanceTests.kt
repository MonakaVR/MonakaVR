package dev.slimevr.desktop

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstanceTests {
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
