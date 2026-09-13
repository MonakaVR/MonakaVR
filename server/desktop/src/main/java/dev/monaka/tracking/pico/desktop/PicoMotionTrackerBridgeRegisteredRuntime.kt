package dev.monaka.tracking.pico.desktop

import dev.monaka.tracking.ConstraintPipeline
import dev.monaka.tracking.ObservationBackendRunner
import dev.monaka.tracking.ObservationSourceProfile
import dev.monaka.tracking.ObservationSourceProfileRegistry
import dev.monaka.tracking.pico.PicoMotionTrackerBridgeBodyAssignments

/**
 * Policy for the live PICO 6DoF observation source.
 *
 * The default component timeout matches the native receiver stale threshold. Optical
 * loss itself remains component-wise: live orientation packets can therefore continue
 * as DEGRADED rotation while position is LOST without waiting for this timeout.
 */
data class PicoMotionTrackerBridgeRegisteredRuntimeConfig(
	val observationConfig: PicoMotionTrackerBridgeNativeObservationRuntimeConfig =
		PicoMotionTrackerBridgeNativeObservationRuntimeConfig(),
	val priority: Int = 100,
	val positionTimeoutNanos: Long = observationConfig.receiverConfig.staleTimeoutNanos,
	val rotationTimeoutNanos: Long = observationConfig.receiverConfig.staleTimeoutNanos,
) {
	init {
		require(positionTimeoutNanos >= 0L) { "positionTimeoutNanos must be non-negative" }
		require(rotationTimeoutNanos >= 0L) { "rotationTimeoutNanos must be non-negative" }
	}
}

/**
 * Complete PICO input registration below the IK/write-back boundary.
 *
 * This class owns the native receiver composition, the source profile, backend runner,
 * persistent serial-to-body assignment policy, and constraint pipeline. It intentionally
 * stops at [ConstraintPipeline]: HumanPoseManager/IK consumption is a later integration
 * step and is not silently coupled here.
 */
class PicoMotionTrackerBridgeRegisteredRuntime(
	val assignments: PicoMotionTrackerBridgeBodyAssignments = PicoMotionTrackerBridgeBodyAssignments(),
	config: PicoMotionTrackerBridgeRegisteredRuntimeConfig = PicoMotionTrackerBridgeRegisteredRuntimeConfig(),
) : AutoCloseable {
	val profile: ObservationSourceProfile = ObservationSourceProfile.sixDof(
		profileId = config.observationConfig.profileId,
		priority = config.priority,
		positionTimeoutNanos = config.positionTimeoutNanos,
		rotationTimeoutNanos = config.rotationTimeoutNanos,
	)

	private val profileRegistry = ObservationSourceProfileRegistry(listOf(profile))

	val pipeline = ConstraintPipeline(profileRegistry = profileRegistry)

	private val observationRuntime = PicoMotionTrackerBridgeNativeObservationRuntime(
		config = config.observationConfig,
		targetResolver = assignments::resolve,
	)

	val runner = ObservationBackendRunner(
		pipeline = pipeline,
		backends = listOf(observationRuntime.backend),
	)

	val listenPort: Int
		get() = observationRuntime.listenPort

	private var closed = false

	fun poll(observedAtNanos: Long = System.nanoTime()): Int {
		check(!closed) { "PicoMotionTrackerBridge registered runtime is closed" }
		require(observedAtNanos >= 0L) { "observedAtNanos must be non-negative" }
		return runner.pollAll(observedAtNanos)
	}

	override fun close() {
		if (closed) return
		closed = true
		runner.clear()
		observationRuntime.close()
	}
}
