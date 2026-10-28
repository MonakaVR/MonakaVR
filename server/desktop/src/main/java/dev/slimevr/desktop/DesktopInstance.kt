package dev.slimevr.desktop

import dev.slimevr.RuntimeEndpoints
import dev.slimevr.config.VRConfig
import java.nio.file.Path

/** An explicit isolated desktop instance; it never infers mode from file names or identities. */
data class DesktopInstance(val profile: Path?, val networkOnly: Boolean, val endpoints: RuntimeEndpoints) {
	fun shouldStopForInput(line: String?): Boolean = line == "exit" || (networkOnly && line == null)

	fun validateOutputs(config: VRConfig) {
		require(!networkOnly || (!config.vrcOSC.enabled && !config.vmc.enabled && !config.oscRouter.enabled)) {
			"Network-only profile must leave OSC outputs disabled"
		}
	}

	companion object {
		fun fromEnvironment(environment: Map<String, String> = System.getenv()): DesktopInstance {
			val profile = environment["MONAKA_PROFILE_DIRECTORY"]?.let {
				val path = Path.of(it)
				require(path.isAbsolute) { "MONAKA_PROFILE_DIRECTORY must be absolute" }
				path.normalize()
			}
			val networkOnly = when (environment["MONAKA_NETWORK_ONLY"]) {
				null, "false" -> false
				"true" -> true
				else -> throw IllegalArgumentException("MONAKA_NETWORK_ONLY must be true or false")
			}
			require(!networkOnly || profile != null) { "Network-only launch requires an explicit profile" }
			val port = environment["MONAKA_WEBSOCKET_PORT"]?.let {
				require(it.matches(Regex("[0-9]+"))) { "Invalid MONAKA_WEBSOCKET_PORT" }
				it.toInt()
			} ?: 21110
			return DesktopInstance(profile, networkOnly, RuntimeEndpoints(port, if (networkOnly) 0 else null, networkOnly))
		}
	}
}
