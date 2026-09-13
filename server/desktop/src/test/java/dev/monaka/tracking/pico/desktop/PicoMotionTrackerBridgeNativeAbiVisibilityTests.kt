package dev.monaka.tracking.pico.desktop

import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertTrue

class PicoMotionTrackerBridgeNativeAbiVisibilityTests {
	@Test
	fun jnaAbiTypesArePublicToJvmReflection() {
		val types = listOf(
			PicoMotionTrackerBridgeNativeLibrary::class.java,
			NativeReceiverConfig::class.java,
			NativeTrackerState::class.java,
			NativeClockEstimate::class.java,
		)

		types.forEach { type ->
			assertTrue(
				Modifier.isPublic(type.modifiers),
				"${type.name} must be public for JNA reflection/proxy dispatch",
			)
		}
	}
}
