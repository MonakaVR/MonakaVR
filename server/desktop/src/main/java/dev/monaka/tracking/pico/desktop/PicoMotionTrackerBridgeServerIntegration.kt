package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.pico.PicoMotionTrackerBridgeBodyAssignments

private const val PICO_ENABLED_PROPERTY = "monaka.pico.enabled"
private const val PICO_ENABLED_ENVIRONMENT = "MONAKA_PICO_ENABLED"

/**
 * Desktop feature gate for the live PicoMotionTrackerBridge receiver path.
 *
 * The gate is false by default. Keeping the decision outside the native receiver is
 * intentional: normal SlimeVR/Monaka startup must not attempt to load the bridge DLL
 * unless PICO input was explicitly requested.
 */
object PicoMotionTrackerBridgeFeatureGate {
	fun isEnabled(
		commandLineEnabled: Boolean,
		propertyValue: String? = System.getProperty(PICO_ENABLED_PROPERTY),
		environmentValue: String? = System.getenv(PICO_ENABLED_ENVIRONMENT),
	): Boolean {
		if (commandLineEnabled) return true
		return parseOptionalFlag(PICO_ENABLED_PROPERTY, propertyValue)
			?: parseOptionalFlag(PICO_ENABLED_ENVIRONMENT, environmentValue)
			?: false
	}

	private fun parseOptionalFlag(
		name: String,
		value: String?,
	): Boolean? {
		val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
		return when (normalized) {
			"1", "true", "yes", "on" -> true
			"0", "false", "no", "off" -> false
			else -> throw IllegalArgumentException(
				"$name must be one of true/false, 1/0, yes/no, or on/off; got '$value'",
			)
		}
	}
}

/**
 * Binds the registered PICO observation runtime to the desktop server tick without
 * coupling the native receiver to VRServer itself.
 *
 * [startIfEnabled] returns before creating any native object when [enabled] is false.
 * When enabled, the registered tick is expected to run on the VRServer loop before the
 * HumanPoseManager update. The current Monaka path still stops at ConstraintPipeline;
 * this class does not write constraints into SlimeVR trackers or IK yet.
 */
class PicoMotionTrackerBridgeServerIntegration private constructor(
	internal val runtime: PicoMotionTrackerBridgeRegisteredRuntime,
	private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
	@Volatile
	private var closed = false

	@Volatile
	var failure: Throwable? = null
		private set

	val isActive: Boolean
		get() = !closed && failure == null

	private fun tick() {
		if (!isActive) return
		try {
			runtime.poll()
		} catch (throwable: Throwable) {
			failure = throwable
			closeRuntime()
			try {
				onFailure(throwable)
			} catch (_: Throwable) {
				// Failure reporting must not take down the VRServer tick after PICO was disabled.
			}
		}
	}

	override fun close() {
		if (closed) return
		closed = true
		closeRuntime()
	}

	private fun closeRuntime() {
		try {
			runtime.close()
		} catch (closeFailure: Throwable) {
			if (failure == null) {
				failure = closeFailure
				try {
					onFailure(closeFailure)
				} catch (_: Throwable) {
				}
			}
		}
	}

	companion object {
		fun startIfEnabled(
			enabled: Boolean,
			addOnTick: (Runnable) -> Unit,
			assignments: PicoMotionTrackerBridgeBodyAssignments = PicoMotionTrackerBridgeBodyAssignments(),
			config: PicoMotionTrackerBridgeRegisteredRuntimeConfig = PicoMotionTrackerBridgeRegisteredRuntimeConfig(),
			onFailure: (Throwable) -> Unit = {},
		): PicoMotionTrackerBridgeServerIntegration? {
			if (!enabled) return null

			val runtime = PicoMotionTrackerBridgeRegisteredRuntime(
				assignments = assignments,
				config = config,
			)
			val integration = PicoMotionTrackerBridgeServerIntegration(runtime, onFailure)
			try {
				addOnTick(Runnable { integration.tick() })
			} catch (throwable: Throwable) {
				integration.close()
				throw throwable
			}
			return integration
		}
	}
}
